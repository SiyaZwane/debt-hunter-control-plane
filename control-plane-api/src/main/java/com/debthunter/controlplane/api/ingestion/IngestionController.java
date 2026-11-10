package com.debthunter.controlplane.api.ingestion;

import com.debthunter.controlplane.egress.alert.SecurityAlert;
import com.debthunter.controlplane.egress.alert.SecurityAlertPublisher;
import com.debthunter.controlplane.egress.alert.SecurityAlertType;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.QueueUnavailableException;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The ingestion edge: accepts a run bundle from a pipeline, checks it, hands it to the intake
 * queue, and acknowledges with {@code 202}.
 *
 * <p>The defining constraint is that nothing here may be something a pipeline waits on. The work
 * done before replying is bounded and local — rate limit, parse, scope check, schema check, one
 * bounded enqueue — and the reply carries no verdict. If the queue is down the answer is {@code
 * 503}, fast, rather than a hang.
 *
 * <p>The checks run cheapest and least revealing first: a caller over its rate limit is turned away
 * before its body is read, and a caller outside the bundle's repository scope is refused before it
 * learns anything about schema validation.
 */
@RestController
public class IngestionController {

  /** Where bundles are published. */
  public static final String PATH = "/api/v1/runs";

  private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

  /**
   * Duplicate keys are rejected: a bundle that says "repository" twice is trying to be ambiguous.
   */
  private final ObjectMapper mapper =
      JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();

  private final RunBundleSchemaValidator schemaValidator;
  private final WorkloadIdentityValidator workloadValidator;
  private final RunIntakeQueuePublisher queue;
  private final SecurityAlertPublisher alerts;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  /**
   * Creates the controller.
   *
   * @param schemaValidator checks a bundle against the accepted schema versions
   * @param workloadValidator checks the caller's scope against the declared repository
   * @param queue where accepted bundles go
   * @param alerts told when a caller steps outside its scope
   * @param rateLimiter limits how fast one caller may publish
   * @param clock stamps accepted bundles
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public IngestionController(
      RunBundleSchemaValidator schemaValidator,
      WorkloadIdentityValidator workloadValidator,
      RunIntakeQueuePublisher queue,
      SecurityAlertPublisher alerts,
      RequestRateLimiter rateLimiter,
      Clock clock) {
    this.schemaValidator = schemaValidator;
    this.workloadValidator = workloadValidator;
    this.queue = queue;
    this.alerts = alerts;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  /**
   * Accepts a run bundle.
   *
   * @param principal the authenticated caller, supplied by the security filter chain
   * @param body the bundle's raw JSON
   * @return {@code 202} with a correlation id, or a machine-readable error
   */
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Object>> ingest(
      Mono<Principal> principal, @RequestBody Mono<byte[]> body) {
    return principal
        .map(Principal::getName)
        .flatMap(identity -> process(identity, body))
        .switchIfEmpty(
            Mono.fromSupplier(
                () ->
                    error(
                        HttpStatus.UNAUTHORIZED,
                        "unauthenticated",
                        "A workload identity is required")))
        .onErrorResume(
            failure -> {
              log.error("unexpected failure ingesting a run bundle", failure);
              return Mono.just(
                  error(
                      HttpStatus.INTERNAL_SERVER_ERROR,
                      "internal_error",
                      "The bundle could not be processed"));
            });
  }

  private Mono<ResponseEntity<Object>> process(String identity, Mono<byte[]> body) {
    RateLimitDecision limit = rateLimiter.tryAcquire(identity);
    if (!limit.allowed()) {
      log.warn("rate limit exceeded workloadIdentity={}", identity);
      return Mono.just(rateLimited(limit.retryAfter()));
    }
    return body.defaultIfEmpty(new byte[0]).flatMap(bytes -> handle(identity, bytes));
  }

