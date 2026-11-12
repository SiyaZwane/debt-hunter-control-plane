package com.debthunter.controlplane.egress.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelAdaptersTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private FakeWebhookServer server;
  private final SlackNotificationAdapter slack =
      new SlackNotificationAdapter(Duration.ofSeconds(5));
  private final TeamsNotificationAdapter teams =
      new TeamsNotificationAdapter(Duration.ofSeconds(5));

  @BeforeEach
  void setUp() {
    server = new FakeWebhookServer();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private static DigestMessage digest(String message) {
    return new DigestMessage(
        "team-a",
        "2026-07-01T00:00:00Z",
        "2026-07-02T00:00:00Z",
        Map.of("HIGH", 1),
        0,
        0,
        List.of(),
        Map.of(),
        List.of(),
        0,
        List.of(new DigestMessage.Notable("HIGH", "rule", "src/A.java", message, false)),
        0,
        1,
        null);
  }

  @Test
  void slackGetsOneJsonPostWithMrkdwnText() throws IOException {
    slack.send(server.webhook(), digest("a hotspot"));

    assertThat(server.requests).hasSize(1);
    FakeWebhookServer.Request r = server.requests.get(0);
    assertThat(r.method()).isEqualTo("POST");
    assertThat(r.contentType()).isEqualTo("application/json");
    JsonNode body = JSON.readTree(r.body());
    assertThat(body.path("mrkdwn").asBoolean()).isTrue();
    assertThat(body.path("text").asText()).contains("Debt Hunter digest", "a hotspot");
  }

  @Test
  void teamsGetsOneJsonPostWithMarkdownText() throws IOException {
    teams.send(server.webhook(), digest("a hotspot"));

    JsonNode body = JSON.readTree(server.requests.get(0).body());
    assertThat(body.path("text").asText()).contains("**Debt Hunter digest", "a hotspot");
    assertThat(body.has("mrkdwn")).isFalse();
  }

  @Test
  void aMessageWithMarkupIsEscapedInsideThePostedBody() throws IOException {
    slack.send(server.webhook(), digest("<!channel> look"));

    assertThat(JSON.readTree(server.requests.get(0).body()).path("text").asText())
        .doesNotContain("<!channel>")
        .contains("&lt;!channel&gt;");
  }

  @Test
  void anOutageARateLimitAndATimeoutAreUnavailableNotRefusals() {
    for (int status : new int[] {429, 500, 503, 408}) {
      server.forcedStatus = status;
      assertThatThrownBy(() -> slack.send(server.webhook(), digest("x")))
          .as("HTTP %d", status)
          .isInstanceOf(ChannelUnavailableException.class);
      assertThatThrownBy(() -> teams.send(server.webhook(), digest("x")))
          .isInstanceOf(ChannelUnavailableException.class);
    }
  }

  @Test
  void aRevokedOrBadWebhookIsARefusal() {
    for (int status : new int[] {400, 401, 403, 404, 410}) {
      server.forcedStatus = status;
      assertThatThrownBy(() -> slack.send(server.webhook(), digest("x")))
          .as("HTTP %d", status)
          .isInstanceOf(ChannelRejectedException.class);
    }
  }

  @Test
  void aRedirectIsNotFollowedSoTheDigestGoesNowhereElse() {
    server.redirectTo = "http://127.0.0.1:1/elsewhere";

    assertThatThrownBy(() -> slack.send(server.webhook(), digest("x")))
        .isInstanceOf(ChannelRejectedException.class);
    assertThat(server.requests).hasSize(1);
  }

  @Test
  void aChannelThatCannotBeReachedIsUnavailableAndTheErrorDoesNotRevealTheAddress() {
    java.net.URI gone = server.webhook();
    server.close();

    assertThatThrownBy(() -> teams.send(gone, digest("x")))
        .isInstanceOf(ChannelUnavailableException.class)
        .hasMessageNotContaining("secret")
        .hasMessageNotContaining("hooks");
    server = new FakeWebhookServer();
  }

  @Test
  void aRefusalMessageDoesNotRevealTheAddress() {
    server.forcedStatus = 404;

    assertThatThrownBy(() -> slack.send(server.webhook(), digest("x")))
        .hasMessageNotContaining("secret")
        .hasMessageContaining("404");
  }
}
