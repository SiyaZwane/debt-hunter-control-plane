package com.debthunter.controlplane.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.debthunter.controlplane.api.ingestion.IngestionApiConfiguration;
import com.debthunter.controlplane.api.ingestion.IngestionController;
import com.debthunter.controlplane.api.ingestion.WorkloadIdentityValidator;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * CP-AC-85, structurally: the only way a run reaches the intake queue from the API is through the
 * ingestion controller, and the ingestion controller cannot be built without the check that the
 * caller's workload identity may write for the repository the run declares. A new route that wrote
 * to the queue would have to appear here as an exception, in review.
 */
@AnalyzeClasses(
    packages = "com.debthunter.controlplane.api",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ApiArchitectureTest {

  @ArchTest
  static final ArchRule onlyTheIngestionControllerWritesToTheIntakeQueue =
      noClasses()
          .that()
          .resideInAPackage("..api..")
          .and()
          .doNotHaveFullyQualifiedName(IngestionController.class.getName())
          .and()
          .doNotHaveFullyQualifiedName(IngestionApiConfiguration.class.getName())
          .should()
          .dependOnClassesThat()
          .areAssignableTo(RunIntakeQueuePublisher.class)
          .because("every write to the queue must pass the ingestion controller's scope check");

  @ArchTest
  static final ArchRule theIngestionControllerDependsOnTheScopeCheck =
      classes()
          .that()
          .haveFullyQualifiedName(IngestionController.class.getName())
          .should()
          .dependOnClassesThat()
          .areAssignableTo(WorkloadIdentityValidator.class)
          .because("a run is only queued for a repository its caller's identity may write for");
}
