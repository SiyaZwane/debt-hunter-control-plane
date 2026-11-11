package com.debthunter.controlplane.analytics.coverage.estate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The small amount of HTTP the estate providers share. Timeouts are bounded, redirects are never
 * followed (a redirect could carry a credential to another host), and failures become {@link
 * EstateUnavailableException}s that name the situation but never a credential or a response body.
 */
final class EstateHttp {

  private final HttpClient client;
  private final Duration timeout;

  EstateHttp(Duration timeout) {
    this.timeout = timeout;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** A successful response. */
  record Reply(String body, Map<String, java.util.List<String>> headers) {}

  Reply get(URI uri, Map<String, String> headers, String system) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).GET();
    headers.forEach(request::header);
    HttpResponse<String> response;
    try {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new EstateUnavailableException(system + " could not be reached", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EstateUnavailableException(system + " request was interrupted", e);
    }
    int status = response.statusCode();
    if (status == 200) {
      return new Reply(response.body(), response.headers().map());
    }
    if (status == 401 || status == 403) {
      throw new EstateUnavailableException(
          system + " rejected the credentials or they lack access (HTTP " + status + ")");
    }
    if (status == 404) {
      throw new EstateUnavailableException(
          system + " does not know that organisation, or the credentials cannot see it (HTTP 404)");
    }
    throw new EstateUnavailableException(system + " answered HTTP " + status);
  }
}
