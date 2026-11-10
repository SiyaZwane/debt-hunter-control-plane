package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Proves the assembled Spring Boot application context starts and picks up its configuration. */
@SpringBootTest
class ControlPlaneApplicationSmokeTest {

  @Autowired private Environment environment;

  @Test
  void theApplicationContextStartsWithItsConfiguredName() {
    assertThat(environment.getProperty("spring.application.name"))
        .isEqualTo("debt-hunter-control-plane");
  }
}
