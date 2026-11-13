package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.audit.AuditReadService;
import com.debthunter.controlplane.serve.evidence.EvidenceRetrievalService;
import com.debthunter.controlplane.serve.portfolio.PortfolioDashboardService;
import com.debthunter.controlplane.serve.query.QueryService;
import com.debthunter.controlplane.serve.squad.SquadViewService;
import com.debthunter.controlplane.serve.suppression.SquadSuppressionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Wires the read controllers over the services the application supplies. The services carry the
 * access rules, so a controller added here cannot widen access.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(ServeZoneCondition.class)
public class ServeApiConfiguration implements WebFluxConfigurer {

  /**
   * Instants are written as ISO-8601 text whatever hosts the API, so a reader of a response, an
   * auditor above all, is never handed epoch numbers.
   */
  @Override
  public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
    ObjectMapper mapper =
        Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
    configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper));
  }

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

  /** The squad suppression controller. */
  @Bean
  public SquadSuppressionController squadSuppressionController(SquadSuppressionService service) {
    return new SquadSuppressionController(service);
  }

  /** The audit controller. */
  @Bean
  public AuditController auditController(AuditReadService service) {
    return new AuditController(service);
  }
}
