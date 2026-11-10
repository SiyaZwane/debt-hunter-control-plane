package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/** Proves the assembled application starts with valid configuration and refuses to without it. */
@SpringBootTest(
    properties = {
      "debthunter.controlplane.security.jwk-set-uri=http://127.0.0.1:1/jwks",
      "debthunter.controlplane.security.issuer=https://login.test/tenant/v2.0",
      "debthunter.controlplane.security.audience=api://debt-hunter-control-plane",
      "debthunter.controlplane.intake.bootstrap-servers=127.0.0.1:1"
    })
class ControlPlaneApplicationSmokeTest {

  @Autowired private Environment environment;

  @Test
  void theApplicationContextStartsWithItsConfiguredName() {
    assertThat(environment.getProperty("spring.application.name"))
        .isEqualTo("debt-hunter-control-plane");
  }

  @Test
  void theApplicationRefusesToStartWithoutTokenVerificationConfigured() {
    SpringApplicationBuilder unconfigured =
        new SpringApplicationBuilder(ControlPlaneApplication.class)
            .web(org.springframework.boot.WebApplicationType.REACTIVE)
            .properties(
                "server.port=0", "debthunter.controlplane.intake.bootstrap-servers=127.0.0.1:1");

    assertThatThrownBy(
            () -> {
              try (ConfigurableApplicationContext ignored = unconfigured.run()) {
                // never reached: start-up must fail
              }
            })
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .rootCause()
        .hasMessageContaining("Token verification is not configured");
  }
}
