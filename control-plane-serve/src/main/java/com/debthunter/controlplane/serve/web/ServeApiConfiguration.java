package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.evidence.EvidenceRetrievalService;
import com.debthunter.controlplane.serve.portfolio.PortfolioDashboardService;
import com.debthunter.controlplane.serve.query.QueryService;
import com.debthunter.controlplane.serve.squad.SquadViewService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the read controllers over the services the application supplies. The services carry the
 * access rules, so a controller added here cannot widen access.
 */
@Configuration(proxyBeanMethods = false)
public class ServeApiConfiguration {

  /** The portfolio controller. */
  @Bean
  public PortfolioController portfolioController(PortfolioDashboardService service) {
    return new PortfolioController(service);
  }

  /** The squad-view controller. */
  @Bean
  public SquadViewController squadViewController(SquadViewService service) {
    return new SquadViewController(service);
  }

  /** The query API controller. */
  @Bean
  public QueryApiController queryApiController(QueryService service) {
    return new QueryApiController(service);
  }

  /** The evidence controller. */
  @Bean
  public EvidenceController evidenceController(EvidenceRetrievalService service) {
    return new EvidenceController(service);
  }
}
