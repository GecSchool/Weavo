package com.weavo.analysis.source.infrastructure.http;

import com.weavo.siteaccess.WorkBudget;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class AnalysisHttpClient {
  private final HttpClient http;

  AnalysisHttpClient(HttpClient http) {
    this.http = http;
  }

  HttpResponse<String> send(HttpRequest request, WorkBudget budget) {
    budget.beforeRequest();
    var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    try {
      while (true) {
        Duration remaining = budget.remaining();
        long wait = Math.max(1, Math.min(remaining.toMillis(), 100));
        try {
          return pending.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
          // Observe the worker deadline and lease before waiting for the response again.
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Analysis HTTP call interrupted");
    } catch (ExecutionException error) {
      throw new Unavailable();
    } finally {
      if (!pending.isDone()) {
        pending.cancel(true);
      }
    }
  }

  static final class Unavailable extends RuntimeException {
    Unavailable() {
      super("Analysis HTTP call did not complete");
    }
  }
}
