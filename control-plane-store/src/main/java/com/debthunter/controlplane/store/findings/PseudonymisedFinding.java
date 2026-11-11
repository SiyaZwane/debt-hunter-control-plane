package com.debthunter.controlplane.store.findings;

import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A finding as the control plane stores it: the domain finding's content, but with contributor
 * identity represented only as {@link Pseudonym}s.
 *
 * <p>There is deliberately no field of any type that could hold a raw identity, and {@code
 * evidence} has been reduced to keys known not to carry one.
 *
 * @param id the finding id
 * @param ruleId the rule that raised it
 * @param category what kind of debt
 * @param severity how severe
 * @param confidence how sure the engine was
 * @param path the file concerned
 * @param startLine where in it
 * @param message the human-readable description
 * @param evidence the engine's supporting data, limited to identity-free keys
 * @param score the engine's score
 * @param isNew whether the data plane reported it as new
 * @param fingerprint the finding's stable fingerprint
 * @param contributors who touched it, as pseudonyms
 */
public record PseudonymisedFinding(
    String id,
    String ruleId,
    Category category,
    Severity severity,
    double confidence,
    String path,
    int startLine,
    String message,
    Map<String, Object> evidence,
    double score,
    boolean isNew,
    String fingerprint,
    List<Pseudonym> contributors) {

  /** Validates and defensively copies the components. */
  public PseudonymisedFinding {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(fingerprint, "fingerprint");
    evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    contributors = contributors == null ? List.of() : List.copyOf(contributors);
  }
}
