package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.portfolio.PortfolioDashboardService;
import java.security.Principal;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The estate by team, for leaders. */
@RestController
@RequestMapping(PortfolioController.PATH)
public class PortfolioController {

  /** The address of the portfolio. */
  public static final String PATH = "/api/v1/portfolio";

  private final PortfolioDashboardService service;

  /**
   * Creates the controller.
   *
   * @param service builds the portfolio
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public PortfolioController(PortfolioDashboardService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * The portfolio.
   *
   * @param principal the caller's token
   * @return the view, or an error
   */
  @GetMapping
  public Mono<ResponseEntity<Object>> portfolio(Mono<Principal> principal) {
    return Web.blocking(principal, service::portfolio);
  }
}
