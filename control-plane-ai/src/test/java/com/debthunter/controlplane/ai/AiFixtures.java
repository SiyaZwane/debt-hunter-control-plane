package com.debthunter.controlplane.ai;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Findings and a clock for the AI tests. */
public final class AiFixtures {

  public static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");
  public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  public static final String CONTRIBUTOR = "c".repeat(64);

  private AiFixtures() {}

  public static PseudonymisedFinding hotspot() {
    return finding(
        "codemaat.hotspot:src/Foo.java",
        "codemaat.hotspot",
        Category.HOTSPOT,
        Severity.HIGH,
        "src/Foo.java",
        "src/Foo.java is a change hotspot (42 revisions)",
        Map.of("changeFrequency", 42.0, "engine", "code-maat", "calculation", "revisions >= 20"),
        "fp-hotspot-foo");
  }

  public static PseudonymisedFinding finding(
      String id,
      String ruleId,
      Category category,
      Severity severity,
      String path,
      String message,
      Map<String, Object> evidence,
      String fingerprint) {
    return new PseudonymisedFinding(
        id,
        ruleId,
        category,
        severity,
        0.83,
        path,
        7,
        message,
        evidence,
        0.61,
        true,
        fingerprint,
        List.of(Pseudonym.fromStored(CONTRIBUTOR)));
  }

  public static PseudonymisedFinding withMessage(String message) {
    return finding(
        "id-1",
        "codemaat.hotspot",
        Category.HOTSPOT,
        Severity.HIGH,
        "src/Foo.java",
        message,
        Map.of(),
        "fp-1");
  }

  public static PseudonymisedFinding withEvidence(Map<String, Object> evidence) {
    return finding(
        "id-1",
        "codemaat.hotspot",
        Category.HOTSPOT,
        Severity.HIGH,
        "src/Foo.java",
        "m",
        evidence,
        "fp-1");
  }
}
