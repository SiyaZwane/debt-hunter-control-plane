package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.query.FindingRow;
import com.debthunter.controlplane.serve.query.QueryResult;
import com.debthunter.controlplane.serve.squad.SquadViewService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** A squad's own view of its repositories and findings. */
@RestController
@RequestMapping(SquadViewController.BASE)
public class SquadViewController {

  /** The base address. */
  public static final String BASE = "/api/v1/squads";

  /**
   * A finding with where to go next.
   *
   * @param finding the finding
   * @param detail where to read it with its explanation
   * @param evidence where to retrieve the evidence of the run that supports it (needs the evidence
   *     role and a stated purpose)
   */
  public record LinkedFinding(FindingRow finding, String detail, String evidence) {}

  /**
   * A page of a squad's findings.
   *
   * @param items the findings
   * @param nextCursor where the next page starts, or null
   * @param asOf when the newest run these findings reflect was received
   * @param generatedAt when this was produced
   */
  public record SquadFindings(
      List<LinkedFinding> items, String nextCursor, Instant asOf, Instant generatedAt) {

    /** Copies the items. */
    public SquadFindings {
      items = List.copyOf(items);
    }
  }

  private final SquadViewService service;

  /**
   * Creates the controller.
   *
   * @param service the squad view
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public SquadViewController(SquadViewService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * The squad's repositories at a glance.
   *
   * @param principal the caller's token
   * @param squad the squad
   * @return the overview, or an error
   */
  @GetMapping("/{squad}")
  public Mono<ResponseEntity<Object>> overview(
      Mono<Principal> principal, @PathVariable String squad) {
    return Web.blocking(principal, caller -> service.overview(caller, squad));
  }

  /**
   * The squad's findings.
   *
   * @param principal the caller's token
   * @param squad the squad
   * @param repository only this repository
   * @param severity only this severity
   * @param category only this category
   * @param state only findings in this state
   * @param from only findings whose latest run was committed at or after this instant
   * @param to only findings whose latest run was committed before this instant
   * @param limit page size, at most 200
   * @param cursor where the previous page stopped
   * @return a page with links, or an error
   */
  @GetMapping("/{squad}/findings")
  public Mono<ResponseEntity<Object>> findings(
      Mono<Principal> principal,
      @PathVariable String squad,
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
        caller -> {
          QueryResult result =
              service.findings(
                  caller,
                  squad,
                  Params.of(repository, severity, category, state, from, to, limit, cursor));
          return new SquadFindings(
              result.items().stream().map(f -> link(squad, f)).toList(),
              result.nextCursor(),
              result.asOf(),
              result.generatedAt());
        });
  }

  /**
   * One finding with its explanation.
   *
   * @param principal the caller's token
   * @param squad the squad
   * @param repository the finding's repository
   * @param run the run that reported it
   * @param id its id in that run
   * @return the finding and explanation, or an error
   */
  @GetMapping("/{squad}/finding")
  public Mono<ResponseEntity<Object>> detail(
      Mono<Principal> principal,
      @PathVariable String squad,
      @RequestParam String repository,
      @RequestParam String run,
      @RequestParam String id) {
    return Web.blocking(principal, caller -> service.detail(caller, squad, repository, run, id));
  }

  private static LinkedFinding link(String squad, FindingRow f) {
    return new LinkedFinding(
        f,
        BASE
            + "/"
            + enc(squad)
            + "/finding?repository="
            + enc(f.repository())
            + "&run="
            + enc(f.runId())
            + "&id="
            + enc(f.findingId()),
        EvidenceController.PATH + "/runs/" + enc(f.runId()));
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
