package com.debthunter.controlplane.ai.remediation.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local imitation of the parts of GitHub's REST API that remediation uses, with real state:
 * branches, files, protection and pull requests. It records every request, so a test can show that
 * nothing but the expected calls were made, and the token it was given.
 */
public final class FakeGitHubServer implements AutoCloseable {

  /** A request received. */
  public record Request(
      String method, String path, String query, String authorization, String body) {}

  /** A pull request. */
  public record Pr(
      int number,
      String head,
      String base,
      String title,
      String body,
      boolean draft,
      List<String> labels) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern REF = Pattern.compile("/repos/([^/]+/[^/]+)/git/ref/heads/(.+)");
  private static final Pattern CREATE_REF = Pattern.compile("/repos/([^/]+/[^/]+)/git/refs");
  private static final Pattern BRANCH = Pattern.compile("/repos/([^/]+/[^/]+)/branches/(.+)");
  private static final Pattern RULES = Pattern.compile("/repos/([^/]+/[^/]+)/rules/branches/(.+)");
  private static final Pattern CONTENTS = Pattern.compile("/repos/([^/]+/[^/]+)/contents/(.+)");
  private static final Pattern PULLS = Pattern.compile("/repos/([^/]+/[^/]+)/pulls");
  private static final Pattern LABELS =
      Pattern.compile("/repos/([^/]+/[^/]+)/issues/(\\d+)/labels");

  private final HttpServer server;
  public final List<Request> requests = new CopyOnWriteArrayList<>();
  public final Map<String, String> branches = new HashMap<>();
  public final Set<String> protectedBranches = new HashSet<>();
  public final Map<String, String> files = new HashMap<>();
  public final List<Pr> pullRequests = new CopyOnWriteArrayList<>();

  /** Branch names a ruleset covers, even when the branch does not exist yet. */
  public volatile Predicate<String> rulesetCovers = name -> false;

  /** When set, every request is answered with this status. */
  public volatile int forcedStatus;

  public volatile String forcedHeaderName;
  public volatile String forcedHeaderValue;
  private final AtomicInteger numbers = new AtomicInteger(1);

