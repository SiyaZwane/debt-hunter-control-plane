package com.debthunter.controlplane.governance.publication;

import java.time.Instant;

/**
 * A request to publish a policy bundle.
 *
 * @param bundleYaml the bundle text, exactly as it will be distributed
 * @param ratifiedBy who ratified it
 * @param effectiveFrom when it takes effect
 */
public record PublishRequest(String bundleYaml, String ratifiedBy, Instant effectiveFrom) {

  /** Validates the components. */
  public PublishRequest {
    if (bundleYaml == null) {
      throw new IllegalArgumentException("A publish request needs the bundle text");
    }
    if (ratifiedBy == null || ratifiedBy.isBlank()) {
      throw new IllegalArgumentException("A bundle cannot be published without a ratifier");
    }
    if (effectiveFrom == null) {
      throw new IllegalArgumentException("A bundle cannot be published without an effective date");
    }
  }
}
