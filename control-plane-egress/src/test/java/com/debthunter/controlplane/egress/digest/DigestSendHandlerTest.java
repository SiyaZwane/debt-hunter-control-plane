package com.debthunter.controlplane.egress.digest;

import static com.debthunter.controlplane.egress.digest.DigestHarness.TEAM_A;
import static com.debthunter.controlplane.egress.digest.DigestHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;
import com.debthunter.controlplane.egress.outbox.OutboxMessage;
import com.debthunter.controlplane.egress.outbox.UndeliverableException;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.debthunter.domain.Severity;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DigestSendHandlerTest {

  private DigestHarness h;
  private Instant period;

  @BeforeEach
  void setUp() {
    h = InMemoryDigestParts.harness();
    h.start();
    h.newFinding(WIDGETS, "fp-1", Severity.HIGH, "src/A.java", "x");
    h.nextPeriod();
    h.service.tick();
    period =
        Instant.ofEpochSecond(
            Math.floorDiv(h.clock.instant().getEpochSecond(), 86_400L) * 86_400L - 86_400L);
  }

  private OutboxMessage job() {
    return new OutboxMessage(1, TEAM_A, new DigestJob(TEAM_A, period.toString()).encode(), 1);
  }

  @Test
  void aSquadWithNoChannelHoldsTheDigest() {
    assertThatThrownBy(() -> h.handler.deliver(job()))
        .isInstanceOf(DeliveryUnavailableException.class)
        .hasMessageContaining("no channel");
    assertThat(h.slack.posted).isEmpty();
  }

  @Test
  void aChannelWhoseSecretIsMissingHoldsTheDigest() {
    h.slackFor(TEAM_A);
    h.secrets.clear();

    assertThatThrownBy(() -> h.handler.deliver(job()))
        .isInstanceOf(DeliveryUnavailableException.class)
        .hasMessageContaining("no webhook");
  }

  @Test
  void aSecretStoreThatFailsHoldsTheDigestWithoutLeakingWhy() {
    h.slackFor(TEAM_A);
    DigestSendHandler broken =
        new DigestSendHandler(
            h.parts.ledger(),
            h.parts.channels(),
            ref -> {
              throw new IllegalStateException("vault down: secret-detail");
            },
            Map.of(SquadChannel.Kind.SLACK, h.slack),
            h.clock);

    assertThatThrownBy(() -> broken.deliver(job()))
        .isInstanceOf(DeliveryUnavailableException.class)
        .hasMessageNotContaining("secret-detail");
  }

  @Test
  void aChannelKindWithNoAdapterHoldsTheDigest() {
    h.slackFor(TEAM_A);
    DigestSendHandler none =
        new DigestSendHandler(
            h.parts.ledger(),
            h.parts.channels(),
            ref -> Optional.of(URI.create("https://x/")),
            Map.of(),
            h.clock);

    assertThatThrownBy(() -> none.deliver(job())).isInstanceOf(DeliveryUnavailableException.class);
  }

  @Test
  void aDigestAlreadySentIsNotSentAgain() {
    h.slackFor(TEAM_A);
    h.handler.deliver(job());
    h.handler.deliver(job());

    assertThat(h.slack.posted).hasSize(1);
  }

  @Test
  void aMessageThatIsNotADigestJobCanNeverBeDelivered() {
    assertThatThrownBy(
            () ->
                h.handler.deliver(
                    new OutboxMessage(1, "k", "nope".getBytes(StandardCharsets.UTF_8), 1)))
        .isInstanceOf(UndeliverableException.class);
  }

  @Test
  void aJobWithNoBuiltDigestCanNeverBeDelivered() {
    OutboxMessage unknown =
        new OutboxMessage(1, "k", new DigestJob("nobody", "2020-01-01T00:00:00Z").encode(), 1);

    assertThatThrownBy(() -> h.handler.deliver(unknown)).isInstanceOf(UndeliverableException.class);
  }

  @Test
  void aStoredDigestThatCannotBeReadCanNeverBeDelivered() {
    Instant start = Instant.parse("2020-01-01T00:00:00Z");
    h.parts.ledger().record("broken", start, "{not json", h.clock.instant());

    assertThatThrownBy(
            () ->
                h.handler.deliver(
                    new OutboxMessage(
                        1, "k", new DigestJob("broken", start.toString()).encode(), 1)))
        .isInstanceOf(UndeliverableException.class);
  }

  @Test
  void anOutageOfTheSystemOfRecordHoldsTheDigest() {
    DigestLedger down = new DownLedger();
    DigestSendHandler handler =
        new DigestSendHandler(down, h.parts.channels(), ref -> Optional.empty(), Map.of(), h.clock);

    assertThatThrownBy(() -> handler.deliver(job()))
        .isInstanceOf(DeliveryUnavailableException.class)
        .hasCauseInstanceOf(StoreException.class);
  }

  @Test
  void aJobRoundTripsThroughItsBytes() {
    DigestJob j = new DigestJob("team-a", "2026-07-01T00:00:00Z");

    assertThat(DigestJob.decode(j.encode())).isEqualTo(j);
    assertThat(j.idempotencyKey()).isEqualTo("team-a|2026-07-01T00:00:00Z");
    assertThatThrownBy(
            () ->
                DigestJob.decode(
                    "{\"owner\":\"a\",\"periodStart\":\"x\"}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** A ledger whose store is down. */
  private static final class DownLedger implements DigestLedger {
    private static StoreException down() {
      return new StoreException("down", null);
    }

    @Override
    public Window window(Instant start, Instant end, long latestSeq) {
      throw down();
    }

    @Override
    public boolean record(String owner, Instant periodStart, String payload, Instant now) {
      throw down();
    }

    @Override
    public Optional<Entry> find(String owner, Instant periodStart) {
      throw down();
    }

    @Override
    public java.util.List<Entry> unqueued() {
      throw down();
    }

    @Override
    public void markQueued(String owner, Instant periodStart, Instant now) {
      throw down();
    }

    @Override
    public void markSent(String owner, Instant periodStart, Instant now) {
      throw down();
    }
  }
}
