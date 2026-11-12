package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.testkit.TestJwtIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves the assembled application starts with valid configuration, and refuses to start without
 * any one of the things it must not run without: token verification, the system of record, and the
 * pseudonymisation key.
 */
@Tag("integration")
class ControlPlaneApplicationSmokeTest {

  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  private static ConfigurableApplicationContext start(AppProperties properties) {
    return new SpringApplicationBuilder(ControlPlaneApplication.class)
        .web(WebApplicationType.REACTIVE)
        .run(properties.args());
  }

  private static void assertRefusesToStart(AppProperties properties, String expectedMessage) {
    assertThatThrownBy(() -> start(properties).close())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .rootCause()
        .hasMessageContaining(expectedMessage);
  }

  @Test
  void theApplicationStartsWithItsConfiguredNameAndMigratesItsDatabase() {
    try (ConfigurableApplicationContext context = start(AppProperties.valid(ISSUER))) {
      assertThat(context.getEnvironment().getProperty("spring.application.name"))
          .isEqualTo("debt-hunter-control-plane");
      assertThat(context.getBean(javax.sql.DataSource.class)).isNotNull();
    }
  }

  @Test
  void theApplicationRefusesToStartWithoutTokenVerificationConfigured() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER).without("debthunter.controlplane.security.issuer"),
        "Token verification is not configured");
  }

  @Test
  void theApplicationRefusesToStartWithoutASystemOfRecord() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER).without("debthunter.controlplane.database.url"),
        "The system of record is not configured");
  }

  @Test
  void theApplicationRefusesToStartWithoutAPseudonymisationKey() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER).without("debthunter.controlplane.pseudonym.key"),
        "The pseudonymisation key is not configured");
  }

  @Test
  void theApplicationRefusesToStartWithAPseudonymisationKeyThatIsTooShort() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER)
            .with("debthunter.controlplane.pseudonym.key", "c2hvcnQ="), // "short"
        "at least 32 bytes");
  }

  @Test
  void theApplicationRefusesToStartWithoutAPseudonymDirectoryKey() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER).without("debthunter.controlplane.pseudonym.directory-key"),
        "The pseudonym directory key is not configured");
  }

  @Test
  void theApplicationRefusesToStartWithAPseudonymDirectoryKeyThatIsTooShort() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER)
            .with("debthunter.controlplane.pseudonym.directory-key", "c2hvcnQ="), // "short"
        "at least 32 bytes");
  }

  @Test
  void theDirectoryKeyMayNotBeReusedFromAnotherKey() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER)
            .with("debthunter.controlplane.pseudonym.directory-key", AppProperties.PSEUDONYM_KEY),
        "must differ");
    assertRefusesToStart(
        AppProperties.valid(ISSUER)
            .with("debthunter.controlplane.pseudonym.directory-key", AppProperties.INTAKE_KEY),
        "must differ");
  }

  @Test
  void theDirectoryIsInTheDatabaseSoWhatItHoldsSurvivesARestart() {
    AppProperties properties = AppProperties.valid(ISSUER);
    com.debthunter.controlplane.store.identity.Pseudonym alice =
        com.debthunter.controlplane.store.identity.Pseudonym.fromStored("a".repeat(64));

    try (ConfigurableApplicationContext first = start(properties)) {
      first
          .getBean(com.debthunter.controlplane.store.identity.PseudonymDirectory.class)
          .remember(alice, "alice@example.com");
    }
    try (ConfigurableApplicationContext second = start(properties)) {
      assertThat(
              second
                  .getBean(com.debthunter.controlplane.store.identity.PseudonymDirectory.class)
                  .lookup(alice))
          .contains("alice@example.com");
    }
  }

  @Test
  void theApplicationRefusesToStartIfTheDatabaseCannotBeReached() {
    assertThatThrownBy(
            () ->
                start(
                        AppProperties.valid(ISSUER)
                            .with(
                                "debthunter.controlplane.database.url",
                                "jdbc:postgresql://127.0.0.1:1/nope"))
                    .close())
        .isInstanceOf(RuntimeException.class);
  }
}