  private Mono<ResponseEntity<Object>> handle(String identity, byte[] bytes) {
    JsonNode root;
    try {
      root = mapper.readTree(bytes);
    } catch (IOException e) {
      return Mono.just(
          error(HttpStatus.BAD_REQUEST, "invalid_json", "The request body is not valid JSON"));
    }
    if (root == null || !root.isObject()) {
      return Mono.just(
          error(HttpStatus.BAD_REQUEST, "invalid_json", "The request body must be a JSON object"));
    }

    JsonNode repositoryNode = root.path("run").path("repository");
    String repository = repositoryNode.isTextual() ? repositoryNode.asText() : null;
    if (repository == null || repository.isBlank()) {
      return Mono.just(
          schemaError(List.of(new SchemaViolation("$.run.repository", "is required")), 1));
    }

    ScopeDecision scope = workloadValidator.validate(identity, repository);
    if (!scope.allowed()) {
      log.warn("workload scope violation workloadIdentity={} reason={}", identity, scope.reason());
      raiseScopeAlert(identity, repository);
      return Mono.just(
          error(
              HttpStatus.FORBIDDEN,
              "workload_scope_violation",
              "The caller is not permitted to publish for the declared repository"));
    }

    return switch (schemaValidator.validate(root)) {
      case SchemaValidationResult.Invalid invalid ->
          Mono.just(schemaError(invalid.violations(), invalid.totalViolations()));
      case SchemaValidationResult.UnsupportedVersion unsupported ->
          Mono.just(unsupportedVersion(unsupported));
      case SchemaValidationResult.Valid valid -> enqueue(identity, repository, valid, root, bytes);
    };
  }

  private Mono<ResponseEntity<Object>> enqueue(
      String identity,
      String repository,
      SchemaValidationResult.Valid valid,
      JsonNode root,
      byte[] bytes) {
    IntakeEnvelope envelope =
        new IntakeEnvelope(
            UUID.randomUUID(),
            identity,
            repository,
            valid.schemaVersion(),
            clock.instant(),
            root.path("run").path("id").asText(),
            new String(bytes, StandardCharsets.UTF_8));
    // Publishing may block briefly while the broker's metadata is resolved, so it must not run on
    // an event-loop thread.
    return Mono.fromFuture(() -> queue.publish(envelope).toCompletableFuture())
        .subscribeOn(Schedulers.boundedElastic())
        .then(Mono.fromSupplier(() -> accepted(envelope)))
        .onErrorResume(
            QueueUnavailableException.class,
            failure -> {
              log.warn(
                  "intake queue unavailable correlationId={}", envelope.correlationId(), failure);
              return Mono.just(queueUnavailable());
            });
  }

  private void raiseScopeAlert(String identity, String repository) {
    try {
      alerts.publish(
          new SecurityAlert(
              SecurityAlertType.WORKLOAD_SCOPE_VIOLATION, identity, repository, clock.instant()));
    } catch (RuntimeException e) {
      // The alert contract forbids throwing, but the 403 must not depend on it.
      log.error("could not raise a security alert", e);
    }
  }

  private static ResponseEntity<Object> accepted(IntakeEnvelope envelope) {
    log.info(
        "accepted run bundle correlationId={} workloadIdentity={}",
        envelope.correlationId(),
        envelope.workloadIdentity());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new AcceptedResponse(envelope.correlationId()));
  }

  private static ResponseEntity<Object> schemaError(List<SchemaViolation> violations, int total) {
    String message =
        total > violations.size()
            ? "The bundle failed schema validation ("
                + total
                + " problems; first "
                + violations.size()
                + " shown)"
            : "The bundle failed schema validation";
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ApiError("schema_validation_failed", message, violations, null));
  }

  private static ResponseEntity<Object> unsupportedVersion(
      SchemaValidationResult.UnsupportedVersion unsupported) {
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new ApiError(
                "unsupported_schema_version",
                "Schema version " + unsupported.declared() + " is " + unsupported.reason(),
                null,
                unsupported.supportedMajors()));
  }

  private static ResponseEntity<Object> queueUnavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", "30")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            ApiError.of(
                "intake_unavailable",
                "The bundle could not be accepted right now; try again later"));
  }

  private static ResponseEntity<Object> rateLimited(Duration retryAfter) {
    long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", String.valueOf(seconds))
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiError.of("rate_limited", "Too many bundles published; slow down"));
  }

  private static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiError.of(code, message));
  }
}
