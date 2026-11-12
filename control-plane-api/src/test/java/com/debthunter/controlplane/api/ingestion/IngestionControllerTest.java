package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.egress.alert.SecurityAlert;
import com.debthunter.controlplane.egress.alert.SecurityAlertType;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.QueueUnavailableException;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryScopeLookup;
import com.debthunter.controlplane.store.registry.RepositoryScopeLookup;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Exercises the controller's behaviour in isolation: real validators, with the queue, alerting, and
 * authentication stood in for. The full security chain and wiring are covered by the integration
 * tests.
 */
class IngestionControllerTest {

  private static final String IDENTITY = "svc-a";

  private final CapturingQueue queue = new CapturingQueue();
  private final RecordingAlerts alerts = new RecordingAlerts();
  private final TestClock clock = new TestClock(Instant.parse("2026-02-03T04:05:06Z"));

  @Test
  void aValidBundleIsAcceptedWithOnlyACorrelationIdInTheBody() {
    Map<String, Object> body =
        post(client(IDENTITY), RunBundleFixtures.validV1())
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(body).containsOnlyKeys("correlationId");
    assertThat(UUID.fromString((String) body.get("correlationId"))).isNotNull();
  }

  @Test
  void theEnqueuedEnvelopeCarriesWhatTheEdgeLearnedAboutTheBundle() {
    String bundle = RunBundleFixtures.validV1("run-42", "acme/widgets");

    Map<String, Object> body =
        post(client(IDENTITY), bundle)
            .expectStatus()
            .isAccepted()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(queue.published()).hasSize(1);
    IntakeEnvelope envelope = queue.published().get(0);
    assertThat(envelope.correlationId().toString()).isEqualTo(body.get("correlationId"));
    assertThat(envelope.workloadIdentity()).isEqualTo(IDENTITY);
    assertThat(envelope.repository()).isEqualTo("acme/widgets");
    assertThat(envelope.runId()).isEqualTo("run-42");
    assertThat(envelope.schemaVersion()).isEqualTo("1.0");
    assertThat(envelope.receivedAt()).isEqualTo(Instant.parse("2026-02-03T04:05:06Z"));
    assertThat(envelope.payloadJson()).isEqualTo(bundle);
  }

  @Test
  void aPreviousVersionBundleIsAccepted() {
    post(client(IDENTITY), RunBundleFixtures.validV0()).expectStatus().isAccepted();

    assertThat(queue.published()).extracting(IntakeEnvelope::schemaVersion).containsExactly("0.9");
  }

  @Test
  void aBundleForARepositoryOutsideTheCallersScopeIsForbiddenAndAlerted() {
    post(client(IDENTITY), RunBundleFixtures.validV1("run-1", "acme/secret"))
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("workload_scope_violation");

    assertThat(queue.published()).isEmpty();
    assertThat(alerts.alerts())
        .containsExactly(
            new SecurityAlert(
                SecurityAlertType.WORKLOAD_SCOPE_VIOLATION,
                IDENTITY,
                "acme/secret",
                Instant.parse("2026-02-03T04:05:06Z")));
  }

  @Test
  void scopeIsCheckedBeforeSchemaSoAnOutOfScopeCallerLearnsNothingAboutValidation() {
    String invalidAndOutOfScope =
        RunBundleFixtures.withoutField(
            RunBundleFixtures.validV1("run-1", "acme/secret"), "/findings");

    post(client(IDENTITY), invalidAndOutOfScope).expectStatus().isForbidden();
  }

  @Test
  void aFailureToRaiseTheAlertDoesNotChangeTheForbiddenResponse() {
    alerts.setThrowing(true);

    post(client(IDENTITY), RunBundleFixtures.validV1("run-1", "acme/secret"))
        .expectStatus()
        .isForbidden();
  }

