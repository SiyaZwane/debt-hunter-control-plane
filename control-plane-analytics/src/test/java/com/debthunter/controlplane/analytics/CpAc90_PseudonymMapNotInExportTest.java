package com.debthunter.controlplane.analytics;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-90: the pseudonym map cannot appear in anything exported. There is no export feature; what
 * any future one would be built from is the analytics read models, so this pins that they cannot
 * reach the directory, cannot be handed a pseudonym or a person, and have no column that could hold
 * one. (The estate-wide rule that only the pseudonymiser and the audited re-identification service
 * touch the directory is in {@code control-plane-app}.)
 */
class CpAc90_PseudonymMapNotInExportTest {

  private static JavaClasses analytics;

  @BeforeAll
  static void importAnalytics() {
    analytics =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.debthunter.controlplane.analytics");
  }

  @Test
  void noAnalyticsClassCanReachThePseudonymDirectoryOrAPseudonym() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.debthunter.controlplane.store.identity..")
        .because("read models, and anything exported from them, never carry or resolve a person")
        .check(analytics);
  }

  @Test
  void noAnalyticsClassDependsOnIngestWhereIdentitiesAreHandled() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.debthunter.controlplane.ingest..")
        .check(analytics);
  }

  @Test
  void noReadModelFieldIsNamedForAPerson() {
    fields()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..analytics..")
        .should()
        .haveNameNotMatching("(?i).*(contributor|author|committer|email|identity|pseudonym).*")
        .check(analytics);
  }

  @Test
  void noReadModelTableHasAColumnThatCouldHoldAPerson() throws IOException {
    for (String migration :
        List.of(
            "V4__analytics_read_models.sql",
            "V6__coverage.sql",
            "V8__coverage_rollout_stage.sql")) {
      try (var in = getClass().getResourceAsStream("/db/migration/" + migration)) {
        assertThat(in).as(migration).isNotNull();
        String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql)
            .as(migration)
            .doesNotContain("contributor")
            .doesNotContain("author")
            .doesNotContain("committer")
            .doesNotContain("email")
            .doesNotContain("identity")
            .doesNotContain("pseudonym");
      }
    }
  }
}
