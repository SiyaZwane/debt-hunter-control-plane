package com.debthunter.controlplane.ai.explain;

import java.util.Objects;

/**
 * An explanation of a finding, with the provenance of its text.
 *
 * <p>It can only be created through {@link ProvenanceLabeller}, so no explanation exists without
 * saying where it came from.
 */
public final class Explanation {

  private final String fingerprint;
  private final String ruleId;
  private final String text;
  private final Provenance provenance;
  private final boolean sourceInformed;

  Explanation(
      String fingerprint,
      String ruleId,
      String text,
      Provenance provenance,
      boolean sourceInformed) {
    this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
    this.text = Objects.requireNonNull(text, "text");
    this.provenance = Objects.requireNonNull(provenance, "provenance");
    this.sourceInformed = sourceInformed;
  }

  /** The fingerprint of the finding it explains. */
  public String fingerprint() {
    return fingerprint;
  }

  /** The rule of the finding it explains. */
  public String ruleId() {
    return ruleId;
  }

  /** The explanation. */
  public String text() {
    return text;
  }

  /** Who or what produced it. */
  public Provenance provenance() {
    return provenance;
  }

  /** Whether a language model wrote it. */
  public boolean modelGenerated() {
    return provenance.modelGenerated();
  }

  /**
   * Whether it was produced from a prompt that contained source code. Such text may quote source,
   * so it is never shared between repositories.
   */
  public boolean sourceInformed() {
    return sourceInformed;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Explanation e)) {
      return false;
    }
    return sourceInformed == e.sourceInformed
        && fingerprint.equals(e.fingerprint)
        && ruleId.equals(e.ruleId)
        && text.equals(e.text)
        && provenance.equals(e.provenance);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fingerprint, ruleId, text, provenance, sourceInformed);
  }

  @Override
  public String toString() {
    return "Explanation[" + ruleId + ", " + provenance.source() + "]";
  }
}
