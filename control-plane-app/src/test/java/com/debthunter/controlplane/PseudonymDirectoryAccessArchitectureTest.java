package com.debthunter.controlplane;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.pseudonymise.IdentityPseudonymiser;
import com.debthunter.controlplane.ingest.pseudonymise.ReIdentificationService;
import com.debthunter.controlplane.store.identity.PseudonymDirectory;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The pseudonym directory is the one place a pseudonym turns back into a person, so reaching it is
 * a closed list: the pseudonymiser writes it, the audited re-identification service reads it, and
 * the two configuration classes that build them hand it over. Nothing else in the whole estate (the
 * serve layer, egress, analytics, the AI module) may depend on it, and no migration may reference
 * its table, so no view, copy or export can be built from it by accident. The application runs
 * every module, so this one test sees them all.
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
    assertThat(
            production.contain(
                com.debthunter.controlplane.analytics.readmodel.ReadModelRepository.class))
        .isTrue();
  }

  @Test
  void onlyTheTwoServicesAndTheirWiringTouchTheDirectory() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(IdentityPseudonymiser.class.getName())
        .and()
        .doNotHaveFullyQualifiedName(ReIdentificationService.class.getName())
        .and()
        .resideOutsideOfPackage("com.debthunter.controlplane.store.identity..")
        .and()
        .doNotHaveFullyQualifiedName(StoreConfiguration.class.getName())
        .and()
        .doNotHaveFullyQualifiedName(IngestPipelineConfiguration.class.getName())
        .should()
        .dependOnClassesThat()
        .areAssignableTo(PseudonymDirectory.class)
        .because(
            "the directory is reachable only through the pseudonymiser and the audited service")
        .check(production);
  }

  @Test
  void theDirectoryImplementationsAreBuiltOnlyByTheStoreConfiguration() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(StoreConfiguration.class.getName())
        .and()
        .resideOutsideOfPackage("com.debthunter.controlplane.store.identity..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.debthunter.controlplane.store.identity.JdbcPseudonymDirectory")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(
            "com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory")
        .check(production);
  }

  @Test
  void noMigrationReferencesTheDirectoryTableExceptItsOwn() throws IOException {
    List<String> referencing = new ArrayList<>();
    Resource[] migrations =
        new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql");
    for (Resource migration : migrations) {
      URL url = migration.getURL();
      String text = new String(migration.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (text.toLowerCase().contains("pseudonym_directory")) {
        referencing.add(migration.getFilename());
      }
      assertThat(url).isNotNull();
    }
    Collections.sort(referencing);

    assertThat(migrations.length).as("migrations found").isGreaterThan(10);
    assertThat(referencing).containsExactly("V14__pseudonym_directory.sql");
  }
}
