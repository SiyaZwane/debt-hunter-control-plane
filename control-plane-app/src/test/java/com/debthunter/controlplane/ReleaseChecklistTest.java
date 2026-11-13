package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The release checklist as a test: every acceptance criterion and boundary case has a named test,
 * nothing is disabled, and no test fixture carries key material. It reads the repository's own
 * sources, so a deleted or renamed test fails the build instead of quietly leaving a gap.
 */
class ReleaseChecklistTest {

  /** Any disabling annotation, qualified or not. Built in parts so this file does not match it. */
  private static final Pattern DISABLED = Pattern.compile("@(?:\\w+\\.)*Dis" + "abled");

  private static final int ACCEPTANCE_CRITERIA = 95;
  private static final int BOUNDARY_CASES = 14;

  /**
   * Boundary cases the source prompt numbers but never defines, so there is nothing to test. This
   * list is a visible record of the gap, not an allowance: the check below fails if a listed id has
   * gained a test, so it cannot go stale.
   */
  private static final Set<String> UNSPECIFIED_BOUNDARY_CASES = Set.of("CP-BC-03", "CP-BC-10");

  private static final Path SELF =
      RepositoryFiles.root()
          .resolve(
              "control-plane-app/src/test/java/com/debthunter/controlplane/"
                  + "ReleaseChecklistTest.java");

  /** Java test sources, apart from this file (which names ids without testing them). */
  private static Map<Path, String> javaSources() {
    return RepositoryFiles.sourceFiles().stream()
        .filter(p -> p.toString().endsWith(".java"))
        .filter(p -> !p.equals(SELF))
        .collect(Collectors.toMap(p -> p, RepositoryFiles::read));
  }

  private static boolean hasNamedTest(Map<Path, String> sources, String kind, int number) {
    String className = String.format("Cp%s%02d_", kind, number);
    String methodName = String.format("cp%s%02d_", kind, number);
    String compact = String.format("Cp%s%02d_", kind, number).toLowerCase();
    Pattern code = Pattern.compile("(?i)Cp" + kind + String.format("%02d", number) + "_");
    Pattern both = Pattern.compile("(?i)_cp" + kind + String.format("%02d", number) + "_");
    return sources.entrySet().stream()
        .filter(
            e ->
                e.getKey().toString().contains("/src/test/")
                    || e.getKey().toString().contains("/src/integrationTest/"))
        .anyMatch(
            e ->
                e.getKey().getFileName().toString().startsWith(className)
                    || e.getValue().contains("void " + methodName)
                    || code.matcher(e.getValue()).find() && e.getValue().contains("@Test")
                    || both.matcher(e.getValue()).find()
                    || e.getValue().toLowerCase().contains("void " + compact));
  }

  @Test
  void everyAcceptanceCriterionHasANamedTest() {
    Map<Path, String> sources = javaSources();
    List<String> missing = new ArrayList<>();
    for (int i = 1; i <= ACCEPTANCE_CRITERIA; i++) {
      if (!hasNamedTest(sources, "Ac", i)) {
        missing.add(String.format("CP-AC-%02d", i));
      }
    }
    assertThat(missing).as("acceptance criteria with no named test").isEmpty();
  }

  @Test
  void everyDefinedBoundaryCaseHasANamedTest() {
    Map<Path, String> sources = javaSources();
    List<String> missing = new ArrayList<>();
    for (int i = 1; i <= BOUNDARY_CASES; i++) {
      String id = String.format("CP-BC-%02d", i);
      if (!UNSPECIFIED_BOUNDARY_CASES.contains(id) && !hasNamedTest(sources, "Bc", i)) {
        missing.add(id);
      }
    }
    assertThat(missing).as("boundary cases with no named test").isEmpty();
  }

  @Test
  void theUnspecifiedBoundaryCasesStillHaveNoTestSoTheListIsNotStale() {
    Map<Path, String> sources = javaSources();
    Set<String> nowTested = new TreeSet<>();
    for (String id : UNSPECIFIED_BOUNDARY_CASES) {
      int number = Integer.parseInt(id.substring(id.length() - 2));
      if (hasNamedTest(sources, "Bc", number)) {
        nowTested.add(id);
      }
    }
    assertThat(nowTested)
        .as("ids in UNSPECIFIED_BOUNDARY_CASES that now have a test: remove them from the list")
        .isEmpty();
  }

  @Test
  void theCheckSeesTheWholeEstate() {
    List<Path> files = RepositoryFiles.sourceFiles();
    long modules =
        files.stream()
            .map(p -> RepositoryFiles.root().relativize(p).getName(0).toString())
            .distinct()
            .count();
    assertThat(modules).as("modules with sources").isGreaterThanOrEqualTo(9);
    assertThat(
            IntStream.rangeClosed(1, ACCEPTANCE_CRITERIA)
                .filter(i -> hasNamedTest(javaSources(), "Ac", i))
                .count())
        .isPositive();
  }

  @Test
  void noTestIsDisabled() {
    List<String> disabled = new ArrayList<>();
    javaSources()
        .forEach(
            (path, text) -> {
              if (DISABLED.matcher(text).find()) {
                disabled.add(RepositoryFiles.root().relativize(path).toString());
              }
            });
    assertThat(disabled).as("files with a disabling annotation").isEmpty();
  }

  @Test
  void theDegradedRunTestIsPresent() {
    // CP-AC-17: a degraded run must not resolve any finding. It lives with the other lifecycle
    // criteria, so look for its method rather than a file of its own.
    assertThat(
            javaSources().values().stream()
                .anyMatch(
                    text -> {
                      int at = text.indexOf("void cpAc17_");
                      return at >= 0
                          && text.substring(at, Math.min(text.length(), at + 400))
                              .toLowerCase()
                              .contains("degraded");
                    }))
        .as("CP-AC-17: a degraded run must not resolve any finding")
        .isTrue();
  }

  @Test
  void noResourceFileCarriesKeyMaterial() {
    // A 32-byte key in base64 is 43 characters and a final "=". Nothing the build ships or tests
    // against as a fixture should hold one; test keys are built in code, never kept as files.
    Pattern key = Pattern.compile("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])");
    List<String> carrying = new ArrayList<>();
    for (Path file : RepositoryFiles.sourceFiles()) {
      String name = file.toString();
      if (name.contains("/resources/") && !name.endsWith(".sql")) {
        if (key.matcher(RepositoryFiles.read(file)).find()) {
          carrying.add(RepositoryFiles.root().relativize(file).toString());
        }
      }
    }
    assertThat(carrying).as("resource files that look like they hold a key").isEmpty();
  }
}
