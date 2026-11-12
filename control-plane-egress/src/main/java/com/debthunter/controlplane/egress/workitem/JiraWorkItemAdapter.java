package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Talks to Jira (REST API version 2). A route's target is the Jira project key. An item carries its
 * key as a label, which is how it is found again.
 *
 * <p>Jira projects name their workflow states themselves, so the three states are given as the
 * names this deployment's workflow uses; they must match the project's workflow, and a move to a
 * state the workflow does not allow from where the item is will be refused.
 *
 * <p>This adapter has been exercised against a local imitation of Jira, not against Jira.
 */
public final class JiraWorkItemAdapter implements WorkItemTracker {

  /**
   * The names of the three states in the Jira workflow.
   *
   * @param open the state of an item someone should work on
   * @param onHold the state of an item whose finding is suppressed
   * @param closed the state of an item whose finding is resolved
   */
  public record StateNames(String open, String onHold, String closed) {

    /** Validates the components. */
    public StateNames {
      Objects.requireNonNull(open, "open");
      Objects.requireNonNull(onHold, "onHold");
      Objects.requireNonNull(closed, "closed");
    }

    /** Jira's default workflow, with an "On Hold" state added. */
    public static StateNames defaults() {
      return new StateNames("To Do", "On Hold", "Done");
    }

    String of(WorkItemStatus status) {
      return switch (status) {
        case OPEN -> open;
        case ON_HOLD -> onHold;
        case CLOSED -> closed;
      };
    }

    WorkItemStatus statusOf(String name) {
      if (name == null) {
        return null;
      }
      for (WorkItemStatus s : WorkItemStatus.values()) {
        if (of(s).equalsIgnoreCase(name)) {
          return s;
        }
      }
      return null;
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9_-]{1,40}");
  private static final Pattern ISSUE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*-\\d+");

  private final URI base;
  private final TrackerHttp http;
  private final StateNames states;

  /**
   * Creates the adapter.
   *
   * @param base Jira's address, such as {@code https://acme.atlassian.net}
   * @param authorization supplies the full {@code Authorization} header value each time
   * @param timeout how long to wait for one call
   * @param states the names of the three states in the project workflow
   */
  public JiraWorkItemAdapter(
      URI base, Supplier<String> authorization, Duration timeout, StateNames states) {
    this.base = Objects.requireNonNull(base, "base");
    this.http = new TrackerHttp(timeout, Objects.requireNonNull(authorization, "authorization"));
    this.states = Objects.requireNonNull(states, "states");
  }

  @Override
  public Optional<TrackedItem> find(WorkItemRoute route, String key) {
    String project = project(route);
    String jql = "project = \"" + project + "\" AND labels = \"" + label(key) + "\"";
    TrackerHttp.Reply reply =
        http.send(
            "GET",
            TrackerHttp.resolve(
                base, "/rest/api/2/search?maxResults=2&fields=status&jql=" + TrackerHttp.enc(jql)),
            null,
            null);
    if (!reply.ok()) {
      throw TrackerHttp.rejected("The search", reply);
    }
    JsonNode issues = parse(reply).path("issues");
    if (!issues.isArray() || issues.isEmpty()) {
      return Optional.empty();
    }
    JsonNode issue = issues.get(0);
    String id = issue.path("key").asText("");
    return Optional.of(
        new TrackedItem(
            checkedId(id),
            browseUrl(id),
            states.statusOf(issue.path("fields").path("status").path("name").asText(null))));
  }

  @Override
  public TrackedItem create(WorkItemRoute route, WorkItemContent content) {
    String project = project(route);
    ObjectNode fields = JSON.createObjectNode();
    fields.putObject("project").put("key", project);
    fields.put("summary", Rendering.wiki(content.title()));
    fields.put("description", description(content));
    fields.putObject("issuetype").put("name", "Task");
    ArrayNode labels = fields.putArray("labels");
    labels.add(label(content.key()));
    labels.add("debt-hunter");
    ObjectNode body = JSON.createObjectNode();
    body.set("fields", fields);
    TrackerHttp.Reply reply =
        http.send(
            "POST",
            TrackerHttp.resolve(base, "/rest/api/2/issue"),
            "application/json",
            body.toString());
    if (!reply.ok()) {
      throw TrackerHttp.rejected("Creating the item", reply);
    }
    String id = checkedId(parse(reply).path("key").asText(""));
    return new TrackedItem(id, browseUrl(id), WorkItemStatus.OPEN);
  }

