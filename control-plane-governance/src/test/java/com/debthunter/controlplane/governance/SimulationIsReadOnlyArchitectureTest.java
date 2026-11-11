package com.debthunter.controlplane.governance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The structural half of "a simulation mutates nothing": the simulator is handed a port with no
 * write methods, only one class touches the stores at all, and that class never calls a write.
 */
class SimulationIsReadOnlyArchitectureTest {

  private static final String ADAPTER =
      "com.debthunter.controlplane.governance.simulation.StoreBackedRetainedEvidence";

  private static JavaClasses governance;

  @BeforeAll
  static void importGovernance() {
    governance =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.debthunter.controlplane.governance");
  }

  @Test
  void onlyTheEvidenceAdapterDependsOnAWriteCapableStorePort() {
    noClasses()
        .that()
        .resideInAPackage("com.debthunter.controlplane.governance..")
        .and()
        .doNotHaveFullyQualifiedName(ADAPTER)
        .should()
        .dependOnClassesThat()
        .areAssignableTo(RunRepository.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(PseudonymisedFindingStore.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(RepositoryRegistry.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(FindingStateRepository.class)
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(TransitionLogRepository.class)
        .because("everything else sees the stores only through the read-only RetainedEvidence")
        .check(governance);
  }

  @Test
  void theEvidenceAdapterNeverCallsAWriteMethod() {
    noClasses()
        .that()
        .haveFullyQualifiedName(ADAPTER)
        .should()
        .callMethod(
            RunRepository.class, "save", com.debthunter.controlplane.store.runs.RunRecord.class)
        .orShould()
        .callMethod(
            PseudonymisedFindingStore.class, "saveRun", String.class, String.class, List.class)
        .orShould()
        .callMethod(
            RepositoryRegistry.class,
            "save",
            com.debthunter.controlplane.store.registry.OnboardedRepository.class)
        .orShould()
        .callMethod(RepositoryRegistry.class, "delete", String.class)
        .orShould()
        .callMethod(RepositoryRegistry.class, "grant", String.class, String.class)
        .orShould()
        .callMethod(RepositoryRegistry.class, "revoke", String.class, String.class)
        .because("reading the system of record is all the simulator may do to it")
        .check(governance);
  }

  @Test
  void theRetainedEvidencePortDeclaresOnlyReads() {
    classes()
        .that()
        .haveFullyQualifiedName(
            "com.debthunter.controlplane.governance.simulation.RetainedEvidence")
        .should()
        .beInterfaces()
        .check(governance);
    org.assertj.core.api.Assertions.assertThat(
            com.debthunter.controlplane.governance.simulation.RetainedEvidence.class
                .getDeclaredMethods())
        .extracting(java.lang.reflect.Method::getName)
        .containsExactlyInAnyOrder("estate", "recentRuns", "findingsOf", "suppressedAt");
  }

  @Test
  void governanceDoesNotReachIntoIngestOrTheApi() {
    noClasses()
        .that()
        .resideInAPackage("com.debthunter.controlplane.governance..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.debthunter.controlplane.ingest..", "com.debthunter.controlplane.api..")
        .check(governance);
  }
}
