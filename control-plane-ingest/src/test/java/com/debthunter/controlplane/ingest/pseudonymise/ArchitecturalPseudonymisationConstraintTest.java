package com.debthunter.controlplane.ingest.pseudonymise;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-09, enforced structurally: nothing can put contributor identity into a store, because the
 * only things a store accepts are built from {@code Pseudonym}s and only {@code
 * IdentityPseudonymiser} can make one from an identity.
 */
class ArchitecturalPseudonymisationConstraintTest {

  private static JavaClasses production;

  @BeforeAll
  static void importProductionClasses() {
    production =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.debthunter.controlplane");
  }

  @Test
  void onlyTheIdentityPseudonymiserMayIssueAPseudonym() {
    ArchRule rule =
        noClasses()
            .that()
            .doNotHaveFullyQualifiedName(IdentityPseudonymiser.class.getName())
            .and()
            .doNotHaveFullyQualifiedName("com.debthunter.controlplane.store.identity.Pseudonym")
            .should()
            .callMethod(
                com.debthunter.controlplane.store.identity.Pseudonym.class, "issue", String.class)
            .because("a Pseudonym is how a store knows it is not being handed a raw identity");

    rule.check(production);
  }

  @Test
  void onlyStorageMayRestoreAPseudonymFromText() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "com.debthunter.controlplane.store..",
                "com.debthunter.controlplane.ingest.pseudonymise..")
            .should()
            .callMethod(
                com.debthunter.controlplane.store.identity.Pseudonym.class,
                "fromStored",
                String.class);

    rule.check(production);
  }

  @Test
  void theStoreCannotSeeAnythingFromIngestSoItCannotBeHandedAnUnpseudonymisedBundle() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.debthunter.controlplane.store..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.debthunter.controlplane.ingest..")
            .because(
                "the store accepts only types built from Pseudonyms, and ingest owns the rest");

    rule.check(production);
  }

  @Test
  void nothingBeforeTheStorageStageMayWriteToTheFindingStore() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "com.debthunter.controlplane.store..",
                "com.debthunter.controlplane.ingest.pseudonymise..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(
                com.debthunter.controlplane.store.findings.PseudonymisedFindingStore.class)
            .because("only the pseudonymised stage persists findings");

    rule.check(production);
  }

  @Test
  void thePseudonymisingGateIsTheOnlyNormalisedRunSinkThatReachesTheStore() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.debthunter.controlplane.ingest.normalise..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.debthunter.controlplane.store.findings..",
                "com.debthunter.controlplane.ingest.pseudonymise..")
            .because("normalisation happens before pseudonymisation and must not persist");

    rule.check(production);
  }

  @Test
  void anythingTheStoreHoldsThatIsNamedForAPersonIsAPseudonym() {
    ArchCondition<JavaField> involvePseudonym =
        new ArchCondition<>("be a Pseudonym, or hold only Pseudonyms") {
          @Override
          public void check(JavaField field, ConditionEvents events) {
            boolean ok =
                field.getType().getAllInvolvedRawTypes().stream()
                    .anyMatch(
                        t ->
                            t.isEquivalentTo(
                                com.debthunter.controlplane.store.identity.Pseudonym.class));
            if (!ok) {
              events.add(
                  SimpleConditionEvent.violated(
                      field,
                      field.getFullName() + " is named for a person but is not a Pseudonym"));
            }
          }
        };

    ArchRule rule =
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("com.debthunter.controlplane.store..")
            .and()
            .haveNameMatching("(?i).*(contributor|author|committer|email).*")
            .should(involvePseudonym)
            .because("a store field named for a person may only ever hold a pseudonym");

    rule.check(production);
  }
}
