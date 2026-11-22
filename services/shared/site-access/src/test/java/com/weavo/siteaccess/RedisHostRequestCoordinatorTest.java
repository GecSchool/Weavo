package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.CANCELLED;
import static com.weavo.siteaccess.SiteAccessException.Kind.REQUEST_COORDINATION_FAILED;
import static com.weavo.siteaccess.SiteAccessException.Kind.WORK_LIMIT;
import static com.weavo.siteaccess.SiteFixture.reply;
import static com.weavo.siteaccess.SiteFixture.work;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

@Tag("redis")
class RedisHostRequestCoordinatorTest {
  private static final Duration WORK = Duration.ofSeconds(8);

  @Test
  void coordinatesSameHostsAcrossClientsAndAllowsDifferentHostsToRunTogether() throws Exception {
    URI target = target();
    URI variant =
        URI.create(
            "https://" + target.getHost().toUpperCase(java.util.Locale.ROOT) + ".:8443/other");
    try (var first = coordinator();
        var second = coordinator()) {
      long releasedAt;
      try (var same = first.acquire(target, work(WORK));
          var different = second.acquire(target(), work(WORK))) {
        assertThat(
                assertThrows(
                        SiteAccessException.class,
                        () -> second.acquire(variant, work(Duration.ofMillis(200))))
                    .kind())
            .isEqualTo(WORK_LIMIT);
        same.check();
        different.check();
        releasedAt = System.nanoTime();
      }
      try (var next = second.acquire(variant, work(WORK))) {
        assertThat(Duration.ofNanos(System.nanoTime() - releasedAt))
            .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        next.check();
      }
    }
  }

  @Test
  void keepsAnExpiredOwnerBlockedUntilItsRequestHasEnded() throws Exception {
    URI target = target();
    try (var first = coordinator();
        var second = coordinator()) {
      try (var old = first.acquire(target, work(WORK))) {
        Thread.sleep(RedisHostRequestCoordinator.LEASE.plusMillis(100));
        assertThat(assertThrows(SiteAccessException.class, old::check).kind())
            .isEqualTo(REQUEST_COORDINATION_FAILED);
        assertThat(
                assertThrows(
                        SiteAccessException.class,
                        () -> second.acquire(target, work(Duration.ofMillis(200))))
                    .kind())
            .isEqualTo(WORK_LIMIT);
      }
      try (var next = second.acquire(target, work(WORK))) {
        next.check();
      }
    }
  }

  @Test
  void cancelsAnActiveRequestWhenRedisStopsConfirmingItsLease() throws Exception {
    try (var first = coordinator();
        var second = coordinator();
        var redis = new Jedis(redisUri());
        var fixture = new SiteFixture();
        var executor = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var finish = new CountDownLatch(1);
      fixture.route(
          "/wait",
          request -> {
            entered.countDown();
            try {
              finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
            reply(request, 200, "text/html", "<main>Ready</main>");
          });
      var access = fixture.access(first).open(work(WORK));
      var target = URI.create(fixture.url("/wait"));
      var result = executor.submit(() -> access.fetchHtml(target.toString()));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        redis.clientPause(1_500);
        var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertThat(failure.getCause())
            .isInstanceOfSatisfying(
                SiteAccessException.class,
                error -> assertThat(error.kind()).isEqualTo(REQUEST_COORDINATION_FAILED));
        assertThat(
                assertThrows(SiteAccessException.class, () -> second.acquire(target, work(WORK)))
                    .kind())
            .isEqualTo(REQUEST_COORDINATION_FAILED);
      } finally {
        redis.clientUnpause();
        finish.countDown();
      }
      try (var next = second.acquire(target, work(WORK))) {
        next.check();
      }
    }
  }

