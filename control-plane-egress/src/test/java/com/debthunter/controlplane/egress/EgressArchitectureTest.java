package com.debthunter.controlplane.egress;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** What egress may and may not depend on. */
@AnalyzeClasses(
    packages = "com.debthunter.controlplane.egress",
    importOptions = ImportOption.DoNotIncludeTests.class)
class EgressArchitectureTest {

  @ArchTest
  static final ArchRule egressDoesNotReachForTheOtherZones =
      noClasses()
          .that()
          .resideInAPackage("com.debthunter.controlplane.egress..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.debthunter.controlplane.governance..",
              "com.debthunter.controlplane.ai..",
              "com.debthunter.controlplane.api..",
              "com.debthunter.controlplane.serve..",
              "com.debthunter.controlplane.ingest..",
              "com.debthunter.controlplane.analytics..",
              "com.debthunter.controlplane.app..");

  @ArchTest
  static final ArchRule onlyTheTrackerPlumbingTalksHttp =
      noClasses()
          .that()
          .doNotHaveSimpleName("TrackerHttp")
          .and()
          .doNotHaveSimpleName("WebhookHttp")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.net.http..");

  @ArchTest
  static final ArchRule onlyTheOrchestratorAndLinksWriteToTrackers =
      noClasses()
          .that()
          .doNotHaveSimpleName("WorkItemOrchestrator")
          .and()
          .doNotHaveSimpleName("JiraWorkItemAdapter")
          .and()
          .doNotHaveSimpleName("AzureBoardsWorkItemAdapter")
          .should()
          .callMethod(
              com.debthunter.controlplane.egress.workitem.WorkItemTracker.class,
              "create",
              com.debthunter.controlplane.store.registry.WorkItemRoute.class,
              com.debthunter.controlplane.egress.workitem.WorkItemContent.class);
}
