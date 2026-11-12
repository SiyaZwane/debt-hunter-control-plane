package com.debthunter.controlplane.egress.digest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A local imitation of an incoming webhook: records each post, and can refuse or redirect. */
public final class FakeWebhookServer implements AutoCloseable {

  /** A request received. */
  public record Request(String method, String path, String contentType, String body) {}

  private final HttpServer server;
  public final List<Request> requests = new CopyOnWriteArrayList<>();

  /** When set, every request is answered with this status. */
  public volatile int forcedStatus;

  /** When set, every request is answered with a redirect to this address. */
  public volatile String redirectTo;

  public FakeWebhookServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  public URI webhook() {
    return URI.create(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks/T000/B000/secret");
  }

  private void handle(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(
        new Request(
            ex.getRequestMethod(),
            ex.getRequestURI().getPath(),
            ex.getRequestHeaders().getFirst("Content-Type"),
            body));
    if (redirectTo != null) {
      ex.getResponseHeaders().add("Location", redirectTo);
      ex.sendResponseHeaders(302, -1);
      ex.close();
      return;
    }
    int status = forcedStatus == 0 ? 200 : forcedStatus;
    byte[] reply = "ok".getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(status, reply.length);
    ex.getResponseBody().write(reply);
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
