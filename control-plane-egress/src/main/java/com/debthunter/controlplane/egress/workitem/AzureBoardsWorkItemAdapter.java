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
 * Talks to Azure Boards (Azure DevOps REST API 7.1). A route's target is the project name. An item
 * carries its key as a tag, which is how it is found again.
 *
 * <p>Work-item states depend on the project's process, so the three states are given as the names
 * this deployment's process uses; they must be states the process allows.
 *
 * <p>This adapter has been exercised against a local imitation of Azure DevOps, not against Azure
 * DevOps.
 */
public final class AzureBoardsWorkItemAdapter implements WorkItemTracker {

  /**
   * The names of the three states in the project's process.
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

    /** The Agile process, with an "On Hold" state assumed to have been added. */
    public static StateNames defaults() {
      return new StateNames("Active", "On Hold", "Closed");
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
  private static final String VERSION = "api-version=7.1";
  private static final String PATCH = "application/json-patch+json";
  private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}");
  private static final Pattern ID = Pattern.compile("\\d{1,12}");

  private final URI base;
  private final TrackerHttp http;
  private final StateNames states;

  /**
   * Creates the adapter.
   *
   * @param base the organisation's address, such as {@code https://dev.azure.com/acme}
   * @param authorization supplies the full {@code Authorization} header value each time
   * @param timeout how long to wait for one call
   * @param states the names of the three states in the project's process
   */
  public AzureBoardsWorkItemAdapter(
      URI base, Supplier<String> authorization, Duration timeout, StateNames states) {
    this.base = Objects.requireNonNull(base, "base");
    this.http = new TrackerHttp(timeout, Objects.requireNonNull(authorization, "authorization"));
    this.states = Objects.requireNonNull(states, "states");
  }

  @Override
  public Optional<TrackedItem> find(WorkItemRoute route, String key) {
    String project = project(route);
    ObjectNode query = JSON.createObjectNode();
    query.put(
        "query",
        "SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = @project"
            + " AND [System.Tags] CONTAINS '"
            + tag(key)
            + "'");
    TrackerHttp.Reply found =
        http.send(
            "POST",
            TrackerHttp.resolve(
                base, "/" + TrackerHttp.enc(project) + "/_apis/wit/wiql?" + VERSION),
            "application/json",
            query.toString());
    if (!found.ok()) {
      throw TrackerHttp.rejected("The search", found);
    }
    JsonNode items = parse(found).path("workItems");
    if (!items.isArray() || items.isEmpty()) {
      return Optional.empty();
    }
    String id = checkedId(items.get(0).path("id").asText(""));
    TrackerHttp.Reply item = read(project, id);
    return Optional.of(new TrackedItem(id, urlOf(project, id), states.statusOf(stateOf(item))));
  }

  @Override
  public TrackedItem create(WorkItemRoute route, WorkItemContent content) {
    String project = project(route);
    ArrayNode patch = JSON.createArrayNode();
    add(patch, "System.Title", Rendering.html(content.title()));
    add(patch, "System.Description", description(content));
    add(patch, "System.Tags", tag(content.key()) + "; debt-hunter");
    TrackerHttp.Reply created =
        http.send(
            "POST",
            TrackerHttp.resolve(
                base, "/" + TrackerHttp.enc(project) + "/_apis/wit/workitems/$Task?" + VERSION),
            PATCH,
            patch.toString());
    if (!created.ok()) {
      throw TrackerHttp.rejected("Creating the item", created);
    }
    String id = checkedId(parse(created).path("id").asText(""));
    return new TrackedItem(id, urlOf(project, id), WorkItemStatus.OPEN);
  }

  @Override
  public void move(WorkItemRoute route, String itemId, WorkItemStatus to, String comment) {
    String project = project(route);
    String id = checkedId(itemId);
    String now = stateOf(read(project, id));
    if (states.statusOf(now) != to) {
      ArrayNode patch = JSON.createArrayNode();
      add(patch, "System.State", states.of(to));
      TrackerHttp.Reply moved =
          http.send(
              "PATCH",
              TrackerHttp.resolve(
                  base,
                  "/" + TrackerHttp.enc(project) + "/_apis/wit/workitems/" + id + "?" + VERSION),
              PATCH,
              patch.toString());
      if (!moved.ok()) {
        throw TrackerHttp.rejected("The state change", moved);
      }
    }
    ObjectNode body = JSON.createObjectNode();
    body.put("text", Rendering.html(comment));
    TrackerHttp.Reply added =
        http.send(
            "POST",
            TrackerHttp.resolve(
                base,
                "/"
                    + TrackerHttp.enc(project)
                    + "/_apis/wit/workItems/"
                    + id
                    + "/comments?api-version=7.1-preview.4"),
            "application/json",
            body.toString());
    if (!added.ok()) {
      throw TrackerHttp.rejected("Adding the comment", added);
    }
  }

  private TrackerHttp.Reply read(String project, String id) {
    TrackerHttp.Reply item =
        http.send(
            "GET",
            TrackerHttp.resolve(
                base,
                "/"
                    + TrackerHttp.enc(project)
                    + "/_apis/wit/workitems/"
                    + id
                    + "?fields=System.State&"
                    + VERSION),
            null,
            null);
    if (!item.ok()) {
      throw TrackerHttp.rejected("Reading the item", item);
    }
    return item;
  }

  private static String stateOf(TrackerHttp.Reply item) {
    return parse(item).path("fields").path("System.State").asText(null);
  }

  private static void add(ArrayNode patch, String field, String value) {
    ObjectNode op = patch.addObject();
    op.put("op", "add");
    op.put("path", "/fields/" + field);
    op.put("value", value);
  }

  private static String description(WorkItemContent content) {
    StringBuilder b = new StringBuilder();
    b.append("<p>Created by Debt Hunter. Its state follows the finding automatically.</p><ul>");
    for (WorkItemContent.Detail d : content.details()) {
      b.append("<li><b>")
          .append(Rendering.html(d.label()))
          .append(":</b> <code>")
          .append(Rendering.html(d.value()))
          .append("</code></li>");
    }
    b.append("</ul>");
    if (content.runLink() != null) {
      b.append("<p><a href=\"")
          .append(Rendering.html(content.runLink()))
          .append("\">Open the run in Debt Hunter</a></p>");
    }
    return b.toString();
  }

  private static String project(WorkItemRoute route) {
    if (!PROJECT.matcher(route.target()).matches()) {
      throw new TrackerRejectedException("The route's project is not a valid project name");
    }
    return route.target();
  }

  private static String tag(String key) {
    if (!key.matches("[A-Za-z0-9_-]{1,80}")) {
      throw new TrackerRejectedException("The item key is not tag-safe");
    }
    return key;
  }

  private static String checkedId(String id) {
    if (!ID.matcher(id).matches()) {
      throw new TrackerRejectedException("The tracker returned an item id of an unexpected form");
    }
    return id;
  }

  private String urlOf(String project, String id) {
    return TrackerHttp.resolve(base, "/" + TrackerHttp.enc(project) + "/_workitems/edit/" + id)
        .toString();
  }

  private static JsonNode parse(TrackerHttp.Reply reply) {
    try {
      return JSON.readTree(reply.body());
    } catch (IOException e) {
      throw new TrackerRejectedException("The tracker's answer could not be read");
    }
  }
}
