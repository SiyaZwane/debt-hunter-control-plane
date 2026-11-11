package com.debthunter.controlplane.analytics.coverage.estate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lists an Azure DevOps organisation's Git repositories through the REST API. Repositories are
 * identified as {@code project/name}. Disabled repositories are left out.
 */
public final class AzureReposEstateProvider implements SourceControlEstateProvider {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final URI apiUrl;
  private final String organization;
  private final String personalAccessToken;
  private final EstateHttp http;

  /**
   * Creates the provider.
   *
   * @param apiUrl the base URL, such as {@code https://dev.azure.com}
   * @param organization the Azure DevOps organisation
   * @param personalAccessToken a token able to read repositories
   * @param timeout the connect and request timeout
   */
  public AzureReposEstateProvider(
      URI apiUrl, String organization, String personalAccessToken, Duration timeout) {
    this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
    if (organization == null || organization.isBlank()) {
      throw new IllegalArgumentException("An Azure DevOps organisation is required");
    }
    this.organization = organization;
    this.personalAccessToken = personalAccessToken == null ? "" : personalAccessToken;
    this.http = new EstateHttp(timeout);
  }

  @Override
  public List<String> repositories() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", "application/json");
    headers.put("User-Agent", "debt-hunter-control-plane");
    if (!personalAccessToken.isBlank()) {
      // Azure DevOps takes a personal access token as the password of an empty user name.
      headers.put(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString((":" + personalAccessToken).getBytes(StandardCharsets.UTF_8)));
    }
    URI uri =
        URI.create(
            apiUrl.toString().replaceAll("/+$", "")
                + "/"
                + URLEncoder.encode(organization, StandardCharsets.UTF_8).replace("+", "%20")
                + "/_apis/git/repositories?api-version=7.1");
    EstateHttp.Reply reply = http.get(uri, headers, "Azure DevOps");

    JsonNode root;
    try {
      root = JSON.readTree(reply.body());
    } catch (IOException e) {
      throw new EstateUnavailableException("Azure DevOps' response was not valid JSON", e);
    }
    if (root == null || !root.path("value").isArray()) {
      throw new EstateUnavailableException("Azure DevOps' response was not a list of repositories");
    }
    List<String> result = new ArrayList<>();
    for (JsonNode repo : root.path("value")) {
      String project = repo.path("project").path("name").asText("");
      String name = repo.path("name").asText("");
      if (project.isBlank() || name.isBlank()) {
        throw new EstateUnavailableException("Azure DevOps returned a repository with no name");
      }
      if (!repo.path("isDisabled").asBoolean(false)) {
        result.add(project + "/" + name);
      }
    }
    return result;
  }
}