  @Override
  public void move(WorkItemRoute route, String itemId, WorkItemStatus to, String comment) {
    String id = checkedId(itemId);
    TrackerHttp.Reply current =
        http.send(
            "GET",
            TrackerHttp.resolve(base, "/rest/api/2/issue/" + id + "?fields=status"),
            null,
            null);
    if (!current.ok()) {
      throw TrackerHttp.rejected("Reading the item", current);
    }
    String now = parse(current).path("fields").path("status").path("name").asText(null);
    if (states.statusOf(now) != to) {
      transition(id, to);
    }
    ObjectNode body = JSON.createObjectNode();
    body.put("body", Rendering.wiki(comment));
    TrackerHttp.Reply added =
        http.send(
            "POST",
            TrackerHttp.resolve(base, "/rest/api/2/issue/" + id + "/comment"),
            "application/json",
            body.toString());
    if (!added.ok()) {
      throw TrackerHttp.rejected("Adding the comment", added);
    }
  }

  private void transition(String id, WorkItemStatus to) {
    TrackerHttp.Reply listed =
        http.send(
            "GET",
            TrackerHttp.resolve(base, "/rest/api/2/issue/" + id + "/transitions"),
            null,
            null);
    if (!listed.ok()) {
      throw TrackerHttp.rejected("Listing the transitions", listed);
    }
    String wanted = states.of(to);
    String transitionId = null;
    for (JsonNode t : parse(listed).path("transitions")) {
      if (wanted.equalsIgnoreCase(t.path("to").path("name").asText(""))) {
        transitionId = t.path("id").asText(null);
        break;
      }
    }
    if (transitionId == null) {
      throw new TrackerRejectedException(
          "The workflow has no transition to \"" + wanted + "\" from the item's current state");
    }
    ObjectNode body = JSON.createObjectNode();
    body.putObject("transition").put("id", transitionId);
    TrackerHttp.Reply done =
        http.send(
            "POST",
            TrackerHttp.resolve(base, "/rest/api/2/issue/" + id + "/transitions"),
            "application/json",
            body.toString());
    if (!done.ok()) {
      throw TrackerHttp.rejected("The transition", done);
    }
  }

  private static String description(WorkItemContent content) {
    StringBuilder b = new StringBuilder();
    b.append("Created by Debt Hunter. Its state follows the finding automatically.\n\n");
    for (WorkItemContent.Detail d : content.details()) {
      b.append("*")
          .append(d.label())
          .append(":* {{")
          .append(Rendering.wiki(d.value()))
          .append("}}\n");
    }
    if (content.runLink() != null) {
      b.append("\n[Open the run in Debt Hunter|").append(content.runLink()).append("]\n");
    }
    return b.toString();
  }

  private static String project(WorkItemRoute route) {
    if (!PROJECT.matcher(route.target()).matches()) {
      throw new TrackerRejectedException("The route's project is not a valid Jira project key");
    }
    return route.target();
  }

  private static String label(String key) {
    if (!key.matches("[A-Za-z0-9_-]{1,80}")) {
      throw new TrackerRejectedException("The item key is not label-safe");
    }
    return key;
  }

  private static String checkedId(String id) {
    if (!ISSUE.matcher(id).matches()) {
      throw new TrackerRejectedException("The tracker returned an item id of an unexpected form");
    }
    return id;
  }

  private String browseUrl(String id) {
    return TrackerHttp.resolve(base, "/browse/" + id).toString();
  }

  private static JsonNode parse(TrackerHttp.Reply reply) {
    try {
      return JSON.readTree(reply.body());
    } catch (IOException e) {
      throw new TrackerRejectedException("The tracker's answer could not be read");
    }
  }
}
