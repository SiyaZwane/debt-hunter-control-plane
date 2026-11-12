package com.debthunter.controlplane.ai.remediation.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SourceHostClient} for GitHub's REST API. Repositories are {@code owner/name}.
 *
 * <p>The token goes only in an {@code Authorization} header to the configured host, redirects are
 * not followed, and no message contains it. <strong>Verified only against a local imitation of the
 * API</strong>, not against GitHub itself.
 */
public final class GitHubSourceHostClient implements SourceHostClient {

  private static final Logger LOG = LoggerFactory.getLogger(GitHubSourceHostClient.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

  private final URI api;
  private final Supplier<String> token;
  private final SourceHostHttp http;

  /**
   * Creates the client.
   *
   * @param apiBase the API's base address, for example {@code https://api.github.com}
   * @param token supplies the access token; called for every request so a rotated token is used
   * @param timeout how long any one call may take
   */
  public GitHubSourceHostClient(URI apiBase, Supplier<String> token, Duration timeout) {
    this.api = apiBase;
    this.token = token;
    this.http = new SourceHostHttp(timeout);
  }

  private Map<String, String> headers() {
    return Map.of(
        "Authorization", "Bearer " + token.get(),
        "Accept", "application/vnd.github+json",
        "X-GitHub-Api-Version", "2022-11-28",
        "User-Agent", "debt-hunter-control-plane");
  }

  private static String repo(String repository) {
    if (!REPOSITORY.matcher(repository).matches()) {
      throw new SourceHostRejectedException("Not a GitHub repository name");
    }
    return repository;
  }

  /** Encodes each path segment, keeping the slashes. */
  private static String path(String value) {
    StringBuilder out = new StringBuilder();
    for (String segment : value.split("/")) {
      if (out.length() > 0) {
        out.append('/');
      }
      out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out.toString();
  }

  private URI uri(String path, String query) {
    return URI.create(
        api.toString().replaceAll("/+$", "") + path + (query == null ? "" : "?" + query));
  }

  private static JsonNode parse(SourceHostHttp.Reply reply) {
    try {
      return JSON.readTree(reply.body());
    } catch (IOException e) {
      throw new SourceHostRejectedException("The source host sent a reply that could not be read");
    }
  }

  @Override
  public Optional<String> branchHead(String repository, String branch) {
    SourceHostHttp.Reply reply =
        http.send(
            "GET",
            uri("/repos/" + repo(repository) + "/git/ref/heads/" + path(branch), null),
            headers(),
            null);
    if (reply.status() == 404) {
      return Optional.empty();
    }
    if (reply.status() != 200) {
      throw SourceHostHttp.rejected("Reading a branch", reply);
    }
    String sha = parse(reply).path("object").path("sha").asText("");
    return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
  }

  @Override
  public boolean isProtected(String repository, String branch) {
    SourceHostHttp.Reply reply =
        http.send(
            "GET",
            uri("/repos/" + repo(repository) + "/branches/" + path(branch), null),
            headers(),
            null);
    if (reply.status() == 200 && parse(reply).path("protected").asBoolean(false)) {
      return true;
    }
    if (reply.status() != 200 && reply.status() != 404) {
      throw SourceHostHttp.rejected("Reading a branch's protection", reply);
    }
    // A ruleset can protect a branch name that does not exist yet.
    SourceHostHttp.Reply rules =
        http.send(
            "GET",
            uri("/repos/" + repo(repository) + "/rules/branches/" + path(branch), null),
            headers(),
            null);
    if (rules.status() == 404) {
      return false;
    }
    if (rules.status() != 200) {
      throw SourceHostHttp.rejected("Reading a branch's rules", rules);
    }
    JsonNode body = parse(rules);
    return body.isArray() && body.size() > 0;
  }

  @Override
  public void ensureBranchWithFile(
      String repository,
      String branch,
      String baseSha,
      String path,
      String content,
      String commitMessage) {
    String repo = repo(repository);
    ObjectNode ref = JSON.createObjectNode().put("ref", "refs/heads/" + branch).put("sha", baseSha);
    SourceHostHttp.Reply created =
        http.send("POST", uri("/repos/" + repo + "/git/refs", null), headers(), ref.toString());
    boolean alreadyThere = created.status() == 422 && created.body().contains("already exists");
    if (created.status() != 201 && !alreadyThere) {
      throw SourceHostHttp.rejected("Creating a branch", created);
    }

    SourceHostHttp.Reply existing =
        http.send(
            "GET",
            uri(
                "/repos/" + repo + "/contents/" + path(path),
                "ref=" + URLEncoder.encode(branch, StandardCharsets.UTF_8)),
            headers(),
            null);
    String existingSha = null;
    if (existing.status() == 200) {
      JsonNode file = parse(existing);
      String current = file.path("content").asText("").replace("\n", "");
      String wanted = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
      if (current.equals(wanted)) {
        return;
      }
      existingSha = file.path("sha").asText(null);
    } else if (existing.status() != 404) {
      throw SourceHostHttp.rejected("Reading the proposal file", existing);
    }

    ObjectNode put =
        JSON.createObjectNode()
            .put("message", commitMessage)
            .put(
                "content",
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)))
            .put("branch", branch);
    if (existingSha != null) {
      put.put("sha", existingSha);
    }
    SourceHostHttp.Reply written =
        http.send(
            "PUT",
            uri("/repos/" + repo + "/contents/" + path(path), null),
            headers(),
            put.toString());
    if (written.status() != 200 && written.status() != 201) {
      throw SourceHostHttp.rejected("Writing the proposal file", written);
    }
  }