  @Test
  void holdsTheOwnerAfterCancellationUntilTheTransportConfirmsCompletion() throws Exception {
    try (var first = coordinator();
        var second = coordinator();
        var fixture = new SiteFixture();
        var executor = Executors.newSingleThreadExecutor()) {
      var enteredDns = new CountDownLatch(1);
      var finishDns = new CountDownLatch(1);
      var cancelled = new AtomicBoolean();
      var access =
          new SiteAccess(
              first,
              host -> {
                enteredDns.countDown();
                try {
                  if (finishDns.await(6, TimeUnit.SECONDS)) {
                    return List.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
                  }
                } catch (InterruptedException error) {
                  Thread.currentThread().interrupt();
                }
                throw new UnknownHostException("Test lookup did not complete");
              });
      URI target = URI.create(fixture.url("/unused"));
      var result =
          executor.submit(
              () -> access.open(work(WORK, cancelled::get)).fetchHtml(target.toString()));
      try {
        assertThat(enteredDns.await(3, TimeUnit.SECONDS)).isTrue();
        cancelled.set(true);
        var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertThat(failure.getCause())
            .isInstanceOfSatisfying(
                SiteAccessException.class, error -> assertThat(error.kind()).isEqualTo(CANCELLED));
        Thread.sleep(RedisHostRequestCoordinator.LEASE.plusMillis(100));
        assertThat(
                assertThrows(
                        SiteAccessException.class,
                        () -> second.acquire(target, work(Duration.ofMillis(200))))
                    .kind())
            .isEqualTo(WORK_LIMIT);
      } finally {
        finishDns.countDown();
      }
      try (var next = second.acquire(target, work(WORK))) {
        next.check();
        assertThat(fixture.requests).isEmpty();
      }
    }
  }

  @Test
  void blocksALostRedisLeaseUntilTheOriginalRequestConfirmsCompletion() throws Exception {
    URI target = target();
    try (var first = coordinator();
        var second = coordinator();
        var redis = new Jedis(redisUri())) {
      try (var active = first.acquire(target, work(WORK))) {
        redis.del(redisKey(target));
        assertThat(
                assertThrows(
                        SiteAccessException.class,
                        () -> second.acquire(target, work(Duration.ofMillis(300))))
                    .kind())
            .isEqualTo(WORK_LIMIT);
      }
      try (var next = second.acquire(target, work(WORK))) {
        next.check();
      }
    }
  }

  @Test
  void replacesAnExpiredRedisLeaseAfterClaimingPostgresOwnership() throws Exception {
    URI target = target();
    String key = redisKey(target);
    String oldOwner = UUID.randomUUID().toString();
    try (var coordinator = coordinator();
        var redis = new Jedis(redisUri())) {
      redis.hset(key, Map.of("owner", oldOwner, "until", "1"));
      redis.pexpire(key, 30_000);

      try (var permit = coordinator.acquire(target, work(WORK))) {
        permit.check();
        assertThat(redis.hget(key, "owner")).isNotBlank().isNotEqualTo(oldOwner);
        assertThat(redis.pttl(key)).isEqualTo(-1L);
      }
    }
  }

  @Test
  void waitsWhenPostgresStillHasTheOriginalOwnerOfAnExpiredRedisLease() throws Exception {
    URI target = target();
    String key = redisKey(target);
    try (var first = coordinator();
        var second = coordinator();
        var redis = new Jedis(redisUri())) {
      try (var active = first.acquire(target, work(WORK))) {
        String original = redis.hget(key, "owner");
        redis.hset(key, "until", "1");

        assertThat(
                assertThrows(
                        SiteAccessException.class,
                        () -> second.acquire(target, work(Duration.ofMillis(300))))
                    .kind())
            .isEqualTo(WORK_LIMIT);
        assertThat(redis.hget(key, "owner")).isEqualTo(original);
      }
      try (var next = second.acquire(target, work(WORK))) {
        next.check();
      }
    }
  }

  @Test
  void preservesAValidRedisLeaseEvenWhenPostgresAllowsTheClaim() {
    URI target = target();
    String key = redisKey(target);
    String owner = UUID.randomUUID().toString();
    try (var coordinator = coordinator();
        var redis = new Jedis(redisUri())) {
      redis.hset(
          key, Map.of("owner", owner, "until", Long.toString(System.currentTimeMillis() + 60_000)));

      assertThat(
              assertThrows(SiteAccessException.class, () -> coordinator.acquire(target, work(WORK)))
                  .kind())
          .isEqualTo(REQUEST_COORDINATION_FAILED);
      assertThat(redis.hget(key, "owner")).isEqualTo(owner);
    }
  }

