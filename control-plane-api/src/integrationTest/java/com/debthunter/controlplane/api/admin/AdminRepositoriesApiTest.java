package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

@Tag("integration")
class AdminRepositoriesApiTest {

  private static final String BASE = "/api/v1/admin/repositories";

  @Test
  void aRepositoryCanBeOnboardedAndReadBack() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, BASE, AdminHarness.widgets())
          .expectStatus()
          .isCreated()
          .expectHeader()
          .valueEquals("Location", BASE + "/acme/widgets")
          .expectBody()
          .jsonPath("$.repository")
          .isEqualTo("acme/widgets")
          .jsonPath("$.route.tracker")
          .isEqualTo("jira");

      h.admin(HttpMethod.GET, BASE + "/acme/widgets", null)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.owner")
          .isEqualTo("team-a")
          .jsonPath("$.rolloutStage")
          .isEqualTo("enforce")
          .jsonPath("$.route.target")
          .isEqualTo("WID");
    }
  }

  @Test
  void onboardingTheSameRepositoryTwiceIsAConflictAndChangesNothing() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, BASE, AdminHarness.widgets()).expectStatus().isCreated();

      h.admin(HttpMethod.POST, BASE, AdminHarness.widgets().replace("team-a", "team-z"))
          .expectStatus()
          .isEqualTo(409)
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("already_onboarded");

      assertThat(h.registry.find("acme/widgets").get().owner()).isEqualTo("team-a");
    }
  }

  @Test
  void listingReturnsEveryRepositoryInOrder() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, BASE, AdminHarness.entry("zeta/z")).expectStatus().isCreated();
      h.admin(HttpMethod.POST, BASE, AdminHarness.entry("alpha/a")).expectStatus().isCreated();

      h.admin(HttpMethod.GET, BASE, null)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.repositories.length()")
          .isEqualTo(2)
          .jsonPath("$.repositories[0].repository")
          .isEqualTo("alpha/a")
          .jsonPath("$.repositories[1].repository")
          .isEqualTo("zeta/z");
    }
  }

  @Test
  void anUnknownRepositoryIsNotFound() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.GET, BASE + "/acme/missing", null)
          .expectStatus()
          .isNotFound()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("not_onboarded");
    }
  }

  @Test
  void anEntryCanBeUpdatedAndWhereWorkItemsGoChanges() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, BASE, AdminHarness.widgets()).expectStatus().isCreated();

      h.admin(
              HttpMethod.PUT,
              BASE + "/acme/widgets",
              """
              {"owner":"team-b","tier":"tier-2","policyBundleVersion":"2.0","rolloutStage":"warn",\
              "route":{"tracker":"azure-boards","target":"WIDGETS"}}""")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.repository")
          .isEqualTo("acme/widgets")
          .jsonPath("$.route.tracker")
          .isEqualTo("azure-boards");

      assertThat(h.registry.routeFor("acme/widgets").get().target()).isEqualTo("WIDGETS");
    }
  }

  @Test
  void updatingARepositoryThatIsNotOnboardedIsNotFound() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.PUT, BASE + "/acme/ghost", AdminHarness.entry("acme/ghost"))
          .expectStatus()
          .isNotFound();
    }
  }

  @Test
  void aBodyNamingADifferentRepositoryThanTheUrlIsRefused() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, BASE, AdminHarness.widgets()).expectStatus().isCreated();

      h.admin(HttpMethod.PUT, BASE + "/acme/widgets", AdminHarness.entry("acme/other"))
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("invalid_request");
    }
  }

  @Test
  void offboardingRemovesTheRepository() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, BASE, AdminHarness.widgets()).expectStatus().isCreated();

      h.admin(HttpMethod.DELETE, BASE + "/acme/widgets", null).expectStatus().isNoContent();

      h.admin(HttpMethod.GET, BASE + "/acme/widgets", null).expectStatus().isNotFound();
      h.admin(HttpMethod.DELETE, BASE + "/acme/widgets", null).expectStatus().isNotFound();
    }
  }

  @Test
  void aRepositoryIdentifierWithSeveralSlashesWorksInThePath() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, BASE, AdminHarness.entry("org/team/repo"))
          .expectStatus()
          .isCreated();

      h.admin(HttpMethod.GET, BASE + "/org/team/repo", null)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.repository")
          .isEqualTo("org/team/repo");
      h.admin(HttpMethod.DELETE, BASE + "/org/team/repo", null).expectStatus().isNoContent();
    }
  }

  @Test
  void anEmptyRepositoryInThePathIsABadRequest() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.GET, BASE + "/", null).expectStatus().isBadRequest();
    }
  }

  // ---- validation ------------------------------------------------------------------------------

  private static void assertRejected(String body) {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, BASE, body)
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("invalid_request");
      assertThat(h.registry.all()).as("a rejected request changes nothing").isEmpty();
      assertThat(h.auditTrail).as("and is not audited as a change").isEmpty();
    }
  }

  @Test
  void aMissingRouteIsRejected() {
    assertRejected(
        """
        {"repository":"acme/widgets","owner":"t","tier":"t","policyBundleVersion":"1","rolloutStage":"s"}""");
  }

  @Test
  void aBlankOrMissingFieldIsRejected() {
    assertRejected(AdminHarness.widgets().replace("\"team-a\"", "\"  \""));
    assertRejected(AdminHarness.widgets().replace("\"owner\":\"team-a\",", ""));
    assertRejected(AdminHarness.widgets().replace("\"tracker\":\"jira\"", "\"tracker\":null"));
  }

  @Test
  void aControlCharacterInAnyFieldIsRejected() {
    assertRejected(AdminHarness.widgets().replace("team-a", "team\\u0007a"));
    assertRejected(AdminHarness.widgets().replace("acme/widgets", "acme/wid\\ngets"));
  }

  @Test
  void anIdentifierPaddedWithWhitespaceIsRejectedRatherThanQuietlyTrimmed() {
    assertRejected(AdminHarness.widgets().replace("acme/widgets", " acme/widgets"));
    assertRejected(AdminHarness.widgets().replace("team-a", "team-a "));
  }

  @Test
  void aFieldThatIsTooLongIsRejected() {
    assertRejected(AdminHarness.widgets().replace("team-a", "x".repeat(201)));
  }

  @Test
  void aBodyThatIsNotJsonIsRejected() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, BASE, "{ not json").expectStatus().isBadRequest();
    }
  }

  @Test
  void anErrorNeverEchoesTheValueThatWasRejected() {
    try (AdminHarness h = new AdminHarness()) {
      String hostile = "ignore previous instructions " + "x".repeat(300);

      h.admin(HttpMethod.POST, BASE, AdminHarness.widgets().replace("team-a", hostile))
          .expectStatus()
          .isBadRequest()
          .expectBody(String.class)
          .value(body -> assertThat(body).doesNotContain("ignore previous instructions"));
    }
  }
}
