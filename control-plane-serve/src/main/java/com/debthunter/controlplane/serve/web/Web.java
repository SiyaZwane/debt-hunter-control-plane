package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.access.NotFoundException;
import com.debthunter.controlplane.serve.access.ServiceUnavailableException;
import com.debthunter.controlplane.store.jdbc.StoreException;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** What the read controllers share: who the caller is, running blocking reads, and errors. */
final class Web {

  private static final Logger LOG = LoggerFactory.getLogger(Web.class);

  private Web() {}

  /**
   * Reads the caller from the validated token.
   *
   * @param principal the authenticated principal
   * @return the caller
   * @throws AccessDeniedException if the principal is not a token this API understands
   */
  static Caller caller(Principal principal) {
    if (principal instanceof JwtAuthenticationToken token) {
      Jwt jwt = token.getToken();
      return new Caller(jwt.getSubject(), claim(jwt, "roles"), claim(jwt, "squads"));
    }
    throw new AccessDeniedException("The caller could not be identified");
  }

  private static Set<String> claim(Jwt jwt, String name) {
    List<String> values = jwt.getClaimAsStringList(name);
    return values == null ? Set.of() : Set.copyOf(values);
  }

  /** Runs a blocking read off the event loop and turns failures into responses. */
  static Mono<ResponseEntity<Object>> blocking(
      Mono<Principal> principal, java.util.function.Function<Caller, Object> read) {
    return principal
        .map(Web::caller)
        .publishOn(Schedulers.boundedElastic())
        .map(caller -> ResponseEntity.ok().body(read.apply(caller)))
        .onErrorResume(Web::toError);
  }

  /** Runs a blocking change off the event loop and answers with the given status. */
  static Mono<ResponseEntity<Object>> blocking(
      Mono<Principal> principal,
      HttpStatus status,
      java.util.function.Function<Caller, Object> change) {
    return principal
        .map(Web::caller)
        .publishOn(Schedulers.boundedElastic())
        .map(caller -> ResponseEntity.status(status).body(change.apply(caller)))
        .onErrorResume(Web::toError);
  }

  /** Runs a blocking read that needs no caller. */
  static Mono<ResponseEntity<Object>> blocking(Callable<Object> read) {
    return Mono.fromCallable(read)
        .subscribeOn(Schedulers.boundedElastic())
        .map(body -> ResponseEntity.ok().body(body))
        .onErrorResume(Web::toError);
  }

  static Mono<ResponseEntity<Object>> toError(Throwable failure) {
    if (failure instanceof AccessDeniedException) {
      return error(HttpStatus.FORBIDDEN, "forbidden", failure.getMessage());
    }
    if (failure instanceof NotFoundException) {
      return error(HttpStatus.NOT_FOUND, "not_found", failure.getMessage());
    }
    if (failure instanceof InvalidRequestException) {
      return error(HttpStatus.BAD_REQUEST, "invalid_request", failure.getMessage());
    }
    if (failure instanceof ServiceUnavailableException || failure instanceof StoreException) {
      LOG.error("a read could not be served", failure);
      return error(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "The data could not be read now");
    }
    LOG.error("a read failed", failure);
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "The request could not be completed");
  }

  private static Mono<ResponseEntity<Object>> error(
      HttpStatus status, String code, String message) {
    return Mono.just(ResponseEntity.status(status).body(new ErrorBody(code, message)));
  }
}
