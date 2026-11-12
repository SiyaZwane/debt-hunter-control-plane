package com.debthunter.controlplane.egress.workitem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A local imitation of the parts of Jira's REST API (version 2) that the adapter uses. */
public final class FakeJiraServer implements AutoCloseable {

  /** A request received. */
  public record Request(String method, String path, String authorization, String body) {}

  /** An issue. */
  public static final class Issue {
    public final String key;
    public final String project;
    public final String summary;
    public final String description;
    public final List<String> labels;
    public volatile String status = "To Do";
    public final List<String> comments = new CopyOnWriteArrayList<>();

    Issue(String key, String project, String summary, String description, List<String> labels) {
      this.key = key;
      this.project = project;
      this.summary = summary;
      this.description = description;
      this.labels = labels;
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern LABEL = Pattern.compile("labels = \"([^\"]+)\"");
  private static final Pattern ISSUE = Pattern.compile("/rest/api/2/issue/([^/?]+)(/[a-z]+)?");
  private static final Map<String, String> TRANSITION_IDS =
      Map.of("To Do", "11", "On Hold", "21", "Done", "31");

  private final HttpServer server;
  public final List<Request> requests = new CopyOnWriteArrayList<>();
  public final Map<String, Issue> issues = new LinkedHashMap<>();

  /** When set, every request is answered with this status. */
  public volatile int forcedStatus;

  /** When set, every request is answered with a redirect to this address. */
  public volatile String redirectTo;

  /** Statuses the workflow lets an issue move to. */
  public volatile List<String> reachable = List.of("To Do", "On Hold", "Done");

  private final AtomicInteger numbers = new AtomicInteger(1);

  public FakeJiraServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  public URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void handle(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String path = ex.getRequestURI().getPath();
    String raw = ex.getRequestURI().getRawQuery();
    requests.add(
        new Request(
            ex.getRequestMethod(),
            path + (raw == null ? "" : "?" + raw),
            ex.getRequestHeaders().getFirst("Authorization"),
            body));
    if (redirectTo != null) {
      ex.getResponseHeaders().add("Location", redirectTo);
      reply(ex, 302, "");
      return;
    }
    if (forcedStatus != 0) {
      reply(ex, forcedStatus, "{}");
      return;
    }
    String method = ex.getRequestMethod();
    synchronized (this) {
      if (path.equals("/rest/api/2/search") && method.equals("GET")) {
        search(ex, raw);
      } else if (path.equals("/rest/api/2/issue") && method.equals("POST")) {
        create(ex, body);
      } else {
        Matcher m = ISSUE.matcher(path);
        if (m.matches()) {
          issue(ex, method, m.group(1), m.group(2) == null ? "" : m.group(2), body);
        } else {
          reply(ex, 404, "{}");
        }
      }
    }
  }

  private void search(HttpExchange ex, String rawQuery) throws IOException {
    String jql = "";
    for (String pair : rawQuery.split("&")) {
      if (pair.startsWith("jql=")) {
        jql = URLDecoder.decode(pair.substring(4), StandardCharsets.UTF_8);
      }
    }
    Matcher m = LABEL.matcher(jql);
    List<Object> found = new ArrayList<>();
    if (m.find()) {
      for (Issue i : issues.values()) {
        if (i.labels.contains(m.group(1))) {
          found.add(Map.of("key", i.key, "fields", Map.of("status", Map.of("name", i.status))));
        }
      }
    }
    reply(ex, 200, JSON.writeValueAsString(Map.of("issues", found)));
  }

  private void create(HttpExchange ex, String body) throws IOException {
    JsonNode fields = JSON.readTree(body).path("fields");
    List<String> labels = new ArrayList<>();
    fields.path("labels").forEach(n -> labels.add(n.asText()));
    String project = fields.path("project").path("key").asText();
    String key = project + "-" + numbers.getAndIncrement();
    issues.put(
        key,
        new Issue(
            key,
            project,
            fields.path("summary").asText(),
            fields.path("description").asText(),
            labels));
    reply(ex, 201, JSON.writeValueAsString(Map.of("id", "1", "key", key)));
  }

  private void issue(HttpExchange ex, String method, String key, String sub, String body)
      throws IOException {
    Issue issue = issues.get(key);
    if (issue == null) {
      reply(ex, 404, "{}");
      return;
    }
    if (sub.isEmpty() && method.equals("GET")) {
      reply(
          ex,
          200,
          JSON.writeValueAsString(
              Map.of("fields", Map.of("status", Map.of("name", issue.status)))));
    } else if (sub.equals("/transitions") && method.equals("GET")) {
      List<Object> list = new ArrayList<>();
      for (String to : reachable) {
        list.add(Map.of("id", TRANSITION_IDS.get(to), "to", Map.of("name", to)));
      }
      reply(ex, 200, JSON.writeValueAsString(Map.of("transitions", list)));
    } else if (sub.equals("/transitions") && method.equals("POST")) {
      String id = JSON.readTree(body).path("transition").path("id").asText();
      TRANSITION_IDS.forEach(
          (name, tid) -> {
            if (tid.equals(id)) {
              issue.status = name;
            }
          });
      reply(ex, 204, "");
    } else if (sub.equals("/comment") && method.equals("POST")) {
      ObjectNode node = (ObjectNode) JSON.readTree(body);
      issue.comments.add(node.path("body").asText());
      reply(ex, 201, "{}");
    } else {
      reply(ex, 404, "{}");
    }
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) {
      ex.getResponseBody().write(bytes);
    }
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
