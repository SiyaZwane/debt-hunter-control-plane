package com.debthunter.controlplane.egress.digest;

import java.net.URI;
import java.util.Optional;

/** Turns a secret reference into the webhook address it names. */
@FunctionalInterface
public interface WebhookSecrets {

  /**
   * Resolves a reference.
   *
   * @param secretRef the reference held in a channel
   * @return the address, or empty if nothing is held under that name
   * @throws RuntimeException if the secret store could not be reached
   */
  Optional<URI> resolve(String secretRef);
}
