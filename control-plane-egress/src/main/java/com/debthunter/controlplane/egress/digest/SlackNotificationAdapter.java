package com.debthunter.controlplane.egress.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;

/** Posts a digest to a Slack incoming webhook. Exercised against a local imitation, not Slack. */
public final class SlackNotificationAdapter implements DigestChannel {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final WebhookHttp http;

  /**
   * Creates the adapter.
   *
   * @param timeout how long to wait for the webhook
   */
  public SlackNotificationAdapter(Duration timeout) {
    this.http = new WebhookHttp(timeout);
  }

  @Override
  public void send(URI webhook, DigestMessage message) {
    ObjectNode body = JSON.createObjectNode();
    body.put("text", DigestText.render(message, DigestText.Style.SLACK));
    body.put("mrkdwn", true);
    http.post(webhook, body.toString());
  }
}