  @Test
  void aBundleFailingSchemaValidationIsRejectedWithMachineReadableDetails() {
    String bundle = RunBundleFixtures.withoutField(RunBundleFixtures.validV1(), "/policy/status");

    post(client(IDENTITY), bundle)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("schema_validation_failed")
        .jsonPath("$.details[0].path")
        .isEqualTo("$.policy")
        .jsonPath("$.details[0].message")
        .value(message -> assertThat((String) message).contains("status"));

    assertThat(queue.published()).isEmpty();
  }

  @Test
  void aMissingDeclaredRepositoryIsRejectedAsASchemaFailure() {
    String bundle = RunBundleFixtures.withoutField(RunBundleFixtures.validV1(), "/run/repository");

    post(client(IDENTITY), bundle)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("schema_validation_failed")
        .jsonPath("$.details[0].path")
        .isEqualTo("$.run.repository");
  }

  @Test
  void anUnsupportedSchemaVersionIsRejectedWithTheAcceptedVersionsListed() {
    String bundle = RunBundleFixtures.withSchemaVersion(RunBundleFixtures.validV1(), "9.0");

    post(client(IDENTITY), bundle)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("unsupported_schema_version")
        .jsonPath("$.supportedMajors[0]")
        .isEqualTo(1)
        .jsonPath("$.supportedMajors[1]")
        .isEqualTo(0);
  }

  @Test
  void aBodyThatIsNotJsonIsRejected() {
    post(client(IDENTITY), RunBundleFixtures.notJson())
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("invalid_json");
  }

  @Test
  void anEmptyBodyIsRejected() {
    post(client(IDENTITY), "").expectStatus().isBadRequest();
  }

  @Test
  void aBodyThatIsNotAJsonObjectIsRejected() {
    post(client(IDENTITY), "[1, 2, 3]")
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("invalid_json");
  }

  @Test
  void aBundleDeclaringTheSameKeyTwiceIsRejectedRatherThanLeftAmbiguous() {
    // The scope check and a later consumer could otherwise read different values of "repository".
    String ambiguous =
        RunBundleFixtures.validV1("run-1", "acme/widgets")
            .replace("\"run\":{", "\"run\":{\"repository\":\"acme/other\",");

    post(client(IDENTITY), ambiguous)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("invalid_json");
    assertThat(queue.published()).isEmpty();
  }

  @Test
  void anUnavailableQueueYieldsAServiceUnavailableWithARetryHint() {
    queue.failWith(new QueueUnavailableException("broker down", new RuntimeException()));

    post(client(IDENTITY), RunBundleFixtures.validV1())
        .expectStatus()
        .isEqualTo(503)
        .expectHeader()
        .exists("Retry-After")
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("intake_unavailable");
  }

  @Test
  void anUnexpectedQueueFailureIsAnInternalErrorThatLeaksNothing() {
    queue.failWith(new IllegalStateException("secret internal detail"));

    String body =
        post(client(IDENTITY), RunBundleFixtures.validV1())
            .expectStatus()
            .is5xxServerError()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(body).contains("internal_error").doesNotContain("secret internal detail");
  }

  @Test
  void aCallerOverItsRateLimitIsToldToSlowDown() {
    RequestRateLimiter refusing = key -> RateLimitDecision.refuse(java.time.Duration.ofSeconds(7));

    post(client(IDENTITY, refusing), RunBundleFixtures.validV1())
        .expectStatus()
        .isEqualTo(429)
        .expectHeader()
        .valueEquals("Retry-After", "7")
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("rate_limited");

    assertThat(queue.published()).isEmpty();
  }

  @Test
  void aRequestWithNoAuthenticatedCallerIsUnauthorised() {
    post(client(null), RunBundleFixtures.validV1()).expectStatus().isUnauthorized();
  }

  // ---- the database being unavailable ----------------------------------------------------------

  private static final Duration SHORT = Duration.ofMillis(300);

  private WebTestClient withScopes(RepositoryScopeLookup scopes) {
    return client(IDENTITY, key -> RateLimitDecision.permit(), scopes, queue, SHORT);
  }

