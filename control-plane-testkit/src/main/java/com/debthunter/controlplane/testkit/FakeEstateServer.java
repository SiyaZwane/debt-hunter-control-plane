package com.debthunter.controlplane.testkit;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A local HTTP server standing in for a source-control host's API (GitHub, Azure DevOps), so the
 * estate providers can be tested over a real socket: real request headers, real status codes, real
 * pagination. The test decides what each request gets back and can inspect what was sent.
 */
public final class FakeEstateServer implements AutoCloseable {

  /**
   * A request the server received.
   *
   * @param path the URL path, decoded
   * @param rawPath the URL path exactly as sent, still percent-encoded
   * @param query the raw query string, or an empty string
   * @param headers the request headers, lower-cased names
   */
  public record Request(String path, String rawPath, String query, Map<String, String> headers) {}

  /**
   * What to answer with.
   *
   * @param status the HTTP status
   * @param headers response headers
   * @param body the response body
   */
  public record Response(int status, Map<String, String> headers, String body) {

    /** Copies the headers so the record cannot be changed after the fact. */
    public Response {
      headers = Map.copyOf(headers);
    }

    /** A 200 response with a JSON body. */
    public static Response json(String body) {
      return new Response(200, Map.of("Content-Type", "application/json"), body);
    }

    /** A response with a status and no body. */
    public static Response status(int status) {
      return new Response(status, Map.of(), "");
    }
  }

  private final HttpServer server;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private volatile Function<Request, Response> responder = r -> Response.status(404);

  /** Starts a server on a free local port. */
  public FakeEstateServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          Map<String, String> headers = new java.util.HashMap<>();
          exchange
              .getRequestHeaders()
              .forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
          Request request =
              new Request(
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestURI().getRawPath(),
                  exchange.getRequestURI().getRawQuery() == null
                      ? ""
                      : exchange.getRequestURI().getRawQuery(),
                  headers);
          requests.add(request);
          Response response = responder.apply(request);
          byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
          response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
          exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          }
          exchange.close();
        });
    server.start();
  }

  /** Decides what each request gets back. */
  public FakeEstateServer respondWith(Function<Request, Response> responder) {
    this.responder = responder;
    return this;
  }

  /** The server's base URL, such as {@code http://127.0.0.1:54321}. */
  public URI baseUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  /** Every request received, oldest first. */
  public List<Request> requests() {
    return List.copyOf(requests);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
