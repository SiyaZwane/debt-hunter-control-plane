package com.debthunter.controlplane.governance.policy;

import com.debthunter.domain.Category;
import com.debthunter.domain.PolicyResult;
import com.debthunter.domain.PolicyViolation;
import com.debthunter.domain.Severity;
import com.debthunter.engine.spi.AnalysisMode;
import com.debthunter.policy.PolicyBundle;
import com.debthunter.policy.PolicyBundleParser;
import com.debthunter.policy.PolicyEvaluator;
import com.debthunter.policy.PolicyParseException;
import com.debthunter.policy.PolicyRule;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Judges a candidate policy bundle before anything is simulated: is it well formed (schema), does
 * it say something coherent (semantics), and can the estate as it actually stands ever satisfy it
 * (observed distribution)?
 *
 * <p>The schema check is the data plane's own {@link PolicyBundleParser}, not a copy of it, so a
 * bundle this validator accepts is a bundle a pipeline will accept. The parser reports the first
 * structural problem it finds; this validator reports that one problem faithfully rather than
 * guessing at a recovery.
 *
 * <p>Errors and warnings are separate on purpose. Anything that makes the bundle untrustworthy as
 * text (malformed, duplicate rule ids, a blank exclusion) is an error and stops the simulation.
 * "This threshold would fail every repository you have" is a warning: that is exactly the thing a
 * simulation exists to quantify, so refusing to simulate it would hide the answer.
 *
 * <p>{@link #validate} never throws; whatever text it is handed, it answers.
 */
public final class PolicyBundleValidator {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyBundleValidator.class);

  /** The largest bundle accepted, in UTF-8 bytes. A policy is a page of YAML, not a payload. */
  public static final int MAX_BUNDLE_BYTES = 256 * 1024;

  /** How many observed repositories are needed before "nobody can meet this" is worth saying. */
  public static final int DEFAULT_MINIMUM_SAMPLE = 3;

  private final PolicyBundleParser parser;
  private final PolicyEvaluator evaluator = new PolicyEvaluator();
  private final int minimumSample;

  /** A validator over the data plane's parser with the default sample-size guard. */
  public PolicyBundleValidator() {
    this(new PolicyBundleParser(), DEFAULT_MINIMUM_SAMPLE);
  }

  /**
   * A validator with explicit collaborators.
   *
   * @param parser the schema check
   * @param minimumSample how many observed repositories are needed before a threshold is called
   *     impossible; a threshold that fails two repositories out of two says little
   */
  public PolicyBundleValidator(PolicyBundleParser parser, int minimumSample) {
    if (minimumSample < 1) {
      throw new IllegalArgumentException("minimumSample must be at least 1: " + minimumSample);
    }
    this.parser = parser;
    this.minimumSample = minimumSample;
  }

  /**
   * Validates {@code yaml} without any knowledge of the estate: schema and static semantics only.
   *
   * @param yaml the candidate bundle's text
   * @return the verdict
   */
  public ValidationReport validate(String yaml) {
    return validate(yaml, ObservedEstate::none);
  }

  /**
   * Validates {@code yaml}, and, if it is well formed, checks its thresholds against {@code
   * observed}.
   *
   * <p>{@code observed} is only asked for after the bundle has passed every check that does not
   * need it, so a malformed bundle costs no reads of the system of record.
   *
   * @param yaml the candidate bundle's text
   * @param observed produces the observed estate; may be expensive, may fail
   * @return the verdict
   */
  public ValidationReport validate(String yaml, Supplier<ObservedEstate> observed) {
    if (yaml == null || yaml.isBlank()) {
      return new ValidationReport(
          List.of(PolicyIssue.error("EMPTY", "The policy bundle is empty")), null);
    }
    if (yaml.getBytes(StandardCharsets.UTF_8).length > MAX_BUNDLE_BYTES) {
      return new ValidationReport(
          List.of(
              PolicyIssue.error(
                  "TOO_LARGE", "The policy bundle is larger than " + MAX_BUNDLE_BYTES + " bytes")),
          null);
    }

    PolicyBundle bundle;
    try {
      bundle = parser.parse(yaml);
    } catch (PolicyParseException e) {
      return new ValidationReport(List.of(PolicyIssue.error("SCHEMA", e.getMessage())), null);
    } catch (RuntimeException e) {
      // The parser is meant to wrap everything it can; this is for what it did not foresee.
      return new ValidationReport(
          List.of(PolicyIssue.error("SCHEMA", "The policy bundle could not be read")), null);
    }

    List<PolicyIssue> issues = new ArrayList<>(staticChecks(bundle));
    boolean hasErrors = issues.stream().anyMatch(i -> i.level() == PolicyIssue.Level.ERROR);
    if (!hasErrors && !bundle.mainRules().isEmpty()) {
      issues.addAll(distributionChecks(bundle, observed));
    }
    issues.sort((a, b) -> a.level().compareTo(b.level()));
    return new ValidationReport(issues, bundle);
  }

  private List<PolicyIssue> staticChecks(PolicyBundle bundle) {
    List<PolicyIssue> issues = new ArrayList<>();

    if (bundle.mainRules().isEmpty()
        && bundle.pullRequestRules().isEmpty()
        && bundle.minimumHistoryDepth() == null) {
      issues.add(
          PolicyIssue.warning(
              "NO_RULES", "The policy has no rules and no history requirement: it blocks nothing"));
    }

    checkRules("main", bundle.mainRules(), issues);
    checkRules("pullRequest", bundle.pullRequestRules(), issues);

    for (String path : bundle.excludedPaths()) {
      if (path.isBlank()) {
        issues.add(PolicyIssue.error("BLANK_EXCLUDED_PATH", "An excluded path is blank"));
      } else if (neverMatches(path)) {
        issues.add(
            PolicyIssue.warning(
                "EXCLUDED_PATH_NEVER_MATCHES",
                "Excluded path '"
                    + path
                    + "' will not match anything: paths are repository-relative, with no leading"
                    + " './' or '/', no '..', and no trailing '/'"));
      }
    }

    if (new HashSet<>(bundle.excludedCategories()).size() == Category.values().length) {
      issues.add(
          PolicyIssue.warning(
              "ALL_CATEGORIES_EXCLUDED",
              "Every category is excluded, so no finding can ever count against a rule"));
    }
    return issues;
  }

  private static boolean neverMatches(String path) {
    return path.startsWith("/")
        || path.startsWith("./")
        || path.endsWith("/")
        || path.equals("..")
        || path.contains("/../")
        || path.startsWith("../")
        || path.endsWith("/..");
  }

  private static void checkRules(String mode, List<PolicyRule> rules, List<PolicyIssue> issues) {
    Set<String> seen = new HashSet<>();
    for (PolicyRule rule : rules) {
      if (!seen.add(rule.id())) {
        issues.add(
            PolicyIssue.error(
                "DUPLICATE_RULE_ID",
                "Rule id '" + rule.id() + "' appears more than once in policy." + mode));
      }
    }
    for (PolicyRule rule : rules) {
      for (PolicyRule other : rules) {
        if (rule != other && !rule.id().equals(other.id()) && shadows(other, rule)) {
          issues.add(
              PolicyIssue.warning(
                  "SHADOWED_RULE",
                  "Rule '"
                      + rule.id()
                      + "' in policy."
                      + mode
                      + " can never fail on its own: rule '"
                      + other.id()
                      + "' already fails whenever it would"));
          break;
        }
      }
    }
  }

  /**
   * {@code broader} shadows {@code narrower} when it counts every finding {@code narrower} counts
   * (severity at least as inclusive) and tolerates no more of them.
   */
  private static boolean shadows(PolicyRule broader, PolicyRule narrower) {
    boolean countsEverythingNarrowerCounts =
        broader.minSeverity().ordinal() >= narrower.minSeverity().ordinal();
    boolean noMoreForgiving = broader.maxCount() <= narrower.maxCount();
    boolean strictlyDifferent =
        broader.minSeverity() != narrower.minSeverity()
            || broader.maxCount() != narrower.maxCount();
    return countsEverythingNarrowerCounts && noMoreForgiving && strictlyDifferent;
  }

  private List<PolicyIssue> distributionChecks(
      PolicyBundle bundle, Supplier<ObservedEstate> observedSupplier) {
    ObservedEstate observed;
    try {
      observed = observedSupplier.get();
    } catch (RuntimeException e) {
      LOG.warn("Could not observe the estate to check the thresholds", e);
      return List.of(
          PolicyIssue.warning(
              "DISTRIBUTION_UNAVAILABLE",
              "The estate could not be observed, so the thresholds were not checked against it"));
    }
    if (observed.sampleSize() < minimumSample) {
      return List.of();
    }

    List<PolicyIssue> issues = new ArrayList<>();
    for (PolicyRule rule : bundle.mainRules()) {
      int lowest = Integer.MAX_VALUE;
      for (ObservedRepository repository : observed.repositories()) {
        lowest = Math.min(lowest, countFindingsAgainst(bundle, repository, rule.minSeverity()));
      }
      if (lowest > rule.maxCount()) {
        issues.add(
            PolicyIssue.warning(
                "IMPOSSIBLE_THRESHOLD",
                "Rule '"
                    + rule.id()
                    + "' (at most "
                    + rule.maxCount()
                    + " new finding(s) at "
                    + rule.minSeverity()
                    + " or worse) would fail all "
                    + observed.sampleSize()
                    + " observed repositories; the least affected has "
                    + lowest
                    + ". The estate cannot meet it as it stands."));
      }
    }
    return issues;
  }

  /**
   * How many findings a repository would have counted against a rule at {@code severity}, after the
   * bundle's exclusions and the repository's suppressions. Asked of the real evaluator with a
   * zero-tolerance probe rule rather than re-implemented, so this can never disagree with it.
   */
  private int countFindingsAgainst(
      PolicyBundle bundle, ObservedRepository repository, Severity severity) {
    PolicyBundle probe =
        new PolicyBundle(
            bundle.version(),
            Map.of(),
            null,
            List.of(new PolicyRule("probe", severity, 0)),
            List.of(),
            bundle.excludedCategories(),
            bundle.excludedPaths(),
            0);
    PolicyResult result =
        evaluator.evaluate(
            repository.findings(), probe, AnalysisMode.FULL, null, repository.suppressions());
    return result.reasons().stream()
        .filter(v -> v.rule().equals("probe"))
        .findFirst()
        .map(PolicyViolation::actual)
        .map(Integer::parseInt)
        .orElse(0);
  }
}
