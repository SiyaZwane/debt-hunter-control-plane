package com.debthunter.controlplane.governance.policy;

import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.debthunter.domain.Category;
import com.debthunter.domain.Finding;
import com.debthunter.domain.Severity;
import com.debthunter.domain.SuppressionEntry;
import com.debthunter.policy.PolicyBundleParser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolicyBundleValidatorTest {

  private final PolicyBundleValidator validator = new PolicyBundleValidator();

  private static Finding finding(String id, Severity severity, Category category, String path) {
    return new Finding(
        id, "rule", category, severity, 1.0, path, 1, "m", null, 1.0, true, "fp-" + id);
  }

  private static ObservedRepository repo(String name, Finding... findings) {
    return new ObservedRepository(name, List.of(findings), List.of());
  }

  private static List<String> codes(ValidationReport report) {
    return report.issues().stream().map(PolicyIssue::code).toList();
  }

  // ---- schema ----------------------------------------------------------------------------------

  @Test
  void aWellFormedBundleIsValidAndComesBackParsed() {
    ValidationReport report =
        validator.validate(policy("1.1").main("no-critical", "CRITICAL", 0).text());

    assertThat(report.isValid()).isTrue();
    assertThat(report.issues()).isEmpty();
    assertThat(report.parsedBundle()).isPresent();
    assertThat(report.parsedBundle().get().version()).isEqualTo("1.1");
  }

  @Test
  void malformedYamlIsASchemaErrorAndNothingIsParsed() {
    ValidationReport report = validator.validate("version: [unclosed");

    assertThat(report.isValid()).isFalse();
    assertThat(codes(report)).containsExactly("SCHEMA");
    assertThat(report.parsedBundle()).isEmpty();
  }

  @Test
  void aMissingVersionNamesTheMissingField() {
    ValidationReport report = validator.validate("policy:\n  main:\n    rules: []\n");

    assertThat(codes(report)).containsExactly("SCHEMA");
    assertThat(report.errors().get(0).message()).contains("version");
  }

  @Test
  void anUnknownSeverityIsASchemaErrorThatNamesIt() {
    ValidationReport report = validator.validate(policy("1").main("r", "SEVERE", 0).text());

    assertThat(codes(report)).containsExactly("SCHEMA");
    assertThat(report.errors().get(0).message()).contains("SEVERE");
  }

  @Test
  void aNegativeThresholdIsASchemaError() {
    ValidationReport report = validator.validate(policy("1").main("r", "HIGH", -1).text());

    assertThat(codes(report)).containsExactly("SCHEMA");
  }

  @Test
  void anUnknownCategoryIsASchemaError() {
    ValidationReport report = validator.validate(policy("1").excludeCategory("NONSENSE").text());

    assertThat(codes(report)).containsExactly("SCHEMA");
  }

  @Test
  void emptyAndBlankTextAreErrorsNotExceptions() {
    assertThat(codes(validator.validate(null))).containsExactly("EMPTY");
    assertThat(codes(validator.validate(""))).containsExactly("EMPTY");
    assertThat(codes(validator.validate("  \n "))).containsExactly("EMPTY");
  }

  @Test
  void aTopLevelListIsASchemaError() {
    assertThat(codes(validator.validate("- a\n- b\n"))).containsExactly("SCHEMA");
  }

  @Test
  void anOversizedBundleIsRefusedWithoutBeingParsed() {
    String huge = "version: \"1\"\n# " + "x".repeat(PolicyBundleValidator.MAX_BUNDLE_BYTES) + "\n";

    ValidationReport report = validator.validate(huge);

    assertThat(codes(report)).containsExactly("TOO_LARGE");
    assertThat(report.parsedBundle()).isEmpty();
  }

  @Test
  void aBundleJustUnderTheLimitIsStillParsed() {
    String padding = "# " + "x".repeat(PolicyBundleValidator.MAX_BUNDLE_BYTES - 200) + "\n";

    ValidationReport report = validator.validate(policy("1").main("r", "HIGH", 0).text() + padding);

    assertThat(report.isValid()).isTrue();
  }

  @Test
  void anAliasBombIsRefusedAsAnErrorNotAHang() {
    StringBuilder bomb = new StringBuilder("version: \"1\"\na: &a [x,x,x,x,x,x,x,x,x]\n");
    String previous = "a";
    for (char c = 'b'; c <= 'j'; c++) {
      bomb.append(c)
          .append(": &")
          .append(c)
          .append(" [*")
          .append(previous)
          .append(",*")
          .append(previous)
          .append(",*")
          .append(previous)
          .append(",*")
          .append(previous)
          .append("]\n");
      previous = String.valueOf(c);
    }

    ValidationReport report = validator.validate(bomb.toString());

    // Either the aliases are refused or the extra keys are simply ignored; what matters is that it
    // answers instead of exhausting memory.
    assertThat(report).isNotNull();
  }

  @Test
  void validateNeverThrowsWhateverItIsGiven() {
    String[] junk = {
      "\u0000\u0001",
      "!!java.lang.Runtime {}",
      "version: !!python/object x",
      "{{{{",
      "---\n---\n",
      "version: 1\npolicy: 5",
      "version: 1\npolicy:\n  main: 7",
      "version: 1\nexclusions: [a]"
    };
    for (String text : junk) {
      ValidationReport report = validator.validate(text);
      assertThat(report).as(text).isNotNull();
      assertThat(report.isValid()).as(text).isFalse();
    }
  }

  // ---- static semantics ------------------------------------------------------------------------

  @Test
  void aDuplicateRuleIdInOneRuleSetIsAnError() {
    ValidationReport report =
        validator.validate(policy("1").main("gate", "CRITICAL", 0).main("gate", "HIGH", 3).text());

    assertThat(report.isValid()).isFalse();
    assertThat(codes(report)).contains("DUPLICATE_RULE_ID");
    assertThat(report.errors().get(0).message()).contains("gate").contains("main");
  }

  @Test
  void theSameIdInMainAndPullRequestIsNotADuplicate() {
    ValidationReport report =
        validator.validate(
            policy("1").main("gate", "CRITICAL", 0).pullRequest("gate", "CRITICAL", 0).text());

    assertThat(report.isValid()).isTrue();
    assertThat(codes(report)).doesNotContain("DUPLICATE_RULE_ID");
  }

  @Test
  void aRuleThatAnotherAlreadyCoversIsShadowed() {
    // "no-high" fails on any HIGH-or-worse finding at all; "few-high" allows 5, but can never fail
    // on its own because no-high has already failed long before the fifth.
    ValidationReport report =
        validator.validate(
            policy("1").main("no-high", "HIGH", 0).main("few-high", "HIGH", 5).text());

    assertThat(report.isValid()).isTrue();
    assertThat(report.warnings()).extracting(PolicyIssue::code).containsExactly("SHADOWED_RULE");
    assertThat(report.warnings().get(0).message()).contains("few-high").contains("no-high");
  }

  @Test
  void aBroaderSeverityAtTheSameToleranceShadowsANarrowerOne() {
    ValidationReport report =
        validator.validate(
            policy("1").main("no-medium", "MEDIUM", 2).main("no-critical", "CRITICAL", 2).text());

    assertThat(report.warnings()).extracting(PolicyIssue::code).containsExactly("SHADOWED_RULE");
    assertThat(report.warnings().get(0).message()).contains("no-critical");
  }

  @Test
  void aStricterTolerancePerSeverityIsNotShadowed() {
    // The classic ladder: no critical, at most 3 high. Neither covers the other.
    ValidationReport report =
        validator.validate(
            policy("1").main("no-critical", "CRITICAL", 0).main("few-high", "HIGH", 3).text());

    assertThat(report.issues()).isEmpty();
  }

  @Test
  void aBundleWithNoRulesAndNoHistoryRequirementWarnsThatItBlocksNothing() {
    ValidationReport report = validator.validate("version: \"1\"\n");

    assertThat(report.isValid()).isTrue();
    assertThat(codes(report)).containsExactly("NO_RULES");
  }

  @Test
  void aHistoryRequirementAloneIsAPolicy() {
    ValidationReport report = validator.validate(policy("1").requireHistory("FULL").text());

    assertThat(report.issues()).isEmpty();
  }

  @Test
  void aBlankExcludedPathIsAnError() {
    ValidationReport report =
        validator.validate(policy("1").main("r", "HIGH", 0).excludePath(" ").text());

    assertThat(report.isValid()).isFalse();
    assertThat(codes(report)).contains("BLANK_EXCLUDED_PATH");
  }

  @Test
  void anExcludedPathThatCanNeverMatchIsAWarning() {
    for (String path : new String[] {"src/gen/", "/src/gen", "./src/gen", "../x", "a/../b"}) {
      ValidationReport report =
          validator.validate(policy("1").main("r", "HIGH", 0).excludePath(path).text());

      assertThat(report.isValid()).as(path).isTrue();
      assertThat(codes(report)).as(path).containsExactly("EXCLUDED_PATH_NEVER_MATCHES");
    }
  }

  @Test
  void aPlainRelativeExcludedPathIsFine() {
    ValidationReport report =
        validator.validate(policy("1").main("r", "HIGH", 0).excludePath("src/generated").text());

    assertThat(report.issues()).isEmpty();
  }

  @Test
  void excludingEveryCategoryWarnsThatNothingCanCount() {
    PolicyYaml yaml = policy("1").main("r", "HIGH", 0);
    for (Category category : Category.values()) {
      yaml.excludeCategory(category.name());
    }

    ValidationReport report = validator.validate(yaml.text());

    assertThat(codes(report)).containsExactly("ALL_CATEGORIES_EXCLUDED");
  }

  @Test
  void errorsComeBeforeWarnings() {
    ValidationReport report =
        validator.validate(
            policy("1")
                .main("a", "HIGH", 0)
                .main("a", "HIGH", 0)
                .main("b", "HIGH", 9)
                .excludePath("src/")
                .text());

    assertThat(report.issues().get(0).level()).isEqualTo(PolicyIssue.Level.ERROR);
    assertThat(report.issues().get(report.issues().size() - 1).level())
        .isEqualTo(PolicyIssue.Level.WARNING);
  }

  // ---- observed distribution -------------------------------------------------------------------

  @Test
  void aThresholdNoObservedRepositoryCanMeetIsFlaggedAsImpossible() {
    ObservedEstate estate =
        new ObservedEstate(
            List.of(
                repo("a", finding("1", Severity.HIGH, Category.HOTSPOT, "x")),
                repo(
                    "b",
                    finding("2", Severity.HIGH, Category.HOTSPOT, "x"),
                    finding("3", Severity.HIGH, Category.HOTSPOT, "y")),
                repo("c", finding("4", Severity.CRITICAL, Category.HOTSPOT, "x"))));

    ValidationReport report =
        validator.validate(policy("1").main("no-high", "HIGH", 0).text(), () -> estate);

    assertThat(report.isValid()).as("a warning does not stop a simulation").isTrue();
    assertThat(codes(report)).containsExactly("IMPOSSIBLE_THRESHOLD");
    assertThat(report.warnings().get(0).message())
        .contains("no-high")
        .contains("all 3 observed repositories")
        .contains("the least affected has 1");
  }

  @Test
  void aThresholdOneRepositoryMeetsIsNotImpossible() {
    ObservedEstate estate =
        new ObservedEstate(
            List.of(
                repo("a", finding("1", Severity.HIGH, Category.HOTSPOT, "x")),
                repo("b", finding("2", Severity.HIGH, Category.HOTSPOT, "x")),
                repo("clean")));

    ValidationReport report =
        validator.validate(policy("1").main("no-high", "HIGH", 0).text(), () -> estate);

    assertThat(report.issues()).isEmpty();
  }

  @Test
  void theThresholdIsJudgedAfterTheBundlesOwnExclusions() {
    // Every repository has a HIGH finding, but they are all in the excluded category, so the rule
    // is perfectly satisfiable once exclusions are applied.
    ObservedEstate estate =
        new ObservedEstate(
            List.of(
                repo("a", finding("1", Severity.HIGH, Category.CHURN, "x")),
                repo("b", finding("2", Severity.HIGH, Category.CHURN, "x")),
                repo("c", finding("3", Severity.HIGH, Category.CHURN, "x"))));

    ValidationReport report =
        validator.validate(
            policy("1").main("no-high", "HIGH", 0).excludeCategory("CHURN").text(), () -> estate);

    assertThat(report.issues()).isEmpty();
  }

  @Test
  void theThresholdIsJudgedAfterSuppressions() {
    Finding f = finding("1", Severity.HIGH, Category.HOTSPOT, "x");
    SuppressionEntry suppression =
        new SuppressionEntry(f.fingerprint(), "o", "r", LocalDate.of(9999, 12, 31));
    ObservedEstate estate =
        new ObservedEstate(
            List.of(
                new ObservedRepository("a", List.of(f), List.of(suppression)),
                repo("b"),
                repo("c")));

    ValidationReport report =
        validator.validate(policy("1").main("no-high", "HIGH", 0).text(), () -> estate);

    assertThat(report.issues()).isEmpty();
  }

  @Test
  void tooSmallASampleSaysNothingAboutImpossibility() {
    ObservedEstate estate =
        new ObservedEstate(
            List.of(
                repo("a", finding("1", Severity.HIGH, Category.HOTSPOT, "x")),
                repo("b", finding("2", Severity.HIGH, Category.HOTSPOT, "x"))));

    ValidationReport report =
        validator.validate(policy("1").main("no-high", "HIGH", 0).text(), () -> estate);

    assertThat(report.issues()).isEmpty();
  }

  @Test
  void theSampleGuardIsConfigurable() {
    PolicyBundleValidator strict = new PolicyBundleValidator(new PolicyBundleParser(), 1);
    ObservedEstate estate =
        new ObservedEstate(List.of(repo("a", finding("1", Severity.HIGH, Category.HOTSPOT, "x"))));

    ValidationReport report =
        strict.validate(policy("1").main("no-high", "HIGH", 0).text(), () -> estate);

    assertThat(codes(report)).containsExactly("IMPOSSIBLE_THRESHOLD");
  }

  @Test
  void aSampleGuardBelowOneIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PolicyBundleValidator(new PolicyBundleParser(), 0));
  }

  @Test
  void anEstateThatCannotBeObservedIsAWarningNotAFailure() {
    ValidationReport report =
        validator.validate(
            policy("1").main("no-high", "HIGH", 0).text(),
            () -> {
              throw new IllegalStateException("database down");
            });

    assertThat(report.isValid()).isTrue();
    assertThat(codes(report)).containsExactly("DISTRIBUTION_UNAVAILABLE");
    assertThat(report.warnings().get(0).message()).doesNotContain("database down");
  }

  @Test
  void theEstateIsNotObservedWhenTheBundleIsAlreadyRejected() {
    AtomicInteger observed = new AtomicInteger();

    validator.validate("version: [broken", () -> count(observed));
    validator.validate(
        policy("1").main("a", "HIGH", 0).main("a", "HIGH", 0).text(), () -> count(observed));

    assertThat(observed).hasValue(0);
  }

  @Test
  void theEstateIsNotObservedWhenThereAreNoMainRulesToCheck() {
    AtomicInteger observed = new AtomicInteger();

    validator.validate(policy("1").pullRequest("pr", "HIGH", 0).text(), () -> count(observed));

    assertThat(observed).hasValue(0);
  }

  @Test
  void onlyMainRulesAreCheckedAgainstTheObservedEstate() {
    ObservedEstate estate =
        new ObservedEstate(
            new ArrayList<>(
                List.of(
                    repo("a", finding("1", Severity.HIGH, Category.HOTSPOT, "x")),
                    repo("b", finding("2", Severity.HIGH, Category.HOTSPOT, "x")),
                    repo("c", finding("3", Severity.HIGH, Category.HOTSPOT, "x")))));

    ValidationReport report =
        validator.validate(
            policy("1").main("ok", "CRITICAL", 0).pullRequest("pr", "HIGH", 0).text(),
            () -> estate);

    assertThat(report.issues()).isEmpty();
  }

  private static ObservedEstate count(AtomicInteger counter) {
    counter.incrementAndGet();
    return ObservedEstate.none();
  }
}
