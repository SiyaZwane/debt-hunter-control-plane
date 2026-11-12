package com.debthunter.controlplane.egress.digest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Posts JSON to an incoming webhook: no redirects (a redirect could send the message elsewhere), a
 * timeout, and failures sorted into "could not take it now" and "refused". The address is a secret,
 * so it is never put in a message.
 */
final class WebhookHttp {

  private final HttpClient client;
  private final Duration timeout;

  WebhookHttp(Duration timeout) {
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout)
            .build();
    this.timeout = timeout;
  }

  void post(URI webhook, String json) {
    HttpRequest request =
        HttpRequest.newBuilder(webhook)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new ChannelUnavailableException(
          "The channel could not be reached (" + e.getClass().getSimpleName() + ")", null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChannelUnavailableException("Interrupted while calling the channel", null);
    }
    int status = response.statusCode();
    if (status == 408 || status == 429 || status >= 500) {
      throw new ChannelUnavailableException(
          "The channel is unavailable or rate-limiting (HTTP " + status + ")", null);
    }
    if (status < 200 || status >= 300) {
      throw new ChannelRejectedException("The channel refused the digest (HTTP " + status + ")");
    }
  }
}
