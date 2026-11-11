package com.debthunter.controlplane.serve.finding;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.util.Map;

/**
 * A finding as a reader sees it. It leaves out contributors, even as pseudonyms: a reader of a
 * finding has no need of who touched the file.
 *
 * @param id the finding id
 * @param ruleId the rule that raised it
 * @param category what kind of debt
 * @param severity how severe
 * @param path the file
 * @param startLine where in it
 * @param message the description
 * @param evidence the retained supporting data
 * @param isNew whether the data plane reported it as new
 */
public record FindingView(
    String id,
    String ruleId,
    Category category,
    Severity severity,
    String path,
    int startLine,
    String message,
    Map<String, Object> evidence,
    boolean isNew) {

  /** Copies the evidence. */
  public FindingView {
    evidence = Map.copyOf(evidence);
  }

  /** The view of a stored finding. */
  public static FindingView of(PseudonymisedFinding f) {
    return new FindingView(
        f.id(),
        f.ruleId(),
        f.category(),
        f.severity(),
        f.path(),
        f.startLine(),
        f.message(),
        f.evidence(),
        f.isNew());
  }
}
