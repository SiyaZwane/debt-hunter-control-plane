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
 * A {@link SourceHostClient} for Azure DevOps Services' Git REST API. Repositories are {@code
 * project/name}, in the configured organisation.
 *
 * <p>The personal access token goes only in an {@code Authorization} header to the configured host,
 * redirects are not followed, and no message contains it. <strong>Verified only against a local
 * imitation of the API that was written from the documentation</strong>, not against Azure DevOps:
 * treat it as unproven until it has been run against a real organisation.
 */
public final class AzureDevOpsSourceHostClient implements SourceHostClient {

  private static final Logger LOG = LoggerFactory.getLogger(AzureDevOpsSourceHostClient.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9._ -]+/[A-Za-z0-9._ -]+");
  private static final String API_VERSION = "api-version=7.1";
  private static final String NO_OBJECT = "0000000000000000000000000000000000000000";

  private final URI base;
  private final String organization;
  private final Supplier<String> token;
  private final SourceHostHttp http;

  /**
   * Creates the client.
   *
   * @param apiBase the service's address, for example {@code https://dev.azure.com}
   * @param organization the organisation
   * @param token supplies the personal access token; called for every request
   * @param timeout how long any one call may take
   */
  public AzureDevOpsSourceHostClient(
      URI apiBase, String organization, Supplier<String> token, Duration timeout) {
    if (organization == null || organization.isBlank()) {
      throw new IllegalArgumentException("An Azure DevOps organisation is required");
    }
    this.base = apiBase;
    this.organization = organization;
    this.token = token;
    this.http = new SourceHostHttp(timeout);
  }

