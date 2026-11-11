package com.debthunter.controlplane.api.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtGrantedAuthoritiesConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import reactor.core.publisher.Mono;

/**
 * Secures the API as a stateless resource server: every request must carry a valid bearer token,
 * and nothing is remembered between requests. The {@code ReactiveJwtDecoder} that validates tokens
 * is supplied by the application, so tests and production differ only in where trust comes from.
 *
 * <p>Deny by default: there is no unauthenticated path. The administration endpoints ({@value
 * #ADMIN_PATH}) additionally require the administrator role, read from the token's {@code roles}
 * claim, so a pipeline's token, however valid, can never reach them.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class IngestionSecurityConfiguration {

  /** Every administration endpoint lives under this path. */
  public static final String ADMIN_PATH = "/api/v1/admin/**";

  /** The role an administrator's token must carry when none is configured. */
  public static final String DEFAULT_ADMIN_ROLE = "ControlPlane.Admin";

  /**
   * The filter chain protecting every endpoint.
   *
   * @param http the builder to configure
   * @param adminAccess which role grants administration, if the application configures one
   * @return the chain
   */
  @Bean
  public SecurityWebFilterChain ingestionSecurityFilterChain(
      ServerHttpSecurity http, ObjectProvider<AdminAccess> adminAccess) {
    String adminRole = adminAccess.getIfAvailable(() -> new AdminAccess(DEFAULT_ADMIN_ROLE)).role();
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers(ADMIN_PATH)
                    .hasAuthority(AUTHORITY_PREFIX + adminRole)
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
        .build();
  }

  private static final String AUTHORITY_PREFIX = "ROLE_";

  /** Turns the token's {@code roles} claim into authorities, so roles can be authorised on. */
  private static Converter<Jwt, Mono<AbstractAuthenticationToken>> rolesConverter() {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix(AUTHORITY_PREFIX);
    ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        new ReactiveJwtGrantedAuthoritiesConverterAdapter(roles));
    return converter;
  }
}