  @Test
  void preservesARedisCooldownEvenWhenPostgresAllowsTheClaim() {
    URI target = target();
    String key = redisKey(target);
    try (var coordinator = coordinator();
        var redis = new Jedis(redisUri())) {
      redis.hset(key, "cooldown", "1");
      redis.pexpire(key, 30_000);

      assertThat(
              assertThrows(SiteAccessException.class, () -> coordinator.acquire(target, work(WORK)))
                  .kind())
          .isEqualTo(REQUEST_COORDINATION_FAILED);
      assertThat(redis.hget(key, "cooldown")).isEqualTo("1");
      assertThat(redis.hget(key, "owner")).isNull();
      assertThat(redis.pttl(key)).isPositive();
    }
  }

  @Test
  void drainsACompletionCallbackThatArrivesAfterShutdownBegins() throws Exception {
    URI target = target();
    try (var first = coordinator();
        var second = coordinator();
        var executor = Executors.newSingleThreadExecutor()) {
      Future<?> shutdown;
      try (var active = first.acquire(target, work(WORK))) {
        first.close();
        shutdown = executor.submit(first::shutdown);
        assertThat(assertThrows(SiteAccessException.class, active::check).kind())
            .isEqualTo(REQUEST_COORDINATION_FAILED);
        assertThat(
                assertThrows(
                        SiteAccessException.class,
                        () -> second.acquire(target, work(Duration.ofMillis(200))))
                    .kind())
            .isEqualTo(WORK_LIMIT);
        assertThat(shutdown.isDone()).isFalse();
      }
      shutdown.get(2, TimeUnit.SECONDS);
      try (var next = second.acquire(target, work(WORK))) {
        next.check();
      }
    }
  }

  @Test
  void usesTheWorkDeadlineWhileRedisIsPausedAndCleansUpTheLateClaim() throws Exception {
    URI target = target();
    try (var first = coordinator();
        var second = coordinator();
        var redis = new Jedis(redisUri());
        var warmup = first.acquire(target(), work(WORK))) {
      redis.clientPause(1_500);
      try {
        long started = System.nanoTime();
        var failure =
            assertThrows(
                SiteAccessException.class,
                () -> first.acquire(target, work(Duration.ofMillis(75))));
        assertThat(failure.kind()).isEqualTo(WORK_LIMIT);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .isLessThan(Duration.ofMillis(240));
      } finally {
        redis.clientUnpause();
      }
      try (var next = second.acquire(target, work(WORK))) {
        next.check();
      }
    }
  }

  @Test
  void usesTheShorterOperationDeadlineWhileRenewingALease() throws Exception {
    try (var coordinator = coordinator();
        var redis = new Jedis(redisUri());
        var active = coordinator.acquire(target(), work(WORK))) {
      Thread.sleep(Duration.ofMillis(300));
      redis.clientPause(1_500);
      try {
        var pageBudget =
            work(WORK).cap(Duration.ofMillis(75), SiteAccessException.Kind.BROWSER_TIMEOUT);
        assertThat(assertThrows(SiteAccessException.class, () -> active.check(pageBudget)).kind())
            .isEqualTo(SiteAccessException.Kind.BROWSER_TIMEOUT);
      } finally {
        redis.clientUnpause();
      }
    }
  }

  private static URI redisUri() {
    return URI.create(System.getenv("WEAVO_SITE_TEST_REDIS_URL"));
  }

  private static RedisHostRequestCoordinator coordinator() {
    return SiteFixture.redisCoordinator();
  }

  private static String redisKey(URI target) {
    return "weavo:site-access:host:" + target.getHost();
  }

  private static URI target() {
    return URI.create("http://" + UUID.randomUUID() + ".example/start");
  }
}
