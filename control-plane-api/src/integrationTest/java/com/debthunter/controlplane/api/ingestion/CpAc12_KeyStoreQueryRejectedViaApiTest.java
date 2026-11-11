package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-12: the pseudonym key, and the directory that maps pseudonyms back to people, cannot be
 * queried through the API. There is no such route; this guards the surface so that adding one is a
 * deliberate act that has to change this test.
 *
 * <p>The API today is the ingestion edge alone. When the query API arrives (CP-FR-15/16) this test
 * should grow a sweep over every route it registers.
 */
@Tag("integration")
class CpAc12_KeyStoreQueryRejectedViaApiTest {

  private static final String[] PATHS = {
    "/api/v1/pseudonym-key",
    "/api/v1/pseudonym-keys",
    "/api/v1/key-store",
    "/api/v1/keystore",
    "/api/v1/pseudonyms",
    "/api/v1/pseudonyms/" + "a".repeat(64),
    "/api/v1/re-identify",
    "/api/v1/identities",
    "/actuator/env",
    "/actuator/configprops",
  };

  @Test
  void cpAc12_anUnauthenticatedQueryIsRejected() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      for (String path : PATHS) {
        harness.client.get().uri(path).exchange().expectStatus().isUnauthorized();
      }
    }
  }

  @Test
  void cpAc12_anAuthenticatedQueryStillFindsNothingToQuery() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      String token = harness.tokenFor(IngestionHarness.SERVICE);
      for (String path : PATHS) {
        for (String method : new String[] {"GET", "POST"}) {
          int status =
              harness
                  .client
                  .method(org.springframework.http.HttpMethod.valueOf(method))
                  .uri(path)
                  .header("Authorization", "Bearer " + token)
                  .exchange()
                  .returnResult(String.class)
                  .getStatus()
                  .value();
          assertThat(status).as(method + " " + path).isIn(403, 404, 405);
        }
      }
    }
  }
}
