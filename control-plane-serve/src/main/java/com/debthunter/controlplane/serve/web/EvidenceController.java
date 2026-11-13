package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.evidence.EvidenceRetrievalService;
import java.security.Principal;
import java.util.Objects;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Retrieval of a run's evidence for an investigation. */
@RestController
@Conditional(ServeZoneCondition.class)
@RequestMapping(EvidenceController.PATH)
public class EvidenceController {

  /** The base address. */
  public static final String PATH = "/api/v1/evidence";

  private final EvidenceRetrievalService service;

  /**
   * Creates the controller.
   *
   * @param service retrieves evidence
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public EvidenceController(EvidenceRetrievalService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * Retrieves a run's evidence. The request is recorded with the caller and the stated purpose.
   *
   * @param principal the caller's token
   * @param runId the run
   * @param purpose why the evidence is wanted
   * @return everything held about the run, or an error
   */
  @GetMapping("/runs/{runId}")
  public Mono<ResponseEntity<Object>> run(
      Mono<Principal> principal,
      @PathVariable String runId,
      @RequestParam(required = false) String purpose) {
    return Web.blocking(principal, caller -> service.retrieve(caller, runId, purpose));
  }
}
