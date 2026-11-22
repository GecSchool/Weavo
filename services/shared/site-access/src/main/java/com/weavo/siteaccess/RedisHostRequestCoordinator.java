package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.REQUEST_COORDINATION_FAILED;

import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;

/** Shared per-host permission for HTTP and Chromium requests. */
public final class RedisHostRequestCoordinator implements RequestCoordinator, AutoCloseable {
  private static final String KEY_PREFIX = "weavo:site-access:host:";
  static final Duration LEASE = Duration.ofSeconds(2);
  private static final Duration CHECK_INTERVAL = Duration.ofMillis(250);
  private static final Duration WAIT_INTERVAL = Duration.ofMillis(100);
  private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(15);
  private static final String COOLDOWN_MILLIS = "1000";
  private static final String REDIS_TIME =
      """
      local time = redis.call('TIME')
      local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
      """;
  // Redis still verifies live leases. The durable host record survives loss of this key.
  private static final String ACQUIRE =
      REDIS_TIME
          + """
          if redis.call('EXISTS', KEYS[1]) == 1 then
            if redis.call('HEXISTS', KEYS[1], 'cooldown') == 1 then return 0 end
            local owner = redis.call('HGET', KEYS[1], 'owner')
            local expires = tonumber(redis.call('HGET', KEYS[1], 'until'))
            if not (owner and expires and expires <= now) then return 0 end
            redis.call('DEL', KEYS[1])
          end
          redis.call('HSET', KEYS[1], 'owner', ARGV[1], 'until', now + tonumber(ARGV[2]))
          return 1
          """;
  private static final String RENEW =
      REDIS_TIME
          + """
          if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end
          local expires = tonumber(redis.call('HGET', KEYS[1], 'until'))
          if not expires or expires <= now then return 0 end
          redis.call('HSET', KEYS[1], 'until', now + tonumber(ARGV[2]))
          return 1
          """;
  private static final String RELEASE =
      """
      if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end
      redis.call('HSET', KEYS[1], 'cooldown', 1)
      redis.call('HDEL', KEYS[1], 'owner', 'until')
      redis.call('PEXPIRE', KEYS[1], ARGV[2])
      return 1
      """;

  private final JedisPooled redis;
  private final JdbcHostRequestStore store;
  private final ExecutorService commands = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService releaseRetry;
  private final Set<Owner> pendingReleases = ConcurrentHashMap.newKeySet();
  private final Object lifecycle = new Object();
  private int outstanding;
  private volatile boolean closing;