  public FakeGitHubServer() {
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

  public FakeGitHubServer withBranch(String name, String sha) {
    branches.put(name, sha);
    return this;
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
    String query = exchange.getRequestURI().getRawQuery();
    requests.add(
        new Request(
            exchange.getRequestMethod(),
            path,
            query == null ? "" : query,
            exchange.getRequestHeaders().getFirst("Authorization"),
            body));
    int forced = forcedStatus;
    if (forced != 0) {
      if (forcedHeaderName != null) {
        exchange.getResponseHeaders().add(forcedHeaderName, forcedHeaderValue);
      }
      respond(exchange, forced, "{}");
      return;
    }
    String method = exchange.getRequestMethod();
    try {
      route(exchange, method, path, query == null ? "" : query, body);
    } catch (RuntimeException e) {
      respond(exchange, 500, "{\"message\":\"" + e.getMessage() + "\"}");
    }
  }

  private void route(HttpExchange ex, String method, String path, String query, String body)
      throws IOException {
    Matcher m;
    if (method.equals("GET") && (m = REF.matcher(path)).matches()) {
      String sha = branches.get(m.group(2));
      if (sha == null) {
        respond(ex, 404, "{\"message\":\"Not Found\"}");
      } else {
        respond(ex, 200, "{\"object\":{\"sha\":\"" + sha + "\"}}");
      }
    } else if (method.equals("GET") && (m = BRANCH.matcher(path)).matches()) {
      String name = m.group(2);
      if (!branches.containsKey(name)) {
        respond(ex, 404, "{\"message\":\"Branch not found\"}");
      } else {
        respond(
            ex,
            200,
            "{\"name\":\"" + name + "\",\"protected\":" + protectedBranches.contains(name) + "}");
      }
    } else if (method.equals("GET") && (m = RULES.matcher(path)).matches()) {
      respond(ex, 200, rulesetCovers.test(m.group(2)) ? "[{\"type\":\"pull_request\"}]" : "[]");
    } else if (method.equals("POST") && (m = CREATE_REF.matcher(path)).matches()) {
      JsonNode in = JSON.readTree(body);
      String name = in.path("ref").asText().replaceFirst("^refs/heads/", "");
      if (branches.containsKey(name)) {
        respond(ex, 422, "{\"message\":\"Reference already exists\"}");
      } else {
        branches.put(name, in.path("sha").asText());
        respond(ex, 201, "{\"ref\":\"refs/heads/" + name + "\"}");
      }
    } else if (method.equals("GET") && (m = CONTENTS.matcher(path)).matches()) {
      String branch = queryValue(query, "ref");
      String content = files.get(branch + ":" + m.group(2));
      if (content == null) {
        respond(ex, 404, "{\"message\":\"Not Found\"}");
      } else {
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        respond(
            ex, 200, "{\"sha\":\"blob-" + content.hashCode() + "\",\"content\":\"" + b64 + "\"}");
      }
    } else if (method.equals("PUT") && (m = CONTENTS.matcher(path)).matches()) {
      JsonNode in = JSON.readTree(body);
      String branch = in.path("branch").asText();
      if (!branches.containsKey(branch)) {
        respond(ex, 404, "{\"message\":\"Branch not found\"}");
        return;
      }
      if (protectedBranches.contains(branch)) {
        respond(ex, 403, "{\"message\":\"Protected branch\"}");
        return;
      }
      String key = branch + ":" + m.group(2);
      if (files.containsKey(key) && in.path("sha").asText("").isEmpty()) {
        respond(ex, 422, "{\"message\":\"sha wasn't supplied\"}");
        return;
      }
      files.put(
          key,
          new String(
              Base64.getDecoder().decode(in.path("content").asText()), StandardCharsets.UTF_8));
      respond(ex, 201, "{\"content\":{}}");
    } else if (method.equals("GET") && PULLS.matcher(path).matches()) {
      String head = queryValue(query, "head");
      String branch = head.substring(head.indexOf(':') + 1);
      ArrayNode out = JSON.createArrayNode();
      for (Pr pr : pullRequests) {
        if (pr.head().equals(branch)) {
          out.add(
              JSON.createObjectNode()
                  .put("number", pr.number())
                  .put("html_url", prUrl(pr.number())));
        }
      }
      respond(ex, 200, out.toString());
    } else if (method.equals("POST") && PULLS.matcher(path).matches()) {
      JsonNode in = JSON.readTree(body);
      int n = numbers.getAndIncrement();
      pullRequests.add(
          new Pr(
              n,
              in.path("head").asText(),
              in.path("base").asText(),
              in.path("title").asText(),
              in.path("body").asText(),
              in.path("draft").asBoolean(false),
              new ArrayList<>()));
      ObjectNode out = JSON.createObjectNode().put("number", n).put("html_url", prUrl(n));
      respond(ex, 201, out.toString());
    } else if (method.equals("POST") && (m = LABELS.matcher(path)).matches()) {
      int number = Integer.parseInt(m.group(2));
      for (Pr pr : pullRequests) {
        if (pr.number() == number) {
          JsonNode in = JSON.readTree(body);
          in.path("labels").forEach(l -> pr.labels().add(l.asText()));
        }
      }
      respond(ex, 200, "[]");
    } else {
      respond(ex, 404, "{\"message\":\"Not Found\"}");
    }
  }

  private String prUrl(int number) {
    return "https://github.example/acme/widgets/pull/" + number;
  }

  private static String queryValue(String query, String key) {
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(key)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      ex.getResponseBody().write(bytes);
    }
    ex.close();
  }

  /** Requests that changed anything. */
  public List<Request> writes() {
    return requests.stream().filter(r -> !r.method().equals("GET")).toList();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
