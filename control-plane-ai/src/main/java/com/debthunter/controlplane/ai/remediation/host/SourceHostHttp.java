package com.debthunter.controlplane.ai.remediation.host;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The HTTP plumbing the source-host adapters share: one request, no redirects (a redirect could
 * send a credential to another host), a timeout, and failures sorted into "unreachable or asked to
 * wait" and "answered". A credential is only ever put in a header and never in a message.
 */
final class SourceHostHttp {

  /** A response. */
  record Reply(int status, String body, Map<String, String> headers) {}

  private final HttpClient client;
  private final Duration timeout;

  SourceHostHttp(Duration timeout) {
    this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout)
            .build(),
        timeout);
  }

  SourceHostHttp(HttpClient client, Duration timeout) {
    this.client = client;
    this.timeout = timeout;
  }

  /**
   * Sends a request.
   *
   * @return the reply, for any status the host answered with other than "unavailable" ones
   * @throws SourceHostUnavailableException if the host could not be reached, timed out, returned a
   *     server error, or asked us to slow down
   */
  Reply send(String method, URI uri, Map<String, String> headers, String jsonBody) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .method(
                method,
                jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
    headers.forEach(request::header);
    if (jsonBody != null) {
      request.header("Content-Type", "application/json");
    }
    HttpResponse<String> response;
    try {
      response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new SourceHostUnavailableException(
          "The source host could not be reached (" + e.getClass().getSimpleName() + ")", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SourceHostUnavailableException("Interrupted while calling the source host", e);
    }
    int status = response.statusCode();
    Map<String, String> replyHeaders = new java.util.HashMap<>();
    response
        .headers()
        .map()
        .forEach((k, v) -> replyHeaders.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
    boolean rateLimited = status == 403 && "0".equals(replyHeaders.get("x-ratelimit-remaining"));
    if (status == 429 || status >= 500 || rateLimited) {
      throw new SourceHostUnavailableException(
          "The source host is unavailable or rate-limiting (HTTP " + status + ")", null);
    }
    return new Reply(status, response.body(), replyHeaders);
  }

  /** Refuses a reply that is not a success. */
  static SourceHostRejectedException rejected(String what, Reply reply) {
    return new SourceHostRejectedException(
        what + " was refused by the source host (HTTP " + reply.status() + ")");
  }
}
