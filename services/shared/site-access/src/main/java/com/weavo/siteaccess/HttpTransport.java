package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.CANCELLED;
import static com.weavo.siteaccess.SiteAccessException.Kind.HTTP_TIMEOUT;
import static com.weavo.siteaccess.SiteAccessException.Kind.INVALID_URL;
import static com.weavo.siteaccess.SiteAccessException.Kind.RESPONSE_TOO_LARGE;
import static com.weavo.siteaccess.SiteAccessException.Kind.SITE_UNAVAILABLE;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;

final class HttpTransport {
  static final int HTML_LIMIT = 10 * 1024 * 1024;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
  private final OkHttpClient client;
  private final RequestCoordinator coordinator;

  HttpTransport(RequestCoordinator coordinator, Dns dns) {
    this.coordinator = coordinator;
    this.client =
        new OkHttpClient.Builder()
            .dns(dns)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(REQUEST_TIMEOUT)
            .readTimeout(REQUEST_TIMEOUT)
            .writeTimeout(REQUEST_TIMEOUT)
            .addInterceptor(chain -> {
              var control = chain.request().tag(RequestControl.class);
              try {
                control.budget().check();
                control.permit().check(control.budget());
              } catch (SiteAccessException failure) {
                throw new RequestStopped(failure);
              }
              return chain.proceed(chain.request());
            })
            .addNetworkInterceptor(chain -> {
              Response response = chain.proceed(chain.request());
              // OkHttp otherwise repeats a 503 with Retry-After: 0 inside the same call.
              return response.code() == 503
                  ? response.newBuilder().removeHeader("Retry-After").build()
                  : response;
            })
            .protocols(List.of(Protocol.HTTP_1_1))
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build();
  }

  Reply send(Request request, WorkBudget budget, int maxBytes) {
    budget.beforeRequest();
    var permit = coordinator.acquire(request.url().uri(), budget);
    boolean deferredRelease = false;
    try {
      budget.beforeRequest();
      WorkBudget requestBudget = budget.cap(REQUEST_TIMEOUT, HTTP_TIMEOUT);
      permit.check(requestBudget);
      Call call =
          client.newCall(
              request
                  .newBuilder()
                  .tag(RequestControl.class, new RequestControl(requestBudget, permit))
                  .build());
      call.timeout().timeout(requestBudget.timeoutMillis(), TimeUnit.MILLISECONDS);
      var pending = new CompletableFuture<Reply>();
      call.enqueue(
          new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
              pending.completeExceptionally(error);
            }

            @Override
            public void onResponse(Call call, Response response) {
              Reply reply;
              try (response) {
                reply = read(response, maxBytes);
              } catch (IOException | SiteAccessException error) {
                pending.completeExceptionally(error);
                return;
              }
              pending.complete(reply);
            }
          });
      try {
        return await(pending, requestBudget, permit);
      } finally {
        if (!pending.isDone()) {
          deferredRelease = true;
          try {
            call.cancel();
          } finally {
            pending.whenComplete((reply, failure) -> permit.close());
          }
        }
      }
    } finally {
      if (!deferredRelease) {
        permit.close();
      }
    }
  }

  private static Reply await(
      CompletableFuture<Reply> pending, WorkBudget budget, RequestCoordinator.Permit permit) {
    while (true) {
      permit.check(budget);
      try {
        Reply reply = pending.get(Math.min(50, budget.timeoutMillis()), TimeUnit.MILLISECONDS);
        budget.check();
        permit.check(budget);
        return reply;
      } catch (TimeoutException ignored) {
        // Cancellation and ownership are checked even while headers or the body are pending.
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new SiteAccessException(CANCELLED, "Site request was interrupted", error);
      } catch (ExecutionException error) {
        budget.check();
        throw transportFailure(error.getCause());
      }
    }
  }

  private static Reply read(Response response, int maxBytes) throws IOException {
    int status = response.code();
    var body = response.body();
    if (body == null || isImmediateBlock(status, response.headers()) || isRedirect(status)) {
      return new Reply(response.request().url(), status, response.headers(), new byte[0]);
    }
    if (body.contentLength() > maxBytes) {
      throw tooLarge();
    }
    byte[] bytes = body.byteStream().readNBytes(maxBytes + 1);
    if (bytes.length > maxBytes) {
      throw tooLarge();
    }
    return new Reply(response.request().url(), status, response.headers(), bytes);
  }

  private static SiteAccessException transportFailure(Throwable error) {
    if (error instanceof SiteAccessException failure) {
      return failure;
    }
    if (error instanceof RequestStopped stopped) {
      return (SiteAccessException) stopped.getCause();
    }
    if (error instanceof PublicUrls.UnsafeHost) {
      return new SiteAccessException(
          INVALID_URL, "Site host resolved to a non-public address", error);
    }
    if (error instanceof InterruptedIOException) {
      return new SiteAccessException(HTTP_TIMEOUT, "Site request timed out", error);
    }
    return new SiteAccessException(SITE_UNAVAILABLE, "Site request could not be completed", error);
  }

  static boolean isImmediateBlock(int status, Headers headers) {
    return status == 401
        || status == 403
        || "challenge".equalsIgnoreCase(headers.get("cf-mitigated"));
  }

  static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  private static SiteAccessException tooLarge() {
    return new SiteAccessException(RESPONSE_TOO_LARGE, "Response exceeded the byte limit");
  }

  private record RequestControl(WorkBudget budget, RequestCoordinator.Permit permit) {}

  private static final class RequestStopped extends IOException {
    RequestStopped(SiteAccessException failure) {
      super(failure);
    }
  }

  record Reply(HttpUrl url, int status, Headers headers, byte[] body) {
    String html() {
      Charset charset = charset();
      return new String(body, charset);
    }

    private Charset charset() {
      String contentType = headers.get("Content-Type");
      MediaType media = contentType == null ? null : MediaType.parse(contentType);
      Charset declared = media == null ? null : media.charset();
      try (var input = new ByteArrayInputStream(body)) {
        return Jsoup.parse(input, declared == null ? null : declared.name(), "").charset();
      } catch (IOException error) {
        throw new SiteAccessException(SITE_UNAVAILABLE, "Could not read the HTML encoding", error);
      }
    }
  }
}
