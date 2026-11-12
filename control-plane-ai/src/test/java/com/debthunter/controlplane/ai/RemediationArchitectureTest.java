package com.debthunter.controlplane.ai;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.debthunter.controlplane.ai.remediation.FixAgentPrCreator;
import com.debthunter.controlplane.ai.remediation.host.SourceHostClient;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Only one class may change anything on a source host, and nothing else may even hold the client.
 */
@AnalyzeClasses(
    packages = "com.debthunter.controlplane",
    importOptions = ImportOption.DoNotIncludeTests.class)
class RemediationArchitectureTest {

  @ArchTest
  static final ArchRule onlyThePrCreatorWritesToASourceHost =
      noClasses()
          .that()
          .areNotAssignableTo(FixAgentPrCreator.class)
          .should()
          .callMethod(
              SourceHostClient.class,
              "ensureBranchWithFile",
              String.class,
              String.class,
              String.class,
              String.class,
              String.class,
              String.class)
          .orShould()
          .callMethod(
              SourceHostClient.class,
              "openDraftPullRequest",
              String.class,
              String.class,
              String.class,
              String.class,
              String.class,
              java.util.List.class)
          .because(
              "proposal pull requests have one author, so the rules about them live in one place");

  @ArchTest
  static final ArchRule onlyThePrCreatorAndTheAdaptersKnowTheHostClient =
      noClasses()
          .that()
          .areNotAssignableTo(FixAgentPrCreator.class)
          .and()
          .areNotAssignableTo(SourceHostClient.class)
          .and()
          .resideOutsideOfPackage("..remediation.host..")
          .should()
          .dependOnClassesThat()
          .areAssignableTo(SourceHostClient.class);
}
