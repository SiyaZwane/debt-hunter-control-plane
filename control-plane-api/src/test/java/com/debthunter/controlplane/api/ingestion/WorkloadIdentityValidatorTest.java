package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.registry.InMemoryRepositoryScopeLookup;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkloadIdentityValidatorTest {

  private final WorkloadIdentityValidator validator =
      new WorkloadIdentityValidator(
          new InMemoryRepositoryScopeLookup(Map.of("svc-a", List.of("acme/widgets"))));

  @Test
  void anIdentityIsAllowedForARepositoryInItsScope() {
    assertThat(validator.validate("svc-a", "acme/widgets").allowed()).isTrue();
  }

  @Test
  void anIdentityIsRefusedForARepositoryOutsideItsScope() {
    ScopeDecision decision = validator.validate("svc-a", "acme/secret");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("outside");
  }

  @Test
  void anUnknownIdentityIsRefused() {
    assertThat(validator.validate("svc-unknown", "acme/widgets").allowed()).isFalse();
  }

  @Test
  void aMissingOrBlankIdentityIsRefused() {
    assertThat(validator.validate(null, "acme/widgets").allowed()).isFalse();
    assertThat(validator.validate("  ", "acme/widgets").allowed()).isFalse();
  }

  @Test
  void aMissingOrBlankRepositoryIsRefused() {
    assertThat(validator.validate("svc-a", null).allowed()).isFalse();
    assertThat(validator.validate("svc-a", "").allowed()).isFalse();
  }
}
