package com.debthunter.controlplane.serve.web;

import java.time.Instant;
import java.util.List;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Builds a client for one controller whose requests carry a given token, without a security chain.
 */
final class ControllerSupport {

  private ControllerSupport() {}

  static JwtAuthenticationToken token(String subject, List<String> roles, List<String> squads) {
    Jwt.Builder jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject(subject)
            .issuedAt(Instant.parse("2026-07-01T00:00:00Z"))
            .expiresAt(Instant.parse("2026-07-02T00:00:00Z"));
    if (roles != null) {
      jwt.claim("roles", roles);
    }
    if (squads != null) {
      jwt.claim("squads", squads);
    }
    return new JwtAuthenticationToken(jwt.build(), AuthorityUtils.NO_AUTHORITIES);
  }

  static WebTestClient client(Object controller, JwtAuthenticationToken as) {
    return WebTestClient.bindToController(controller)
        .webFilter(
            (exchange, chain) -> chain.filter(exchange.mutate().principal(Mono.just(as)).build()))
        .build();
  }
}
