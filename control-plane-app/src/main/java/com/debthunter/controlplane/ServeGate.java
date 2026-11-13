package com.debthunter.controlplane;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Answers 503 on the read endpoints while the serve zone is stopped. It looks only at the paths the
 * read APIs own, so ingestion and administration are never touched by it.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ServeGate implements WebFilter {

  static final List<String> SERVE_PREFIXES =
      List.of(
          "/api/v1/portfolio",
          "/api/v1/squads",
          "/api/v1/findings",
          "/api/v1/evidence",
          "/api/v1/audit");

  private static final String BODY =
      "{\"error\":\"unavailable\",\"message\":\"The read APIs are stopped on this instance.\"}";

  private final ServeZone zone;

  ServeGate(ServeZone zone) {
    this.zone = zone;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    boolean serve =
        SERVE_PREFIXES.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    if (!serve || zone.isRunning()) {
      return chain.filter(exchange);
    }
    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    DataBuffer buffer =
        exchange.getResponse().bufferFactory().wrap(BODY.getBytes(StandardCharsets.UTF_8));
    return exchange.getResponse().writeWith(Mono.just(buffer));
  }
}
