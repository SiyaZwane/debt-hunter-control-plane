package com.debthunter.controlplane.egress.workitem;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The HTTP plumbing the tracker adapters share: one request, no redirects (a redirect could send a
 * credential to another host), a timeout, and failures sorted into "could not take the call now"
 * and "answered". A credential is only ever put in a header and never in a message.
 *
 * <p>Unreachable, timing out, a server error, a request to slow down, and a refusal of our
 * credentials are all "unavailable": none is the work item's fault, and a wrong credential should
 * hold work, not throw it away.
 */
final class TrackerHttp {

  /** A response. */
  record Reply(int status, String body) {

    boolean ok() {
      return status >= 200 && status < 300;
    }
  }

  private final HttpClient client;
  private final Duration timeout;
  private final Supplier<String> authorization;

  TrackerHttp(Duration timeout, Supplier<String> authorization) {
    this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout)
            .build(),
        timeout,
        authorization);
  }

  TrackerHttp(HttpClient client, Duration timeout, Supplier<String> authorization) {
    this.client = client;
    this.timeout = timeout;
    this.authorization = authorization;
  }

  /**
   * Sends a request.
   *
   * @throws TrackerUnavailableException if the tracker could not take the call now
   */
  Reply send(String method, URI uri, String contentType, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    String credential;
    try {
      credential = authorization.get();
    } catch (RuntimeException e) {
      throw new TrackerUnavailableException("The tracker credential could not be obtained", e);
    }
    request.header("Authorization", credential);
    if (body != null) {
      request.header("Content-Type", contentType);
    }
    HttpResponse<String> response;
    try {
      response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new TrackerUnavailableException(
          "The tracker could not be reached (" + e.getClass().getSimpleName() + ")", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TrackerUnavailableException("Interrupted while calling the tracker", e);
    }
    int status = response.statusCode();
    if (status == 401 || status == 403 || status == 408 || status == 429 || status >= 500) {
      throw new TrackerUnavailableException(
          "The tracker is unavailable, rate-limiting or refusing our credentials (HTTP "
              + status
              + ")",
          null);
    }
    return new Reply(status, response.body());
  }

  /** A refusal that is not a success. */
  static TrackerRejectedException rejected(String what, Reply reply) {
    return new TrackerRejectedException(
        what + " was refused by the tracker (HTTP " + reply.status() + ")");
  }

  /** Only addresses of the configured tracker are ever called. */
  static URI resolve(URI base, String path) {
    String root = base.toString();
    return URI.create((root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + path);
  }

  /** Query-safe encoding. */
  static String enc(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
