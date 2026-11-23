package com.weavo.crawler.collection.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.FailureEvidence;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.ObjectReference;
import com.weavo.siteaccess.SiteAccess.PageSnapshot;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.worker.application.AttemptContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Stages HTML locally so network uploads can use the attempt's separate completion window. */
final class CollectionSnapshot implements AutoCloseable {
  private static final JsonMapper JSON =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  private final String bucket;
  private final UUID jobId;
  private final AttemptContext attempt;
  private final String prefix;
  private final Path directory;
  private final List<ListSnapshot> lists = new ArrayList<>();
  private ListSnapshot list;
  private int itemCount;
  private int nextDetailId = 1;

  CollectionSnapshot(String bucket, UUID jobId, AttemptContext attempt) {
    this.bucket = bucket;
    this.jobId = jobId;
    this.attempt = attempt;
    this.prefix = "collections/%s/attempts/%s/".formatted(jobId, attempt.id());
    try {
      directory = Files.createTempDirectory("weavo-capture-");
    } catch (IOException failure) {
      throw new UncheckedIOException("Could not create snapshot staging directory", failure);
    }
  }

  void captureList(PageSnapshot page, Navigation navigation) {
    String id = String.format(Locale.ROOT, "list-%04d", lists.size() + 1);
    list =
        new ListSnapshot(
            id,
            page.url().toString(),
            attempt.now().toString(),
            navigation.listAccess().method().name(),
            capture("lists/" + id + ".html", page.html()),
            new ArrayList<>(),
            navigation.itemSelector());
    lists.add(list);
  }

  Detail captureDetail(PageSnapshot page, List<Consumer> consumers) {
    var ref = capture("details/detail-%04d.html".formatted(nextDetailId++), page.html());
    return new Detail(page.url().toString(), ref, List.copyOf(consumers));
  }

  void addItem(
      int itemIndex,
      List<SourceUrl> sourceUrls,
      List<Detail> details,
      List<String> failedParticipants) {
    String key = list.snapshotId() + String.format(Locale.ROOT, ":item-%04d", itemIndex);
    list.items()
        .add(
            new Item(
                key,
                itemIndex,
                itemCount++,
                List.copyOf(sourceUrls),
                List.copyOf(details),
                List.copyOf(failedParticipants)));
  }

  int itemCount() {
    return itemCount;
  }

  ObjectReference publish(CollectionExecute request, FixedCollection.SnapshotUpload store)
      throws InterruptedException {
    var completedLists = lists.stream().filter(page -> !page.items().isEmpty()).toList();
    var allowed =
        request.projects().stream()
            .map(project -> project.projectSourceId())
            .collect(Collectors.toSet());
    validateParticipants(completedLists, allowed);
    for (ListSnapshot page : completedLists) {
      uploadHtml(page.objectRef(), store);
      for (Item item : page.items()) {
        for (Detail detail : item.details()) {
          uploadHtml(detail.objectRef(), store);
        }
      }
    }
    var manifest =
        new Manifest(
            1,
            jobId.toString(),
            attempt.id().toString(),
            request.sourceId(),
            request.navigationRevision(),
            attempt.now().toString(),
            completedLists);
    var ref = new ObjectReference(bucket, prefix + "manifest.json");
    put(store, ref, "application/json", JSON.writeValueAsBytes(manifest));
    attempt.requireOwnership();
    return ref;
  }

  FailureEvidence storeFailure(
      CollectionProject project,
      String fieldId,
      List<String> rawValues,
      FixedCollection.SnapshotUpload store)
      throws InterruptedException {
    var observedAt = attempt.now();
    var ref =
        new ObjectReference(
            bucket,
            "failures/CRAWLER/%s/%s/%s/%s/0.json"
                .formatted(jobId, attempt.id(), project.projectSourceId(), fieldId));
    var body =
        new FieldFailureSidecar(
            1,
            "CRAWLER",
            jobId.toString(),
            attempt.id().toString(),
            project.projectId(),
            project.projectSourceId(),
            fieldId,
            0,
            observedAt.toString(),
            rawValues);
    put(store, ref, "application/json", JSON.writeValueAsBytes(body));
    return new FailureEvidence(
        project.projectSourceId(), fieldId, ref, observedAt.plus(Duration.ofDays(7)).toString());
  }

