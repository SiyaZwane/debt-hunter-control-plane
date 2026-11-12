package com.debthunter.controlplane.egress.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;

/**
 * Posts a digest to a Teams incoming webhook as a plain text message. Exercised against a local
 * imitation, not Teams; a Workflows-style webhook may want an Adaptive Card instead.
 */
public final class TeamsNotificationAdapter implements DigestChannel {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final WebhookHttp http;

  /**
   * Creates the adapter.
   *
   * @param timeout how long to wait for the webhook
   */
  public TeamsNotificationAdapter(Duration timeout) {
    this.http = new WebhookHttp(timeout);
  }

  @Override
  public void send(URI webhook, DigestMessage message) {
    ObjectNode body = JSON.createObjectNode();
    body.put("text", DigestText.render(message, DigestText.Style.TEAMS));
    http.post(webhook, body.toString());
  }
}