  @Override
  public Optional<PullRequestRef> findOpenPullRequest(String repository, String headBranch) {
    String repo = repo(repository);
    String owner = repo.substring(0, repo.indexOf('/'));
    SourceHostHttp.Reply reply =
        http.send(
            "GET",
            uri(
                "/repos/" + repo + "/pulls",
                "state=open&head="
                    + URLEncoder.encode(owner + ":" + headBranch, StandardCharsets.UTF_8)),
            headers(),
            null);
    if (reply.status() != 200) {
      throw SourceHostHttp.rejected("Looking for a pull request", reply);
    }
    JsonNode list = parse(reply);
    if (!list.isArray() || list.isEmpty()) {
      return Optional.empty();
    }
    JsonNode first = list.get(0);
    return Optional.of(
        new PullRequestRef(first.path("html_url").asText(), first.path("number").asLong()));
  }

  @Override
  public PullRequestRef openDraftPullRequest(
      String repository,
      String headBranch,
      String baseBranch,
      String title,
      String body,
      List<String> labels) {
    String repo = repo(repository);
    ObjectNode request =
        JSON.createObjectNode()
            .put("title", title)
            .put("head", headBranch)
            .put("base", baseBranch)
            .put("body", body)
            .put("draft", true);
    SourceHostHttp.Reply reply =
        http.send("POST", uri("/repos/" + repo + "/pulls", null), headers(), request.toString());
    if (reply.status() != 201) {
      throw SourceHostHttp.rejected("Opening the pull request", reply);
    }
    JsonNode created = parse(reply);
    PullRequestRef pr =
        new PullRequestRef(created.path("html_url").asText(), created.path("number").asLong());
    if (!labels.isEmpty()) {
      ArrayNode names = JSON.createArrayNode();
      labels.forEach(names::add);
      try {
        http.send(
            "POST",
            uri("/repos/" + repo + "/issues/" + pr.number() + "/labels", null),
            headers(),
            JSON.createObjectNode().set("labels", names).toString());
      } catch (SourceHostUnavailableException e) {
        LOG.warn("Could not label pull request {} (it was opened)", pr.number());
      }
    }
    return pr;
  }
}
