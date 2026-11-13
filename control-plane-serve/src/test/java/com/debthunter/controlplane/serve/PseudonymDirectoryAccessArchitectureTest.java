package com.debthunter.controlplane.serve;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.pseudonymise.IdentityPseudonymiser;
import com.debthunter.controlplane.ingest.pseudonymise.ReIdentificationService;
import com.debthunter.controlplane.store.identity.PseudonymDirectory;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The pseudonym directory is reachable only through the pseudonymiser (write) and the audited
 * re-identification service (read). This is the same rule as the one in {@code control-plane-app},
 * run from the module that can see the serve layer, the AI module, the API and egress, which the
 * application does not yet run and so cannot see.
 */
class PseudonymDirectoryAccessArchitectureTest {

  private static JavaClasses production;

  @BeforeAll
  static void importProductionClasses() {
    production =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.debthunter.controlplane");
  }

  @Test
  void theRuleCanSeeTheModulesItIsAboutOrItWouldProveNothing() {
    assertThat(production.contain(com.debthunter.controlplane.serve.audit.AuditReadService.class))
        .isTrue();
    assertThat(production.contain(com.debthunter.controlplane.ai.guard.PromptGuard.class)).isTrue();
    assertThat(production.contain(com.debthunter.controlplane.egress.alert.SecurityAlertType.class))
        .isTrue();
    assertThat(production.contain(com.debthunter.controlplane.api.admin.AdminController.class))
        .isTrue();
  }

  @Test
  void nothingButTheTwoServicesTouchesTheDirectory() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(IdentityPseudonymiser.class.getName())
        .and()
        .doNotHaveFullyQualifiedName(ReIdentificationService.class.getName())
        .and()
        .resideOutsideOfPackage("com.debthunter.controlplane.store.identity..")
        .should()
        .dependOnClassesThat()
        .areAssignableTo(PseudonymDirectory.class)
        .because(
            "the directory is reachable only through the pseudonymiser and the audited service")
        .check(production);
  }
}
