package com.debthunter.controlplane.serve;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.debthunter.controlplane.serve.access.Caller;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/** What the read APIs may and may not depend on, and that every service method checks a caller. */
@AnalyzeClasses(
    packages = "com.debthunter.controlplane.serve",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ServeArchitectureTest {

  private static final ArchCondition<JavaMethod> TAKE_A_CALLER_FIRST =
      new ArchCondition<>("take a Caller as the first parameter") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
          boolean ok =
              !method.getRawParameterTypes().isEmpty()
                  && method.getRawParameterTypes().get(0).isEquivalentTo(Caller.class);
          if (!ok) {
            events.add(
                SimpleConditionEvent.violated(method, method.getFullName() + " takes no Caller"));
          }
        }
      };

  @ArchTest
  static final ArchRule everyPublicServiceMethodTakesAndSoChecksACaller =
      methods()
          .that()
          .arePublic()
          .and()
          .areDeclaredInClassesThat()
          .haveSimpleNameEndingWith("Service")
          .and()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(
              "..serve.portfolio..",
              "..serve.squad..",
              "..serve.query..",
              "..serve.evidence..",
              "..serve.suppression..",
              "..serve.audit..")
          .should(TAKE_A_CALLER_FIRST);

  @ArchTest
  static final ArchRule readersNeverTouchThePseudonymDirectoryOrAnyWriteOperation =
      noClasses()
          .that()
          .resideInAnyPackage(
              "..serve.portfolio..",
              "..serve.squad..",
              "..serve.query..",
              "..serve.evidence..",
              "..serve.web..")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameContaining("PseudonymDirectory")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("IdentityPseudonymiser");

  @ArchTest
  static final ArchRule nothingOutsideTheAiAndServeZonesIsReachedForTheWrongWay =
      noClasses()
          .that()
          .resideInAPackage("com.debthunter.controlplane.serve..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.debthunter.controlplane.ingest..",
              "com.debthunter.controlplane.governance..",
              "com.debthunter.controlplane.egress..",
              "com.debthunter.controlplane.api..",
              "com.debthunter.controlplane.app..");

  /**
   * The controllers are under the application's component-scan root, so without the zone condition
   * a deployment that switched the zone off would still build them and fail for want of services.
   */
  @ArchTest
  static final ArchRule everyControllerFollowsTheServeZoneSwitch =
      com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
          .that()
          .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
          .should()
          .beAnnotatedWith(org.springframework.context.annotation.Conditional.class);

  @SuppressWarnings("unused")
  private static boolean unused(JavaClass c) {
    return c != null;
  }
}
