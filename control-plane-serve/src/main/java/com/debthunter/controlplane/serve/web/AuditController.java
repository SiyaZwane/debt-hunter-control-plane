package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.audit.AuditReadService;
import java.security.Principal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Read access to the audit trails, for auditors only. */
@RestController
@RequestMapping(AuditController.PATH)
public class AuditController {

  /** The base address. */
  public static final String PATH = "/api/v1/audit";

  private final AuditReadService service;

  /**
   * Creates the controller.
   *
   * @param service reads the trails
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public AuditController(AuditReadService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * Reads a page of a trail.
   *
   * @param principal the caller's token
   * @param trail {@code admin}, {@code reidentification}, {@code suppression} or {@code evidence}
   * @param from the earliest time wanted
   * @param limit the page size
   * @return a page, or an error
   */
  @GetMapping("/{trail}")
  public Mono<ResponseEntity<Object>> read(
      Mono<Principal> principal,
      @PathVariable String trail,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String limit) {
    return Web.blocking(
        principal, caller -> service.read(caller, trailOf(trail), instant(from), integer(limit)));
  }

  private static AuditReadService.Trail trailOf(String value) {
    try {
      return AuditReadService.Trail.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Unknown trail");
    }
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new InvalidRequestException("from must be an ISO-8601 instant");
    }
  }

  private static Integer integer(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      throw new InvalidRequestException("limit must be a whole number");
    }
  }
}
