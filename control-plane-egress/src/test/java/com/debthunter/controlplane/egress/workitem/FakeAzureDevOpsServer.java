package com.debthunter.controlplane.egress.workitem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A local imitation of the parts of the Azure DevOps work-item API that the adapter uses. */
public final class FakeAzureDevOpsServer implements AutoCloseable {

  /** A request received. */
  public record Request(
      String method, String path, String contentType, String authorization, String body) {}

  /** A work item. */
  public static final class WorkItem {
    public final int id;
    public final String project;
    public final Map<String, String> fields = new LinkedHashMap<>();
    public final List<String> comments = new CopyOnWriteArrayList<>();

    WorkItem(int id, String project) {
      this.id = id;
      this.project = project;
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern TAG = Pattern.compile("CONTAINS '([^']+)'");
  private static final Pattern ITEM = Pattern.compile("/([^/]+)/_apis/wit/workitems/(\\d+)");
  private static final Pattern COMMENTS =
      Pattern.compile("/([^/]+)/_apis/wit/workItems/(\\d+)/comments");

  private final HttpServer server;
  public final List<Request> requests = new CopyOnWriteArrayList<>();
  public final Map<Integer, WorkItem> items = new LinkedHashMap<>();

  /** When set, every request is answered with this status. */
  public volatile int forcedStatus;

  /** When set, every request is answered with a redirect to this address. */
  public volatile String redirectTo;

  private final AtomicInteger ids = new AtomicInteger(40);

  public FakeAzureDevOpsServer() {
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
    String path = ex.getRequestURI().getRawPath();
    requests.add(
        new Request(
            ex.getRequestMethod(),
            path
                + (ex.getRequestURI().getRawQuery() == null
                    ? ""
                    : "?" + ex.getRequestURI().getRawQuery()),
            ex.getRequestHeaders().getFirst("Content-Type"),
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
      Matcher comments = COMMENTS.matcher(path);
      Matcher item = ITEM.matcher(path);
      if (path.endsWith("/_apis/wit/wiql") && method.equals("POST")) {
        wiql(ex, body);
      } else if (path.contains("/_apis/wit/workitems/$Task") && method.equals("POST")) {
        create(ex, path, body);
      } else if (comments.matches() && method.equals("POST")) {
        WorkItem w = items.get(Integer.parseInt(comments.group(2)));
        if (w == null) {
          reply(ex, 404, "{}");
        } else {
          w.comments.add(JSON.readTree(body).path("text").asText());
          reply(ex, 200, "{}");
        }
      } else if (item.matches()) {
        WorkItem w = items.get(Integer.parseInt(item.group(2)));
        if (w == null) {
          reply(ex, 404, "{}");
        } else if (method.equals("GET")) {
          reply(ex, 200, view(w));
        } else if (method.equals("PATCH")) {
          apply(w, body);
          reply(ex, 200, view(w));
        } else {
          reply(ex, 405, "{}");
        }
      } else {
        reply(ex, 404, "{}");
      }
    }
  }

  private void wiql(HttpExchange ex, String body) throws IOException {
    Matcher m = TAG.matcher(JSON.readTree(body).path("query").asText());
    List<Object> found = new ArrayList<>();
    if (m.find()) {
      for (WorkItem w : items.values()) {
        if (w.fields.getOrDefault("System.Tags", "").contains(m.group(1))) {
          found.add(Map.of("id", w.id));
        }
      }
    }
    reply(ex, 200, JSON.writeValueAsString(Map.of("workItems", found)));
  }

  private void create(HttpExchange ex, String path, String body) throws IOException {
    String project = path.split("/")[1];
    WorkItem w = new WorkItem(ids.incrementAndGet(), project.replace("%20", " "));
    w.fields.put("System.State", "New");
    apply(w, body);
    items.put(w.id, w);
    reply(ex, 200, view(w));
  }

  private static void apply(WorkItem w, String patch) throws IOException {
    for (JsonNode op : JSON.readTree(patch)) {
      w.fields.put(op.path("path").asText().replace("/fields/", ""), op.path("value").asText());
    }
  }

  private static String view(WorkItem w) throws IOException {
    return JSON.writeValueAsString(Map.of("id", w.id, "fields", w.fields));
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      ex.getResponseBody().write(bytes);
    }
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
