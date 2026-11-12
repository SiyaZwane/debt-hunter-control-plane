package com.debthunter.controlplane.egress.digest;

import java.net.URI;

/** Delivers a digest to one chat product. */
public interface DigestChannel {

  /**
   * Posts a digest.
   *
   * @param webhook the incoming-webhook address (a secret)
   * @param message the digest
   * @throws ChannelUnavailableException if the channel could not take it now
   * @throws ChannelRejectedException if the channel refused it
   */
  void send(URI webhook, DigestMessage message);
}