  public RedisHostRequestCoordinator(URI redisUri, String dbUrl, String dbUser, String dbPassword) {
    var pool = new GenericObjectPoolConfig<Connection>();
    pool.setBlockWhenExhausted(false);
    redis = new JedisPooled(pool, redisUri, 250);
    store = new JdbcHostRequestStore(dbUrl, dbUser, dbPassword);
    releaseRetry =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("weavo-redis-host-release").factory());
    releaseRetry.scheduleWithFixedDelay(this::retryReleases, 100, 100, TimeUnit.MILLISECONDS);
  }

  @Override
  public Permit acquire(URI target, WorkBudget budget) {
    String host = PublicUrls.parse(target.toString()).host().replaceFirst("\\.$", "");
    while (true) {
      budget.check();
      admit();
      var owner = new Owner(host, UUID.randomUUID());
      var acquisition = CompletableFuture.supplyAsync(() -> claim(owner, budget), commands);
      boolean handedOff = false;
      try {
        Claim claim = await(acquisition, budget);
        if (claim != null) {
          var lease = new Lease(owner, claim.checkedAt(), budget);
          lease.check(budget);
          handedOff = true;
          return lease;
        }
      } finally {
        if (!handedOff) {
          // If the wait timed out, release only after the acquisition command is finished.
          acquisition.whenComplete((claim, failure) -> {
            if (claim != null || failure != null) {
              pendingReleases.add(owner);
            } else {
              retire();
            }
          });
        }
      }
      budget.pause(WAIT_INTERVAL);
    }
  }

  private Claim claim(Owner owner, WorkBudget budget) {
    try {
      budget.check();
      ensureOpen();
      if (!store.acquire(owner.host(), owner.token())) {
        budget.check();
        redis.ping();
        return null;
      }
      budget.check();
      ensureOpen();
      long checkedAt = System.nanoTime();
      // Only the new PostgreSQL owner can attempt to replace an expired Redis lease.
      if (!eval(ACQUIRE, owner, Long.toString(LEASE.toMillis()))) {
        throw unavailable(null);
      }
      return new Claim(checkedAt);
    } catch (SQLException | JedisException failure) {
      throw unavailable(failure);
    }
  }

  private <T> T await(CompletableFuture<T> command, WorkBudget budget) {
    while (true) {
      budget.check();
      ensureOpen();
      try {
        T result = command.get(Math.min(50, budget.timeoutMillis()), TimeUnit.MILLISECONDS);
        budget.check();
        ensureOpen();
        return result;
      } catch (TimeoutException ignored) {
        // The caller can reach its work deadline before a storage command responds.
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new SiteAccessException(
            SiteAccessException.Kind.CANCELLED, "Host request was cancelled", error);
      } catch (ExecutionException error) {
        budget.check();
        if (error.getCause() instanceof SiteAccessException failure) {
          throw failure;
        }
        throw unavailable(error.getCause());
      }
    }
  }

  private boolean eval(String script, Owner owner, String argument) {
    return Long.valueOf(1)
        .equals(
            redis.eval(
                script,
                List.of(KEY_PREFIX + owner.host()),
                List.of(owner.token().toString(), argument)));
  }

  private void retryReleases() {
    for (Owner owner : pendingReleases) {
      try {
        eval(RELEASE, owner, COOLDOWN_MILLIS);
        store.release(owner.host(), owner.token());
        pendingReleases.remove(owner);
        retire();
      } catch (SQLException | JedisException ignored) {
        // Retain the durable owner until both stores can confirm the release.
      }
    }
  }

  private void ensureOpen() {
    if (closing) {
      throw new SiteAccessException(
          REQUEST_COORDINATION_FAILED, "Host request coordinator is closing");
    }
  }

  private static SiteAccessException unavailable(Throwable failure) {
    return new SiteAccessException(
        REQUEST_COORDINATION_FAILED, "Could not confirm the host request lease", failure);
  }

  private void admit() {
    synchronized (lifecycle) {
      ensureOpen();
      outstanding++;
    }
  }

  private void retire() {
    synchronized (lifecycle) {
      outstanding--;
      closeIfDrained();
      lifecycle.notifyAll();
    }
  }

  @Override
  public void close() {
    synchronized (lifecycle) {
      closing = true;
      closeIfDrained();
    }
  }

  /** Gives transport callbacks time to finish before the service process shuts down. */
  public void shutdown() {
    close();
    long deadline = System.nanoTime() + SHUTDOWN_GRACE.toNanos();
    synchronized (lifecycle) {
      long remaining;
      while (outstanding > 0 && (remaining = deadline - System.nanoTime()) > 0) {
        try {
          TimeUnit.NANOSECONDS.timedWait(lifecycle, remaining);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void closeIfDrained() {
    if (closing && outstanding == 0 && !releaseRetry.isShutdown()) {
      releaseRetry.shutdown();
      commands.shutdown();
      redis.close();
    }
  }

  private record Owner(String host, UUID token) {}

  private record Claim(long checkedAt) {}

  private record Renewal(long checkedAt, CompletableFuture<Boolean> result) {}

  private final class Lease implements Permit {
    private final Owner owner;
    private final WorkBudget defaultBudget;
    private long checkedAt;
    private Renewal renewal;
    private boolean lost;
    private boolean released;

    Lease(Owner owner, long checkedAt, WorkBudget defaultBudget) {
      this.owner = owner;
      this.checkedAt = checkedAt;
      this.defaultBudget = defaultBudget;
    }

    @Override
    public void check() {
      check(defaultBudget);
    }

    @Override
    public void check(WorkBudget budget) {
      budget.check();
      Renewal current;
      synchronized (this) {
        validate();
        long now = System.nanoTime();
        if (now - checkedAt < CHECK_INTERVAL.toNanos()) {
          return;
        }
        if (renewal == null) {
          renewal =
              new Renewal(
                  now,
                  CompletableFuture.supplyAsync(
                      () -> eval(RENEW, owner, Long.toString(LEASE.toMillis())), commands));
        }
        current = renewal;
      }
      try {
        boolean renewed = await(current.result(), budget);
        synchronized (this) {
          if (!renewed
              || lost
              || released
              || System.nanoTime() - current.checkedAt() >= LEASE.toNanos()) {
            throw expired();
          }
          if (renewal == current) {
            checkedAt = current.checkedAt();
            renewal = null;
          }
        }
      } catch (SiteAccessException failure) {
        synchronized (this) {
          lost = true;
        }
        throw failure;
      }
    }

    private void validate() {
      ensureOpen();
      if (lost || released || System.nanoTime() - checkedAt >= LEASE.toNanos()) {
        lost = true;
        throw expired();
      }
    }

    private SiteAccessException expired() {
      return new SiteAccessException(
          REQUEST_COORDINATION_FAILED, "Host request lease is no longer valid");
    }

    @Override
    public synchronized void close() {
      if (!released) {
        released = true;
        pendingReleases.add(owner);
      }
    }
  }
}
