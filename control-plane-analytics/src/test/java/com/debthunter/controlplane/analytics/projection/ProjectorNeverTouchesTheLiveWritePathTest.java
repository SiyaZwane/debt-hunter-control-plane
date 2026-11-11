package com.debthunter.controlplane.analytics.projection;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Analytics reads the system of record and writes only its own read models. This is the structural
 * guarantee behind "stopping the projector never blocks ingest": it has no way to write to, or wait
 * on, anything the ingest path uses.
 */
class ProjectorNeverTouchesTheLiveWritePathTest {

  private static JavaClasses analytics;

  @BeforeAll
  static void importAnalytics() {
    analytics =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.debthunter.controlplane.analytics");
  }

  @Test
  void noAnalyticsClassCallsAnyWriteMethodOfTheLiveStores() {
    noClasses()
        .that()
        .resideInAPackage("com.debthunter.controlplane.analytics..")
        .should()
        .callMethod(TransitionLogRepository.class, "append", List.class)
        .orShould()
        .callMethod(
            RunRepository.class, "save", com.debthunter.controlplane.store.runs.RunRecord.class)
        .orShould()
        .callMethod(FindingStateRepository.class, "putAll", java.util.Collection.class)
        .orShould()
        .callMethod(
            PseudonymisedFindingStore.class, "saveRun", String.class, String.class, List.class)
        .because("the projector only follows the live stores; it never writes to them")
        .check(analytics);
  }

  @Test
  void analyticsDoesNotDependOnIngestOrOnTheApi() {
    noClasses()
        .that()
        .resideInAPackage("com.debthunter.controlplane.analytics..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.debthunter.controlplane.ingest..", "com.debthunter.controlplane.api..")
        .because(
            "ingest must not be able to reach analytics, and analytics must not reach into ingest")
        .check(analytics);
  }
}
