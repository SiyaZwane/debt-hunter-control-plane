package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;
import com.debthunter.controlplane.egress.outbox.OutboxHandler;
import com.debthunter.controlplane.egress.outbox.OutboxMessage;
import com.debthunter.controlplane.egress.outbox.UndeliverableException;
import com.debthunter.controlplane.store.jdbc.StoreException;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers a queued digest: reads it from the ledger as it was built, finds the squad's channel and
 * webhook, posts it, and notes that it was sent. A digest already sent is left alone, so a message
 * handed out twice does not post twice. No channel configured, a secret that cannot be found or
 * read, or a channel that is down all hold the digest; none is the digest's fault.
 */
public final class DigestSendHandler implements OutboxHandler {

  private static final Logger LOG = LoggerFactory.getLogger(DigestSendHandler.class);

  private final DigestLedger ledger;
  private final SquadChannelRepository channels;
  private final WebhookSecrets secrets;
  private final Map<SquadChannel.Kind, DigestChannel> adapters;
  private final Clock clock;

  /**
   * Creates the handler.
   *
   * @param ledger the built digests
   * @param channels where each squad is told
   * @param secrets resolves a channel's secret reference
   * @param adapters one adapter per chat product
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public DigestSendHandler(
      DigestLedger ledger,
      SquadChannelRepository channels,
      WebhookSecrets secrets,
      Map<SquadChannel.Kind, DigestChannel> adapters,
      Clock clock) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.channels = Objects.requireNonNull(channels, "channels");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.adapters = Map.copyOf(adapters);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void deliver(OutboxMessage message) {
    DigestJob job;
    try {
      job = DigestJob.decode(message.payload());
    } catch (IllegalArgumentException e) {
      throw new UndeliverableException("the message is not a digest job");
    }
    try {
      send(job);
    } catch (StoreException e) {
      throw new DeliveryUnavailableException("the system of record is unavailable", e);
    }
  }

  private void send(DigestJob job) {
    DigestLedger.Entry entry =
        ledger
            .find(job.owner(), job.start())
            .orElseThrow(() -> new UndeliverableException("no digest was built for this job"));
    if (entry.sentAt() != null || entry.payload() == null) {
      return;
    }
    DigestMessage digest;
    try {
      digest = DigestNotificationService.read(entry.payload());
    } catch (IllegalArgumentException e) {
      throw new UndeliverableException("the stored digest cannot be read");
    }
    SquadChannel channel =
        channels
            .find(job.owner())
            .orElseThrow(
                () -> {
                  LOG.warn("squad {} has no channel; its digest is held", job.owner());
                  return new ChannelUnavailableException("the squad has no channel", null);
                });
    Optional<URI> webhook;
    try {
      webhook = secrets.resolve(channel.secretRef());
    } catch (RuntimeException e) {
      throw new ChannelUnavailableException("the webhook secret could not be read", null);
    }
    if (webhook.isEmpty()) {
      LOG.warn("no webhook is held for squad {}; its digest is held", job.owner());
      throw new ChannelUnavailableException("no webhook is held for the squad", null);
    }
    DigestChannel adapter = adapters.get(channel.kind());
    if (adapter == null) {
      throw new ChannelUnavailableException("no adapter for " + channel.kind(), null);
    }
    adapter.send(webhook.get(), digest);
    ledger.markSent(job.owner(), job.start(), clock.instant());
  }
}
