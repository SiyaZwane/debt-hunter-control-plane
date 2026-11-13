package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.query.QueryService;
import java.security.Principal;
import java.util.Objects;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The programmatic read of findings. */
@RestController
@Conditional(ServeZoneCondition.class)
@RequestMapping(QueryApiController.PATH)
public class QueryApiController {

  /** The address of the query API. */
  public static final String PATH = "/api/v1/findings";

  private final QueryService service;

  /**
   * Creates the controller.
   *
   * @param service runs the queries
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public QueryApiController(QueryService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * Finds findings in the repositories of the caller's squads.
   *
   * @param principal the caller's token
   * @param repository only this repository
   * @param severity only this severity
   * @param category only this category
   * @param state only findings in this state
   * @param from only findings whose latest run was committed at or after this instant
   * @param to only findings whose latest run was committed before this instant
   * @param limit page size, at most 200
   * @param cursor where the previous page stopped
   * @return a page with when the data is as of, or an error
   */
  @GetMapping
  public Mono<ResponseEntity<Object>> find(
      Mono<Principal> principal,
      @RequestParam(required = false) String repository,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String limit,
      @RequestParam(required = false) String cursor) {
    return Web.blocking(
        principal,
        caller ->
            service.query(
                caller, Params.of(repository, severity, category, state, from, to, limit, cursor)));
  }
}
