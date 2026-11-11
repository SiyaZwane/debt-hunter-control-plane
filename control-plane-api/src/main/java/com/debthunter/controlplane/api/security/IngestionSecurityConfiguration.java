package com.debthunter.controlplane.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

/**
 * Secures the API as a stateless resource server: every request must carry a valid bearer token,
 * and nothing is remembered between requests. The {@code ReactiveJwtDecoder} that validates tokens
 * is supplied by the application, so tests and production differ only in where trust comes from.
 *
 * <p>Deny by default: there is no unauthenticated path.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class IngestionSecurityConfiguration {

  /**
   * The filter chain protecting every endpoint.
   *
   * @param http the builder to configure
   * @return the chain
   */
  @Bean
  public SecurityWebFilterChain ingestionSecurityFilterChain(ServerHttpSecurity http) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .build();
  }
}
