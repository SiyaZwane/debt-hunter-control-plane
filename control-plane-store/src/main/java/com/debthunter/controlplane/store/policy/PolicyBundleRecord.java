package com.debthunter.controlplane.store.policy;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * A policy bundle version in the catalogue.
 *
 * @param version the bundle's version
 * @param digest the bundle's content digest, {@code sha256:<64 hex>}
 * @param ratifiedBy who ratified it, or {@code null} while unratified
 * @param ratifiedAt when, or {@code null}
 * @param effectiveFrom when it takes effect, or {@code null} while not scheduled
 * @param supersededBy the version that replaced it, or {@code null}
 */
public record PolicyBundleRecord(
    String version,
    String digest,
    String ratifiedBy,
    Instant ratifiedAt,
    Instant effectiveFrom,
    String supersededBy) {

  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  /** Validates the components. */
  public PolicyBundleRecord {
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("A policy bundle needs a version");
    }
    if (digest == null || !DIGEST.matcher(digest).matches()) {
      throw new IllegalArgumentException("A policy bundle digest must be sha256:<64 hex>");
    }
    if ((ratifiedBy == null) != (ratifiedAt == null)) {
      throw new IllegalArgumentException("Ratification needs both who and when");
    }
  }

  /** Whether the bundle has been ratified. */
  public boolean isRatified() {
    return ratifiedBy != null;
  }

  /** A copy with a ratification recorded. */
  public PolicyBundleRecord ratified(String approver, Instant at) {
    return new PolicyBundleRecord(version, digest, approver, at, effectiveFrom, supersededBy);
  }

  /** A copy scheduled to take effect. */
  public PolicyBundleRecord effectiveFrom(Instant from) {
    return new PolicyBundleRecord(version, digest, ratifiedBy, ratifiedAt, from, supersededBy);
  }

  /** A copy marked as replaced. */
  public PolicyBundleRecord supersededBy(String newer) {
    return new PolicyBundleRecord(version, digest, ratifiedBy, ratifiedAt, effectiveFrom, newer);
  }
}
