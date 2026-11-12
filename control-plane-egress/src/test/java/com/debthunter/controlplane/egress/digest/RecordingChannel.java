package com.debthunter.controlplane.egress.digest;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A channel that records what it was sent, and can be told to be down or to refuse. */
public final class RecordingChannel implements DigestChannel {

  /** A digest posted. */
  public record Posted(URI webhook, DigestMessage message) {}

  public final List<Posted> posted = new CopyOnWriteArrayList<>();
  public volatile boolean down;
  public volatile boolean refuse;

  @Override
  public void send(URI webhook, DigestMessage message) {
    if (down) {
      throw new ChannelUnavailableException("the channel is down", null);
    }
    if (refuse) {
      throw new ChannelRejectedException("the channel refused the digest (HTTP 404)");
    }
    posted.add(new Posted(webhook, message));
  }
}
