package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.suppression.SquadSuppressionService;
import com.debthunter.controlplane.serve.suppression.SuppressRequest;
import java.security.Principal;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** A squad setting aside findings in its own repositories, and withdrawing that. */
@RestController
@RequestMapping(SquadViewController.BASE)
public class SquadSuppressionController {

  private final SquadSuppressionService service;

  /**
   * Creates the controller.
   *
   * @param service applies and records suppressions
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public SquadSuppressionController(SquadSuppressionService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * Suppresses a finding of one of the squad's repositories.
   *
   * @param principal the caller's token
   * @param squad the squad
   * @param request what to suppress, why and until when
   * @return {@code 201} with the suppression, or an error
   */
  @PostMapping(path = "/{squad}/suppressions", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<Object>> suppress(
      Mono<Principal> principal, @PathVariable String squad, @RequestBody SuppressRequest request) {
    return Web.blocking(
        principal, HttpStatus.CREATED, caller -> service.suppress(caller, squad, request));
  }

  /**
   * Withdraws a suppression.
   *
   * @param principal the caller's token
   * @param squad the squad
   * @param id the suppression's id
   * @return {@code 204}, or an error
   */
  @DeleteMapping("/{squad}/suppressions/{id}")
  public Mono<ResponseEntity<Object>> revoke(
      Mono<Principal> principal, @PathVariable String squad, @PathVariable String id) {
    return Web.blocking(
        principal,
        HttpStatus.NO_CONTENT,
        caller -> {
          service.revoke(caller, squad, id);
          return null;
        });
  }
}
