package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.ingest.normalise.NormalisedRunBundle;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.domain.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a normalised bundle into one that is safe to persist, by sending every contributor identity
 * through {@link IdentityPseudonymiser} and removing anything else that could carry one.
 *
 * <p>Evidence is handled <strong>default-deny</strong>. Only the keys in {@link
 * #SAFE_EVIDENCE_KEYS} are kept; anything else is dropped and counted, because an engine can put an
 * author, owner or email address under any key and there is no way to recognise one by looking. The
 * cost is that a new engine's evidence is withheld until its keys are reviewed and added here.
 */
public final class RunBundlePseudonymiser {

  /** The evidence key that lists contributors; its values are pseudonymised, not kept. */
  static final String CONTRIBUTORS_KEY = "authors";

  /** Evidence keys reviewed and known to hold no contributor identity. */
  static final Set<String> SAFE_EVIDENCE_KEYS =
      Set.of(
          "changeFrequency",
          "complexityDelta",
          "calculation",
          "engine",
          "import",
          "rule",
          "originalCategory");

  private final IdentityPseudonymiser identities;

  /**
   * Creates a pseudonymiser.
   *
   * @param identities turns an identity into a pseudonym
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public RunBundlePseudonymiser(IdentityPseudonymiser identities) {
    this.identities = Objects.requireNonNull(identities, "identities");
  }

  /**
   * Pseudonymises a bundle.
   *
   * @param bundle the normalised bundle, which carries raw identity
   * @return the same content with identity replaced by pseudonyms
   * @throws KeyStoreUnavailableException if the pseudonym key cannot be obtained
   */
  public PseudonymisedRunBundle pseudonymise(NormalisedRunBundle bundle) {
    // Obtain the key before touching anything, so an unavailable key store fails the whole bundle
    // even if it has no contributors to pseudonymise: there is no "nothing to hide" shortcut.
    identities.requireKey();

    int[] dropped = {0, 0};
    List<PseudonymisedFinding> findings = new ArrayList<>();
    for (Finding finding : bundle.scan().findings()) {
      findings.add(pseudonymise(finding, dropped));
    }
    return new PseudonymisedRunBundle(
        bundle.scan().run(),
        findings,
        bundle.scan().metrics(),
        bundle.scan().policy(),
        bundle.provenance(),
        bundle.report(),
        new PseudonymisationReport(dropped[0], dropped[1]));
  }

  private PseudonymisedFinding pseudonymise(Finding finding, int[] dropped) {
    Map<String, Object> safeEvidence = new LinkedHashMap<>();
    Set<Pseudonym> contributors = new LinkedHashSet<>();
    for (Map.Entry<String, Object> entry : finding.evidence().entrySet()) {
      if (CONTRIBUTORS_KEY.equals(entry.getKey())) {
        collectContributors(entry.getValue(), contributors, dropped);
      } else if (SAFE_EVIDENCE_KEYS.contains(entry.getKey())) {
        safeEvidence.put(entry.getKey(), entry.getValue());
      } else {
        dropped[0]++;
      }
    }
    return new PseudonymisedFinding(
        finding.id(),
        finding.ruleId(),
        finding.category(),
        finding.severity(),
        finding.confidence(),
        finding.path(),
        finding.startLine(),
        finding.message(),
        safeEvidence,
        finding.score(),
        finding.isNew(),
        finding.fingerprint(),
        List.copyOf(contributors));
  }

  private void collectContributors(Object value, Set<Pseudonym> out, int[] dropped) {
    if (!(value instanceof List<?> list)) {
      dropped[0]++; // unexpected shape: it cannot be pseudonymised, so it cannot be kept
      return;
    }
    for (Object element : list) {
      String identity = String.valueOf(element);
      if (element == null || identity.isBlank()) {
        dropped[1]++;
      } else {
        out.add(identities.pseudonymise(identity));
      }
    }
  }
}