  private Map<String, String> headers() {
    String credentials =
        Base64.getEncoder().encodeToString((":" + token.get()).getBytes(StandardCharsets.UTF_8));
    return Map.of(
        "Authorization", "Basic " + credentials,
        "Accept", "application/json",
        "User-Agent", "debt-hunter-control-plane");
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private String project(String repository) {
    if (!REPOSITORY.matcher(repository).matches()) {
      throw new SourceHostRejectedException("Not an Azure DevOps repository name");
    }
    return repository.substring(0, repository.indexOf('/'));
  }

  private String name(String repository) {
    return repository.substring(repository.indexOf('/') + 1);
  }

  private String repoPath(String repository) {
    return "/"
        + enc(organization)
        + "/"
        + enc(project(repository))
        + "/_apis/git/repositories/"
        + enc(name(repository));
  }

  private URI uri(String path, String query) {
    String root = base.toString().replaceAll("/+$", "");
    return URI.create(root + path + "?" + (query == null ? "" : query + "&") + API_VERSION);
  }

  private static JsonNode parse(SourceHostHttp.Reply reply) {
    try {
      return JSON.readTree(reply.body());
    } catch (IOException e) {
      throw new SourceHostRejectedException("The source host sent a reply that could not be read");
    }
  }

  private String repositoryId(String repository) {
    SourceHostHttp.Reply reply = http.send("GET", uri(repoPath(repository), null), headers(), null);
    if (reply.status() != 200) {
      throw SourceHostHttp.rejected("Reading the repository", reply);
    }
    return parse(reply).path("id").asText();
  }

  @Override
  public Optional<String> branchHead(String repository, String branch) {
    SourceHostHttp.Reply reply =
        http.send(
            "GET",
            uri(repoPath(repository) + "/refs", "filter=" + enc("heads/" + branch)),
            headers(),
            null);
    if (reply.status() != 200) {
      throw SourceHostHttp.rejected("Reading a branch", reply);
    }
    for (JsonNode ref : parse(reply).path("value")) {
      if (("refs/heads/" + branch).equals(ref.path("name").asText())) {
        return Optional.of(ref.path("objectId").asText());
      }
    }
    return Optional.empty();
  }

  @Override
  public boolean isProtected(String repository, String branch) {
    String id = repositoryId(repository);
    SourceHostHttp.Reply reply =
        http.send(
            "GET",
            uri(
                "/"
                    + enc(organization)
                    + "/"
                    + enc(project(repository))
                    + "/_apis/policy/configurations",
                "repositoryId=" + enc(id) + "&refName=" + enc("refs/heads/" + branch)),
            headers(),
            null);
    if (reply.status() != 200) {
      throw SourceHostHttp.rejected("Reading branch policies", reply);
    }
    for (JsonNode policy : parse(reply).path("value")) {
      if (policy.path("isEnabled").asBoolean(false)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void ensureBranchWithFile(
      String repository,
      String branch,
      String baseSha,
      String path,
      String content,
      String commitMessage) {
    String repoPath = repoPath(repository);
    String ref = "refs/heads/" + branch;

    Optional<String> head = branchHead(repository, branch);
    String tip = head.orElse(baseSha);
    if (head.isEmpty()) {
      ArrayNode updates = JSON.createArrayNode();
      updates.add(
          JSON.createObjectNode()
              .put("name", ref)
              .put("oldObjectId", NO_OBJECT)
              .put("newObjectId", baseSha));
      SourceHostHttp.Reply created =
          http.send("POST", uri(repoPath + "/refs", null), headers(), updates.toString());
      if (created.status() != 200) {
        throw SourceHostHttp.rejected("Creating a branch", created);
      }
    }

    SourceHostHttp.Reply existing =
        http.send(
            "GET",
            uri(
                repoPath + "/items",
                "path="
                    + enc("/" + path)
                    + "&includeContent=true&versionDescriptor.version="
                    + enc(branch)
                    + "&versionDescriptor.versionType=branch&$format=json"),
            headers(),
            null);
    String changeType = "add";
    if (existing.status() == 200) {
      if (content.equals(parse(existing).path("content").asText())) {
        return;
      }
      changeType = "edit";
    } else if (existing.status() != 404) {
      throw SourceHostHttp.rejected("Reading the proposal file", existing);
    }

    ObjectNode change = JSON.createObjectNode().put("changeType", changeType);
    change.set("item", JSON.createObjectNode().put("path", "/" + path));
    change.set(
        "newContent",
        JSON.createObjectNode().put("content", content).put("contentType", "rawtext"));
    ObjectNode commit = JSON.createObjectNode().put("comment", commitMessage);
    commit.set("changes", JSON.createArrayNode().add(change));
    ObjectNode push = JSON.createObjectNode();
    push.set(
        "refUpdates",
        JSON.createArrayNode()
            .add(JSON.createObjectNode().put("name", ref).put("oldObjectId", tip)));
    push.set("commits", JSON.createArrayNode().add(commit));
    SourceHostHttp.Reply pushed =
        http.send("POST", uri(repoPath + "/pushes", null), headers(), push.toString());
    if (pushed.status() != 201 && pushed.status() != 200) {
      throw SourceHostHttp.rejected("Writing the proposal file", pushed);
    }
  }

  private PullRequestRef ref(String repository, JsonNode pr) {
    long id = pr.path("pullRequestId").asLong();
    String url =
        base.toString().replaceAll("/+$", "")
            + "/"
            + enc(organization)
            + "/"
            + enc(project(repository))
            + "/_git/"
            + enc(name(repository))
            + "/pullrequest/"
            + id;
    return new PullRequestRef(url, id);
  }

  @Override
  public Optional<PullRequestRef> findOpenPullRequest(String repository, String headBranch) {
    SourceHostHttp.Reply reply =
        http.send(
            "GET",
            uri(
                repoPath(repository) + "/pullrequests",
                "searchCriteria.sourceRefName="
                    + enc("refs/heads/" + headBranch)
                    + "&searchCriteria.status=active"),
            headers(),
            null);
    if (reply.status() != 200) {
      throw SourceHostHttp.rejected("Looking for a pull request", reply);
    }
    JsonNode value = parse(reply).path("value");
    return value.isEmpty() ? Optional.empty() : Optional.of(ref(repository, value.get(0)));
  }

  @Override
  public PullRequestRef openDraftPullRequest(
      String repository,
      String headBranch,
      String baseBranch,
      String title,
      String body,
      List<String> labels) {
    ObjectNode request =
        JSON.createObjectNode()
            .put("sourceRefName", "refs/heads/" + headBranch)
            .put("targetRefName", "refs/heads/" + baseBranch)
            .put("title", title)
            .put("description", body)
            .put("isDraft", true);
    SourceHostHttp.Reply reply =
        http.send(
            "POST",
            uri(repoPath(repository) + "/pullrequests", null),
            headers(),
            request.toString());
    if (reply.status() != 201 && reply.status() != 200) {
      throw SourceHostHttp.rejected("Opening the pull request", reply);
    }
    PullRequestRef pr = ref(repository, parse(reply));
    for (String label : labels) {
      try {
        http.send(
            "POST",
            uri(repoPath(repository) + "/pullRequests/" + pr.number() + "/labels", null),
            headers(),
            JSON.createObjectNode().put("name", label).toString());
      } catch (SourceHostUnavailableException e) {
        LOG.warn("Could not label pull request {} (it was opened)", pr.number());
      }
    }
    return pr;
  }
}
