package com.debthunter.controlplane.ai;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.debthunter.controlplane.ai.explain.Explanation;
import com.debthunter.controlplane.ai.explain.Provenance;
import com.debthunter.controlplane.ai.explain.ProvenanceLabeller;
import com.debthunter.controlplane.ai.guard.GuardedPrompt;
import com.debthunter.controlplane.ai.guard.ModelClient;
import com.debthunter.controlplane.ai.guard.PromptGuard;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

/** Nothing reaches a model except through the guard, and no explanation exists unlabelled. */
@AnalyzeClasses(
    packages = "com.debthunter.controlplane",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ModelAccessArchitectureTest {

  @ArchTest
  static final ArchRule onlyThePromptGuardCallsTheModel =
      noClasses()
          .that()
          .areNotAssignableTo(PromptGuard.class)
          .should()
          .callMethod(ModelClient.class, "complete", GuardedPrompt.class)
          .because("every dispatch must be decided, recorded and sent by the guard");

  @ArchTest
  static final ArchRule onlyThePromptGuardMakesGuardedPrompts =
      noClasses()
          .that()
          .areNotAssignableTo(PromptGuard.class)
          .should()
          .callConstructor(GuardedPrompt.class, String.class, Set.class, boolean.class);

  @ArchTest
  static final ArchRule onlyTheLabellerMakesExplanations =
      noClasses()
          .that()
          .areNotAssignableTo(ProvenanceLabeller.class)
          .should()
          .callConstructor(
              Explanation.class,
              String.class,
              String.class,
              String.class,
              Provenance.class,
              boolean.class);

  @ArchTest
  static final ArchRule theGuardDoesNotDependOnTheExplanationLayer =
      noClasses()
          .that()
          .resideInAPackage("..ai.guard..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..ai.explain..");
}