  private record FieldFailureSidecar(
      int formatVersion,
      String reportingService,
      String jobId,
      String attemptId,
      String projectId,
      String projectSourceId,
      String fieldId,
      int failureIndex,
      String observedAt,
      List<String> rawValues) {}

  private static void validateParticipants(List<ListSnapshot> pages, Set<String> allowed) {
    for (var page : pages) {
      for (var item : page.items()) {
        var participants = new HashSet<String>();
        for (var source : item.sourceUrls()) {
          if (!allowed.contains(source.projectSourceId())
              || !participants.add(source.projectSourceId())) {
            throw new IllegalStateException("Invalid snapshot item participant");
          }
        }
        var failed = new HashSet<String>();
        for (var id : item.failedProjectSourceIds()) {
          if (!participants.contains(id) || !failed.add(id)) {
            throw new IllegalStateException("Invalid failed snapshot item participant");
          }
        }
        for (var detail : item.details()) {
          var consumers = new HashSet<String>();
          for (var consumer : detail.consumers()) {
            if (!participants.contains(consumer.projectSourceId())
                || !consumers.add(consumer.projectSourceId())) {
              throw new IllegalStateException("Invalid snapshot detail consumer");
            }
          }
        }
      }
    }
  }

  private ObjectReference capture(String path, String html) {
    try {
      Files.writeString(localPath(path), html, StandardCharsets.UTF_8);
      return new ObjectReference(bucket, prefix + path);
    } catch (IOException failure) {
      throw new UncheckedIOException("Could not stage captured HTML", failure);
    }
  }

  private void uploadHtml(ObjectReference ref, FixedCollection.SnapshotUpload store)
      throws InterruptedException {
    try {
      attempt.requireOwnership();
      byte[] html = Files.readAllBytes(localPath(ref.key()));
      put(store, ref, "text/html; charset=utf-8", html);
    } catch (IOException failure) {
      throw new UncheckedIOException("Could not read staged HTML", failure);
    }
  }

  private void put(
      FixedCollection.SnapshotUpload store, ObjectReference ref, String type, byte[] bytes)
      throws InterruptedException {
    attempt.requireOwnership();
    try {
      store.put(ref.key(), type, bytes);
    } catch (SdkException | IllegalStateException failure) {
      if (Thread.currentThread().isInterrupted()) {
        var interrupted = new InterruptedException("Snapshot upload was interrupted");
        interrupted.initCause(failure);
        throw interrupted;
      }
      throw new UploadFailed(failure);
    }
  }

  static final class UploadFailed extends RuntimeException {
    UploadFailed(RuntimeException cause) {
      super("Could not store a snapshot object", cause);
    }
  }

  private Path localPath(String path) {
    return directory.resolve(path.substring(path.lastIndexOf('/') + 1));
  }

  @Override
  public void close() {
    try (var files = Files.newDirectoryStream(directory)) {
      for (Path file : files) {
        Files.deleteIfExists(file);
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Could not remove staged HTML", failure);
    }
    try {
      Files.deleteIfExists(directory);
    } catch (IOException failure) {
      throw new UncheckedIOException("Could not remove snapshot staging directory", failure);
    }
  }

  private record Manifest(
      int formatVersion,
      String collectionJobId,
      String attemptId,
      String sourceId,
      long navigationRevision,
      String createdAt,
      List<ListSnapshot> listSnapshots) {}

  private record ListSnapshot(
      String snapshotId,
      String url,
      String capturedAt,
      String accessMethod,
      ObjectReference objectRef,
      List<Item> items,
      String itemSelector) {}

  private record Item(
      String recordKey,
      int itemIndex,
      int sequenceIndex,
      List<SourceUrl> sourceUrls,
      List<Detail> details,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> failedProjectSourceIds) {}

  record SourceUrl(String projectSourceId, String sourceUrl) {}

  record Detail(String url, ObjectReference objectRef, List<Consumer> consumers) {}

  record Consumer(String projectSourceId, List<String> fieldIds) {}
}
