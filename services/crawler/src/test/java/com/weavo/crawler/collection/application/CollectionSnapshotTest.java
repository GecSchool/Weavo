package com.weavo.crawler.collection.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.weavo.crawler.collection.application.CollectionSnapshot.SourceUrl;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.siteaccess.SiteAccess.PageSnapshot;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.worker.application.AttemptContext;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectionSnapshotTest {
  @Test
  void rejectsUnknownOrDuplicateParticipantsBeforeUploading() {
    var attempt = mock(AttemptContext.class);
    when(attempt.id()).thenReturn(UUID.randomUUID());
    when(attempt.now()).thenReturn(Instant.parse("2026-11-23T12:00:00Z"));
    URI url = URI.create("https://school.example/list");
    var navigation =
        new Navigation(
            url.toString(),
            List.of(),
            new Navigation.ListAccess(Navigation.AccessMethod.HTTP, null),
            "article",
            new Navigation.Pagination(
                Navigation.PaginationType.NONE, null, null, null, null, null, null, null));
    var allowed =
        new CollectionProject("project", "source", 1L, 1L, "FULL", null, List.of(), List.of());
    var request = new CollectionExecute("shared", "INITIAL", 20, 1L, navigation, List.of(allowed));
    for (var participants :
        List.of(
            List.of(new SourceUrl("other", null)),
            List.of(new SourceUrl("source", null), new SourceUrl("source", null)))) {
      try (var snapshot = new CollectionSnapshot("artifacts", UUID.randomUUID(), attempt)) {
        snapshot.captureList(new PageSnapshot(url, "<article>공지</article>"), navigation);
        snapshot.addItem(0, participants, List.of(), List.of());
        assertThatThrownBy(() ->
                snapshot.publish(
                    request,
                    (key, mime, bytes) -> {
                      throw new AssertionError("Invalid data reached storage");
                    }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Invalid snapshot item participant");
      }
    }
  }
}
