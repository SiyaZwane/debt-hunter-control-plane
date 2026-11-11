package com.debthunter.controlplane.api.admin;

import com.debthunter.controlplane.api.ingestion.ApiError;
import com.debthunter.controlplane.store.registry.AlreadyOnboardedException;
import com.debthunter.controlplane.store.registry.NotOnboardedException;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Administration of who the control plane serves: which repositories are onboarded, where their
 * work items go, and which workload identities may publish for them.
 *
 * <p>Every route requires the administrator role (enforced by the security chain, not here), so a
 * pipeline's token cannot reach any of it. Each change is written to the audit log <em>before</em>
 * it is applied, and if that write fails the change is not made. Reads are not audited.
 *
 * <p>The registry is backed by a blocking database, so work runs off the event loop.
 */
@RestController
@RequestMapping(AdminController.BASE)
public class AdminController {

  /** The base path of every administration route. */
  public static final String BASE = "/api/v1/admin";

  private static final Logger log = LoggerFactory.getLogger(AdminController.class);

  private final RepositoryRegistryService registry;
  private final AdminAuditLog audit;
  private final Clock clock;

  /**
   * Creates the controller.
   *
   * @param registry the repository registry
   * @param audit where changes are recorded
   * @param clock the source of audit timestamps
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public AdminController(RepositoryRegistryService registry, AdminAuditLog audit, Clock clock) {
    this.registry = registry;
    this.audit = audit;
    this.clock = clock;
  }

  // ---- repositories ----------------------------------------------------------------------------

  /**
   * Onboards a repository.
   *
   * @param principal the authenticated administrator
   * @param body the entry
   * @return {@code 201} with the entry; {@code 409} if already onboarded; {@code 400} if invalid
   */
  @PostMapping(path = "/repositories", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Object>> onboard(
      Mono<Principal> principal, @RequestBody RepositoryRequest body) {
    return mutate(
        principal,
        () -> toEntry(AdminInput.text("repository", body.repository()), body),
        "ONBOARD_REPOSITORY",
        entry -> "repository=" + entry.repository(),
        entry -> {
          registry.onboard(entry);
          return ResponseEntity.created(location(entry.repository()))
              .body(RepositoryResponse.from(entry));
        });
  }

  /**
   * Lists every onboarded repository.
   *
   * @return the entries
   */
  @GetMapping("/repositories")
  public Mono<ResponseEntity<Object>> list() {
    return blocking(
        () ->
            ResponseEntity.ok(
                new RepositoryListResponse(
                    registry.all().stream().map(RepositoryResponse::from).toList())));
  }

  /**
   * Reads one repository's entry.
   *
   * @param repository the identifier, captured from the rest of the path
   * @return the entry, or {@code 404}
   */
  @GetMapping("/repositories/{*repository}")
  public Mono<ResponseEntity<Object>> get(@PathVariable String repository) {
    return blocking(
        () ->
            registry
                .find(AdminInput.repositoryFromPath(repository))
                .<ResponseEntity<Object>>map(e -> ResponseEntity.ok(RepositoryResponse.from(e)))
                .orElseGet(() -> notOnboarded(repository)));
  }

  /**
   * Changes an onboarded repository's entry.
   *
   * @param principal the authenticated administrator
   * @param repository the identifier, captured from the rest of the path
   * @param body the new entry; a {@code repository} field, if present, must match the path
   * @return the entry; {@code 404} if not onboarded; {@code 400} if invalid
   */
  @PutMapping(path = "/repositories/{*repository}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Object>> update(
      Mono<Principal> principal,
      @PathVariable String repository,
      @RequestBody RepositoryRequest body) {
    return mutate(
        principal,
        () -> {
          String id = AdminInput.repositoryFromPath(repository);
          if (body.repository() != null && !body.repository().equals(id)) {
            throw new InvalidAdminInputException("repository in the body does not match the URL");
          }
          return toEntry(id, body);
        },
        "UPDATE_REPOSITORY",
        entry -> "repository=" + entry.repository(),
        entry -> {
          registry.update(entry);
          return ResponseEntity.ok(RepositoryResponse.from(entry));
        });
  }

  /**
   * Offboards a repository, withdrawing every workload's permission to publish for it.
   *
   * @param principal the authenticated administrator
   * @param repository the identifier, captured from the rest of the path
   * @return {@code 204}, or {@code 404} if it was not onboarded
   */
  @DeleteMapping("/repositories/{*repository}")
  public Mono<ResponseEntity<Object>> offboard(
      Mono<Principal> principal, @PathVariable String repository) {
    return mutate(
        principal,
        () -> AdminInput.repositoryFromPath(repository),
        "OFFBOARD_REPOSITORY",
        id -> "repository=" + id,
        id ->
            registry.offboard(id) ? ResponseEntity.noContent().<Object>build() : notOnboarded(id));
  }

  // ---- workload grants -------------------------------------------------------------------------

  /**
   * Allows a workload identity to publish for an onboarded repository. Idempotent.
   *
   * @param principal the authenticated administrator
   * @param workloadIdentity the identity, as it appears in its token's {@code sub}
   * @param repository the repository
   * @return {@code 204}; {@code 404} if the repository is not onboarded
   */
  @PutMapping("/grants")
  public Mono<ResponseEntity<Object>> grant(
      Mono<Principal> principal,
      @RequestParam String workloadIdentity,
      @RequestParam String repository) {
    return mutate(
        principal,
        () ->
            new String[] {
              AdminInput.text("workloadIdentity", workloadIdentity),
              AdminInput.text("repository", repository)
            },
        "GRANT_WORKLOAD",
        pair -> "workload=" + pair[0] + " repository=" + pair[1],
        pair -> {
          registry.grantWorkload(pair[0], pair[1]);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Withdraws a workload identity's permission to publish for a repository. Idempotent.
   *
   * @param principal the authenticated administrator
   * @param workloadIdentity the identity
   * @param repository the repository
   * @return {@code 204}
   */
  @DeleteMapping("/grants")
  public Mono<ResponseEntity<Object>> revoke(
      Mono<Principal> principal,
      @RequestParam String workloadIdentity,
      @RequestParam String repository) {
    return mutate(
        principal,
        () ->
            new String[] {
              AdminInput.text("workloadIdentity", workloadIdentity),
              AdminInput.text("repository", repository)
            },
        "REVOKE_WORKLOAD",
        pair -> "workload=" + pair[0] + " repository=" + pair[1],
        pair -> {
          registry.revokeWorkload(pair[0], pair[1]);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Lists the repositories a workload identity may publish for.
   *
   * @param workloadIdentity the identity
   * @return its repositories (empty if it has none)
   */
  @GetMapping("/grants")
  public Mono<ResponseEntity<Object>> grants(@RequestParam String workloadIdentity) {
    return blocking(
        () -> {
          String identity = AdminInput.text("workloadIdentity", workloadIdentity);
          List<String> repositories =
              registry.repositoriesOf(identity).stream().sorted(Comparator.naturalOrder()).toList();
          return ResponseEntity.ok(new GrantsResponse(identity, repositories));
        });
  }

  // ---- plumbing --------------------------------------------------------------------------------

  /**
   * Validates, then audits, then applies a change.
   *
   * @param principal the authenticated administrator
   * @param validate turns the request into a validated value; rejects with 400 if it cannot
   * @param action the audit action name
   * @param target describes the value for the audit trail
   * @param apply makes the change
   */
  private <T> Mono<ResponseEntity<Object>> mutate(
      Mono<Principal> principal,
      Callable<T> validate,
      String action,
      java.util.function.Function<T, String> target,
      java.util.function.Function<T, ResponseEntity<Object>> apply) {
    return principal
        .map(Principal::getName)
        .flatMap(
            caller ->
                blocking(
                    () -> {
                      T value = validate.call();
                      // Recorded before it is applied: if this throws, nothing changes.
                      audit.record(
                          new AdminAuditEntry(
                              caller, action, target.apply(value), clock.instant()));
                      return apply.apply(value);
                    }));
  }

  private Mono<ResponseEntity<Object>> blocking(Callable<ResponseEntity<Object>> work) {
    return Mono.fromCallable(work)
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(AdminController::toError);
  }

  private static Mono<ResponseEntity<Object>> toError(Throwable failure) {
    if (failure instanceof InvalidAdminInputException
        || failure instanceof IllegalArgumentException) {
      return error(HttpStatus.BAD_REQUEST, "invalid_request", failure.getMessage());
    }
    if (failure instanceof AlreadyOnboardedException) {
      return error(
          HttpStatus.CONFLICT, "already_onboarded", "That repository is already onboarded");
    }
    if (failure instanceof NotOnboardedException) {
      return error(HttpStatus.NOT_FOUND, "not_onboarded", "That repository is not onboarded");
    }
    log.error("administration request failed", failure);
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "The request could not be completed");
  }

  private static Mono<ResponseEntity<Object>> error(
      HttpStatus status, String code, String message) {
    return Mono.just(ResponseEntity.status(status).body(ApiError.of(code, message)));
  }

  private static ResponseEntity<Object> notOnboarded(String repository) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiError.of("not_onboarded", "That repository is not onboarded"));
  }

  private static URI location(String repository) {
    return URI.create(BASE + "/repositories/" + repository);
  }

  private static OnboardedRepository toEntry(String repository, RepositoryRequest body) {
    if (body.route() == null) {
      throw new InvalidAdminInputException("route is required");
    }
    return new OnboardedRepository(
        repository,
        AdminInput.text("owner", body.owner()),
        AdminInput.text("tier", body.tier()),
        AdminInput.text("policyBundleVersion", body.policyBundleVersion()),
        AdminInput.text("rolloutStage", body.rolloutStage()),
        new WorkItemRoute(
            AdminInput.text("route.tracker", body.route().tracker()),
            AdminInput.text("route.target", body.route().target())));
  }
}
