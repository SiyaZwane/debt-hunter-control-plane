package com.debthunter.controlplane.api.security;

import java.net.URI;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Builds the decoder that authenticates workload tokens.
 *
 * <p>Checking the signature is not enough. An Entra tenant signs tokens for <em>every</em>
 * application in it with the same keys, so a token minted for some other app would verify. The
 * decoder therefore also requires the expected issuer and that this service is the intended
 * audience.
 */
public final class ControlPlaneJwtDecoders {

  private ControlPlaneJwtDecoders() {}

  /**
   * Creates a decoder verifying RS256 signatures against a JWKS endpoint.
   *
   * @param jwkSetUri where the issuer publishes its signing keys; fetched lazily on first use
   * @param issuer the {@code iss} claim a token must carry
   * @param audience the {@code aud} value that must be among a token's audiences
   * @return the decoder
   */
  public static ReactiveJwtDecoder create(URI jwkSetUri, String issuer, String audience) {
    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri.toString())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    OAuth2TokenValidator<Jwt> audienceValidator =
        new JwtClaimValidator<List<String>>(
            JwtClaimNames.AUD, audiences -> audiences != null && audiences.contains(audience));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
    return decoder;
  }
}
