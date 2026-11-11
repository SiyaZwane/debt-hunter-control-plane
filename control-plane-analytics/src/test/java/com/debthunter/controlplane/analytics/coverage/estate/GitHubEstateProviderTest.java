package com.debthunter.controlplane.analytics.coverage.estate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.testkit.FakeEstateServer;
import com.debthunter.controlplane.testkit.FakeEstateServer.Response;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubEstateProviderTest {

  private static final String TOKEN = "ghp_super_secret_token_value";

  private final FakeEstateServer server = new FakeEstateServer();

  @AfterEach
  void stop() {
    server.close();
  }

  private GitHubEstateProvider provider(String token) {
    return new GitHubEstateProvider(server.baseUrl(), "acme", token, Duration.ofSeconds(5));
  }

  private static String repos(String... entries) {
    return "[" + String.join(",", entries) + "]";
  }

  private static String repo(String fullName, boolean archived, boolean disabled) {
    return "{\"full_name\":\"%s\",\"archived\":%s,\"disabled\":%s}"
        .formatted(fullName, archived, disabled);
  }

  @Test
  void listsTheOrganisationsRepositoriesByFullName() {
    server.respondWith(
        r -> Response.json(repos(repo("acme/a", false, false), repo("acme/b", false, false))));

    assertThat(provider(TOKEN).repositories()).containsExactly("acme/a", "acme/b");
  }

  @Test
  void asksTheOrganisationsRepositoriesWithTheTokenAndTheDocumentedHeaders() {
    server.respondWith(r -> Response.json("[]"));

    provider(TOKEN).repositories();

    var request = server.requests().get(0);
    assertThat(request.path()).isEqualTo("/orgs/acme/repos");
    assertThat(request.query()).contains("per_page=100").contains("type=all");
    assertThat(request.headers()).containsEntry("authorization", "Bearer " + TOKEN);
    assertThat(request.headers()).containsEntry("accept", "application/vnd.github+json");
    assertThat(request.headers()).containsEntry("x-github-api-version", "2022-11-28");
  }

  @Test
  void sendsNoAuthorizationHeaderWithoutAToken() {
    server.respondWith(r -> Response.json("[]"));

    provider("").repositories();

    assertThat(server.requests().get(0).headers()).doesNotContainKey("authorization");
  }

  @Test
  void archivedAndDisabledRepositoriesAreNotExpectedToBeOnboarded() {
    server.respondWith(
        r ->
            Response.json(
                repos(
                    repo("acme/live", false, false),
                    repo("acme/old", true, false),
                    repo("acme/off", false, true))));

    assertThat(provider(TOKEN).repositories()).containsExactly("acme/live");
  }

  @Test
  void followsPaginationUntilThereIsNoNextPage() {
    server.respondWith(
        r -> {
          if (r.query().contains("page=2")) {
            return Response.json(repos(repo("acme/two", false, false)));
          }
          String next =
              "<"
                  + server.baseUrl()
                  + "/orgs/acme/repos?per_page=100&type=all&page=2>; rel=\"next\"";
          return new Response(
              200,
              Map.of("Content-Type", "application/json", "Link", next),
              repos(repo("acme/one", false, false)));
        });

    assertThat(provider(TOKEN).repositories()).containsExactly("acme/one", "acme/two");
    assertThat(server.requests()).hasSize(2);
  }

  @Test
  void refusesAPaginationLinkToAnotherHostSoTheTokenIsNeverSentThere() {
    try (FakeEstateServer elsewhere = new FakeEstateServer()) {
      elsewhere.respondWith(r -> Response.json("[]"));
      server.respondWith(
          r ->
              new Response(
                  200,
                  Map.of(
                      "Link", "<" + elsewhere.baseUrl() + "/orgs/acme/repos?page=2>; rel=\"next\""),
                  repos(repo("acme/one", false, false))));

      assertThatThrownBy(() -> provider(TOKEN).repositories())
          .isInstanceOf(EstateUnavailableException.class)
          .hasMessageContaining("different host");
      assertThat(elsewhere.requests()).as("nothing was sent to the other host").isEmpty();
    }
  }

  @Test
  void aRejectedOrForbiddenTokenIsUnavailableAndTheMessageNeverContainsIt() {
    for (int status : new int[] {401, 403}) {
      server.respondWith(r -> Response.status(status));

      assertThatThrownBy(() -> provider(TOKEN).repositories())
          .isInstanceOf(EstateUnavailableException.class)
          .hasMessageContaining("credentials")
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    }
  }

  @Test
  void anUnknownOrganisationIsUnavailable() {
    server.respondWith(r -> Response.status(404));

    assertThatThrownBy(() -> provider(TOKEN).repositories())
        .isInstanceOf(EstateUnavailableException.class)
        .hasMessageContaining("404");
  }

  @Test
  void aServerErrorOrRateLimitIsUnavailable() {
    for (int status : new int[] {429, 500, 503}) {
      server.respondWith(r -> Response.status(status));

      assertThatThrownBy(() -> provider(TOKEN).repositories())
          .isInstanceOf(EstateUnavailableException.class)
          .hasMessageContaining(String.valueOf(status));
    }
  }

  @Test
  void aRedirectIsNotFollowedBecauseItCouldCarryTheTokenElsewhere() {
    server.respondWith(r -> new Response(302, Map.of("Location", "http://127.0.0.1:1/steal"), ""));

    assertThatThrownBy(() -> provider(TOKEN).repositories())
        .isInstanceOf(EstateUnavailableException.class)
        .hasMessageContaining("302");
  }

  @Test
  void aResponseThatIsNotARepositoryListIsUnavailableNotEmpty() {
    for (String body :
        new String[] {"not json", "{\"message\":\"hi\"}", "[{\"archived\":false}]"}) {
      server.respondWith(r -> Response.json(body));

      assertThatThrownBy(() -> provider(TOKEN).repositories())
          .as(body)
          .isInstanceOf(EstateUnavailableException.class);
    }
  }

  @Test
  void aServerThatIsNotThereIsUnavailable() {
    var gone = new GitHubEstateProvider(server.baseUrl(), "acme", TOKEN, Duration.ofSeconds(2));
    server.close();

    assertThatThrownBy(gone::repositories)
        .isInstanceOf(EstateUnavailableException.class)
        .hasMessageContaining("could not be reached");
  }

  @Test
  void aServerThatDoesNotAnswerInTimeIsUnavailableRatherThanHangingTheReconciliation() {
    server.respondWith(
        r -> {
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return Response.json("[]");
        });
    var impatient =
        new GitHubEstateProvider(server.baseUrl(), "acme", TOKEN, Duration.ofMillis(300));

    assertThatThrownBy(impatient::repositories).isInstanceOf(EstateUnavailableException.class);
  }

  @Test
  void theOrganisationNameIsEncodedInThePath() {
    server.respondWith(r -> Response.json("[]"));

    new GitHubEstateProvider(server.baseUrl(), "a b/c", TOKEN, Duration.ofSeconds(5))
        .repositories();

    assertThat(server.requests().get(0).rawPath()).isEqualTo("/orgs/a%20b%2Fc/repos");
  }

  @Test
  void aBlankOrganisationIsRefusedUpFront() {
    assertThatThrownBy(
            () -> new GitHubEstateProvider(server.baseUrl(), " ", TOKEN, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