  @Test
  void anUnreachableRegistryIsAPrompt503NotAServerError() {
    RepositoryScopeLookup down =
        (identity, repository) -> {
          throw new StoreException("database unreachable", null);
        };

    post(withScopes(down), RunBundleFixtures.validV1())
        .expectStatus()
        .isEqualTo(503)
        .expectHeader()
        .exists("Retry-After")
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("registry_unavailable");
    assertThat(queue.published()).isEmpty();
    assertThat(alerts.alerts()).as("an outage is not a scope violation").isEmpty();
  }

  @Test
  void aRegistryFailureWrappedInsideAnotherExceptionIsStillA503() {
    RepositoryScopeLookup down =
        (identity, repository) -> {
          throw new IllegalStateException("lookup failed", new StoreException("db", null));
        };

    post(withScopes(down), RunBundleFixtures.validV1()).expectStatus().isEqualTo(503);
  }

  @Test
  void aRegistryThatHangsIsA503WithinTheBoundNotAHang() {
    RepositoryScopeLookup hanging =
        (identity, repository) -> {
          try {
            Thread.sleep(5_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return true;
        };
    long started = System.nanoTime();

    post(withScopes(hanging), RunBundleFixtures.validV1())
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("registry_unavailable");

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
  }

  @Test
  void anUnexpectedBugInTheScopeLookupIsStillAnInternalError() {
    RepositoryScopeLookup buggy =
        (identity, repository) -> {
          throw new IllegalStateException("a bug, not an outage");
        };

    post(withScopes(buggy), RunBundleFixtures.validV1())
        .expectStatus()
        .isEqualTo(500)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("internal_error");
  }

  @Test
  void theRegistryIsReadOffTheCallingThread() {
    java.util.concurrent.atomic.AtomicReference<String> thread =
        new java.util.concurrent.atomic.AtomicReference<>();
    RepositoryScopeLookup recording =
        (identity, repository) -> {
          thread.set(Thread.currentThread().getName());
          return true;
        };
    String caller = Thread.currentThread().getName();

    post(withScopes(recording), RunBundleFixtures.validV1()).expectStatus().isAccepted();

    assertThat(thread.get()).isNotEqualTo(caller).contains("boundedElastic");
  }

  @Test
  void aQueueWriteThatNeverFinishesIsA503WithinTheBound() {
    RunIntakeQueuePublisher stuck = envelope -> new java.util.concurrent.CompletableFuture<>();
    long started = System.nanoTime();

    post(
            client(
                IDENTITY,
                key -> RateLimitDecision.permit(),
                new InMemoryRepositoryScopeLookup(Map.of(IDENTITY, List.of("acme/widgets"))),
                stuck,
                SHORT),
            RunBundleFixtures.validV1())
        .expectStatus()
        .isEqualTo(503)
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("intake_unavailable");

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
  }

  private WebTestClient client(String identity) {
    return client(identity, key -> RateLimitDecision.permit());
  }

  private WebTestClient client(String identity, RequestRateLimiter rateLimiter) {
    return client(
        identity,
        rateLimiter,
        new InMemoryRepositoryScopeLookup(Map.of(IDENTITY, List.of("acme/widgets"))),
        queue,
        Duration.ofSeconds(3));
  }

  private WebTestClient client(
      String identity,
      RequestRateLimiter rateLimiter,
      RepositoryScopeLookup scopes,
      RunIntakeQueuePublisher publisher,
      Duration wait) {
    IngestionController controller =
        new IngestionController(
            RunBundleSchemaValidator.withClasspathSchemas(1),
            new WorkloadIdentityValidator(scopes),
            publisher,
            alerts,
            rateLimiter,
            clock,
            wait);
    WebFilter authenticate =
        (exchange, chain) ->
            chain.filter(
                identity == null
                    ? exchange
                    : exchange.mutate().principal(Mono.just(() -> identity)).build());
    return WebTestClient.bindToController(controller).webFilter(authenticate).build();
  }

  private static WebTestClient.ResponseSpec post(WebTestClient client, String body) {
    return client
        .post()
        .uri(IngestionController.PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }
}
