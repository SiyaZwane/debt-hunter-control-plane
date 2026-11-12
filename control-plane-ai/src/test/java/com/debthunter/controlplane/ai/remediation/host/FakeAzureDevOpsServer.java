package com.debthunter.controlplane.ai.remediation.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local imitation of the parts of the Azure DevOps Git API that remediation uses, written from
 * the documentation. It proves the adapter is self-consistent; it does <em>not</em> prove the
 * adapter matches Azure DevOps.
 */
public final class FakeAzureDevOpsServer implements AutoCloseable {

  /** A request received. */
  public record Request(
      String method, String path, String query, String authorization, String body) {}

  /** A pull request. */
  public record Pr(
      int id,
      String source,
      String target,
      String title,
      String description,
      boolean draft,
      List<String> labels) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpServer server;
  public final List<Request> requests = new CopyOnWriteArrayList<>();
  public final Map<String, String> refs = new HashMap<>();
  public final Map<String, String> files = new HashMap<>();
  public final Set<String> policyProtected = new HashSet<>();
  public final List<Pr> pullRequests = new CopyOnWriteArrayList<>();
  public volatile int forcedStatus;
  private final AtomicInteger ids = new AtomicInteger(100);

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

  public FakeAzureDevOpsServer withBranch(String name, String sha) {
    refs.put("refs/heads/" + name, sha);
    return this;
  }

  private void handle(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
    String query =
        ex.getRequestURI().getRawQuery() == null
            ? ""
            : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
    String method = ex.getRequestMethod();
    requests.add(
        new Request(method, path, query, ex.getRequestHeaders().getFirst("Authorization"), body));
    if (forcedStatus != 0) {
      respond(ex, forcedStatus, "{}");
      return;
    }
    try {
      if (!query.contains("api-version=")) {
        respond(ex, 400, "{\"message\":\"api-version is required\"}");
      } else if (method.equals("GET")
          && path.matches("/[^/]+/[^/]+/_apis/git/repositories/[^/]+")) {
        respond(ex, 200, "{\"id\":\"repo-id-1\"}");
      } else if (method.equals("GET") && path.endsWith("/refs")) {
        String filter = value(query, "filter");
        List<String> hits = new ArrayList<>();
        refs.forEach(
            (name, sha) -> {
              if (name.startsWith("refs/" + filter)) {
                hits.add("{\"name\":\"" + name + "\",\"objectId\":\"" + sha + "\"}");
              }
            });
        respond(ex, 200, "{\"value\":[" + String.join(",", hits) + "]}");
      } else if (method.equals("POST") && path.endsWith("/refs")) {
        JsonNode update = JSON.readTree(body).get(0);
        refs.put(update.path("name").asText(), update.path("newObjectId").asText());
        respond(ex, 200, "{\"value\":[{\"success\":true}]}");
      } else if (method.equals("GET") && path.endsWith("/items")) {
        String content =
            files.get(value(query, "versionDescriptor.version") + ":" + value(query, "path"));
        if (content == null) {
          respond(ex, 404, "{}");
        } else {
          respond(ex, 200, JSON.createObjectNode().put("content", content).toString());
        }
      } else if (method.equals("POST") && path.endsWith("/pushes")) {
        JsonNode in = JSON.readTree(body);
        String ref = in.path("refUpdates").get(0).path("name").asText();
        String branch = ref.replaceFirst("^refs/heads/", "");
        JsonNode change = in.path("commits").get(0).path("changes").get(0);
        files.put(
            branch + ":" + change.path("item").path("path").asText(),
            change.path("newContent").path("content").asText());
        refs.put(ref, "pushed-" + files.size());
        respond(ex, 201, "{\"pushId\":1}");
      } else if (method.equals("GET") && path.endsWith("/policy/configurations")) {
        String ref = value(query, "refName");
        boolean guarded = policyProtected.contains(ref.replaceFirst("^refs/heads/", ""));
        respond(ex, 200, "{\"value\":[" + (guarded ? "{\"isEnabled\":true}" : "") + "]}");
      } else if (method.equals("GET") && path.endsWith("/pullrequests")) {
        String source =
            value(query, "searchCriteria.sourceRefName").replaceFirst("^refs/heads/", "");
        List<String> hits = new ArrayList<>();
        for (Pr pr : pullRequests) {
          if (pr.source().equals(source)) {
            hits.add("{\"pullRequestId\":" + pr.id() + "}");
          }
        }
        respond(ex, 200, "{\"value\":[" + String.join(",", hits) + "]}");
      } else if (method.equals("POST") && path.endsWith("/pullrequests")) {
        JsonNode in = JSON.readTree(body);
        int id = ids.getAndIncrement();
        pullRequests.add(
            new Pr(
                id,
                in.path("sourceRefName").asText().replaceFirst("^refs/heads/", ""),
                in.path("targetRefName").asText().replaceFirst("^refs/heads/", ""),
                in.path("title").asText(),
                in.path("description").asText(),
                in.path("isDraft").asBoolean(false),
                new ArrayList<>()));
        respond(ex, 201, "{\"pullRequestId\":" + id + "}");
      } else if (method.equals("POST") && path.endsWith("/labels")) {
        String[] parts = path.split("/");
        int id = Integer.parseInt(parts[parts.length - 2]);
        for (Pr pr : pullRequests) {
          if (pr.id() == id) {
            pr.labels().add(JSON.readTree(body).path("name").asText());
          }
        }
        respond(ex, 200, "{}");
      } else {
        respond(ex, 404, "{}");
      }
    } catch (RuntimeException e) {
      respond(ex, 500, "{}");
    }
  }

  private static String value(String query, String key) {
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(key)) {
        return pair.substring(eq + 1);
      }
    }
    return "";
  }

  private static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
