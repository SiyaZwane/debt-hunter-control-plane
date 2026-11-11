package com.debthunter.controlplane.analytics.coverage.estate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists a GitHub organisation's repositories through the REST API, following pagination.
 *
 * <p>Archived and disabled repositories are left out: nobody is expected to onboard them. A
 * pagination link that points at a different host is refused, so a hostile or misconfigured
 * response cannot make the provider send the token elsewhere.
 */
public final class GitHubEstateProvider implements SourceControlEstateProvider {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
  private static final int MAX_PAGES = 1000;

  private final URI apiUrl;
  private final String organization;
  private final String token;
  private final EstateHttp http;

  /**
   * Creates the provider.
   *
   * @param apiUrl the API's base URL, such as {@code https://api.github.com}
   * @param organization the organisation whose repositories are listed
   * @param token an access token able to read the organisation's repositories, or blank for
   *     unauthenticated access to public ones
   * @param timeout the connect and request timeout
   */
  public GitHubEstateProvider(URI apiUrl, String organization, String token, Duration timeout) {
    this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
    if (organization == null || organization.isBlank()) {
      throw new IllegalArgumentException("A GitHub organisation is required");
    }
    this.organization = organization;
    this.token = token == null ? "" : token;
    this.http = new EstateHttp(timeout);
  }

  @Override
  public List<String> repositories() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", "application/vnd.github+json");
    headers.put("X-GitHub-Api-Version", "2022-11-28");
    headers.put("User-Agent", "debt-hunter-control-plane");
    if (!token.isBlank()) {
      headers.put("Authorization", "Bearer " + token);
    }

    List<String> result = new ArrayList<>();
    URI next =
        URI.create(
            apiUrl.toString().replaceAll("/+$", "")
                + "/orgs/"
                + URLEncoder.encode(organization, StandardCharsets.UTF_8).replace("+", "%20")
                + "/repos?per_page=100&type=all");
    for (int page = 0; next != null; page++) {
      if (page >= MAX_PAGES) {
        throw new EstateUnavailableException("GitHub returned more pages than expected");
      }
      EstateHttp.Reply reply = http.get(next, headers, "GitHub");
      parse(reply.body(), result);
      next = nextLink(reply);
    }
    return result;
  }

  private static void parse(String body, List<String> out) {
    JsonNode array;
    try {
      array = JSON.readTree(body);
    } catch (IOException e) {
      throw new EstateUnavailableException("GitHub's response was not valid JSON", e);
    }
    if (array == null || !array.isArray()) {
      throw new EstateUnavailableException("GitHub's response was not a list of repositories");
    }
    for (JsonNode repo : array) {
      String name = repo.path("full_name").asText("");
      if (name.isBlank()) {
        throw new EstateUnavailableException("GitHub returned a repository with no name");
      }
      if (!repo.path("archived").asBoolean(false) && !repo.path("disabled").asBoolean(false)) {
        out.add(name);
      }
    }
  }

  private URI nextLink(EstateHttp.Reply reply) {
    String link =
        reply.headers().entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase("link"))
            .flatMap(e -> e.getValue().stream())
            .findFirst()
            .orElse("");
    Matcher matcher = NEXT.matcher(link);
    if (!matcher.find()) {
      return null;
    }
    URI next = URI.create(matcher.group(1));
    boolean sameHost =
        next.getScheme() != null
            && next.getScheme().equalsIgnoreCase(apiUrl.getScheme())
            && next.getHost() != null
            && next.getHost().equalsIgnoreCase(apiUrl.getHost())
            && next.getPort() == apiUrl.getPort();
    if (!sameHost) {
      throw new EstateUnavailableException(
          "GitHub's pagination pointed at a different host; refusing to send credentials there");
    }
    return next;
  }
}
