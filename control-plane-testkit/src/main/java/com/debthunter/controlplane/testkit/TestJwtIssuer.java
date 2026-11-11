package com.debthunter.controlplane.testkit;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues signed JWTs and serves the matching public key from a local JWKS endpoint, so a service's
 * production JWT decoder can be exercised against real signatures, issuers, and audiences rather
 * than a mock.
 */
public final class TestJwtIssuer implements AutoCloseable {

  /** The issuer claim on tokens this issuer signs by default. */
  public static final String ISSUER = "https://login.test/tenant-1/v2.0";

  /** The audience claim on tokens this issuer signs by default. */
  public static final String AUDIENCE = "api://debt-hunter-control-plane";

  private final RSAKey signingKey;
  private HttpServer jwksServer;

  /** Creates an issuer with a freshly generated RSA signing key. */
  public TestJwtIssuer() {
    try {
      this.signingKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException("Could not generate a test signing key", e);
    }
  }

  /**
   * Signs a valid token for a workload identity, with the default issuer and audience.
   *
   * @param subject the workload identity, carried as the {@code sub} claim
   * @return the compact serialised token
   */
  public String token(String subject) {
    return token(subject, ISSUER, AUDIENCE, Instant.now().plus(Duration.ofHours(1)));
  }

  /**
   * Signs a valid token that carries application roles, as an Entra app-role assignment would.
   *
   * @param subject the {@code sub} claim
   * @param roles the {@code roles} claim values
   * @return the compact serialised token
   */
  public String tokenWithRoles(String subject, String... roles) {
    return sign(
        new JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .issueTime(Date.from(Instant.now().minus(Duration.ofMinutes(1))))
            .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
            .claim("roles", java.util.List.of(roles))
            .build());
  }

  /**
   * Signs a token with explicit claims, for negative-path tests.
   *
   * @param subject the {@code sub} claim
   * @param issuer the {@code iss} claim
   * @param audience the {@code aud} claim
   * @param expiresAt the {@code exp} claim
   * @return the compact serialised token
   */
  public String token(String subject, String issuer, String audience, Instant expiresAt) {
    return sign(
        new JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date.from(Instant.now().minus(Duration.ofMinutes(1))))
            .expirationTime(Date.from(expiresAt))
            .build());
  }

  private String sign(JWTClaimsSet claims) {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
    try {
      jwt.sign(new RSASSASigner(signingKey));
    } catch (JOSEException e) {
      throw new IllegalStateException("Could not sign a test token", e);
    }
    return jwt.serialize();
  }

  /**
   * Starts a local HTTP server publishing this issuer's public key as a JWK set. Idempotent.
   *
   * @return the JWKS endpoint's URI
   */
  public synchronized URI startJwksServer() {
    if (jwksServer == null) {
      try {
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      byte[] body =
          new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      jwksServer.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          });
      jwksServer.start();
    }
    return URI.create("http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
  }

  @Override
  public synchronized void close() {
    if (jwksServer != null) {
      jwksServer.stop(0);
      jwksServer = null;
    }
  }
}
