package com.debthunter.controlplane.analytics.coverage.estate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.testkit.FakeEstateServer;
import com.debthunter.controlplane.testkit.FakeEstateServer.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AzureReposEstateProviderTest {

  private static final String PAT = "azure-pat-secret-value";

  private final FakeEstateServer server = new FakeEstateServer();

  @AfterEach
  void stop() {
    server.close();
  }

  private AzureReposEstateProvider provider() {
    return new AzureReposEstateProvider(server.baseUrl(), "contoso", PAT, Duration.ofSeconds(5));
  }

  private static String repo(String project, String name, boolean disabled) {
    return "{\"name\":\"%s\",\"project\":{\"name\":\"%s\"},\"isDisabled\":%s}"
        .formatted(name, project, disabled);
  }

  private static String list(String... entries) {
    return "{\"count\":%d,\"value\":[%s]}".formatted(entries.length, String.join(",", entries));
  }

  @Test
  void listsRepositoriesAsProjectSlashName() {
    server.respondWith(
        r -> Response.json(list(repo("Payments", "api", false), repo("Web", "site", false))));

    assertThat(provider().repositories()).containsExactly("Payments/api", "Web/site");
  }

  @Test
  void disabledRepositoriesAreNotExpectedToBeOnboarded() {
    server.respondWith(r -> Response.json(list(repo("P", "live", false), repo("P", "off", true))));

    assertThat(provider().repositories()).containsExactly("P/live");
  }

  @Test
  void asksTheOrganisationsGitRepositoriesWithAPersonalAccessTokenAsBasicAuth() {
    server.respondWith(r -> Response.json(list()));

    provider().repositories();

    var request = server.requests().get(0);
    assertThat(request.path()).isEqualTo("/contoso/_apis/git/repositories");
    assertThat(request.query()).isEqualTo("api-version=7.1");
    String expected =
        Base64.getEncoder().encodeToString((":" + PAT).getBytes(StandardCharsets.UTF_8));
    assertThat(request.headers()).containsEntry("authorization", "Basic " + expected);
  }

  @Test
  void anOrganisationNameWithASpaceIsEncodedInThePath() {
    server.respondWith(r -> Response.json(list()));

    new AzureReposEstateProvider(server.baseUrl(), "my org", PAT, Duration.ofSeconds(5))
        .repositories();

    assertThat(server.requests().get(0).rawPath()).isEqualTo("/my%20org/_apis/git/repositories");
  }

  @Test
  void anEmptyListIsAnEmptyEstateNotAFailure() {
    server.respondWith(r -> Response.json(list()));

    assertThat(provider().repositories()).isEmpty();
  }

  @Test
  void rejectedCredentialsAndServerErrorsAreUnavailableAndNeverShowTheToken() {
    for (int status : new int[] {401, 403, 404, 500}) {
      server.respondWith(r -> Response.status(status));

      assertThatThrownBy(() -> provider().repositories())
          .isInstanceOf(EstateUnavailableException.class)
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain(PAT));
    }
  }

  @Test
  void aResponseThatIsNotARepositoryListIsUnavailableNotEmpty() {
    for (String body :
        new String[] {
          "<html>sign in</html>", "{\"value\":\"nope\"}", "{\"value\":[{\"name\":\"x\"}]}"
        }) {
      server.respondWith(r -> Response.json(body));

      assertThatThrownBy(() -> provider().repositories())
          .as(body)
          .isInstanceOf(EstateUnavailableException.class);
    }
  }

  @Test
  void aRedirectIsNotFollowed() {
    server.respondWith(
        r -> new Response(302, java.util.Map.of("Location", "http://127.0.0.1:1/x"), ""));

    assertThatThrownBy(() -> provider().repositories())
        .isInstanceOf(EstateUnavailableException.class)
        .hasMessageContaining("302");
  }

  @Test
  void aBlankOrganisationIsRefusedUpFront() {
    assertThatThrownBy(
            () -> new AzureReposEstateProvider(server.baseUrl(), "", PAT, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
