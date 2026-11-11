package com.debthunter.controlplane.api.admin;

import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the administration API. The registry and clock come from the application; the audit trail
 * defaults to the application log unless the application supplies its own.
 */
@Configuration(proxyBeanMethods = false)
public class AdminApiConfiguration {

  /**
   * The administration controller.
   *
   * @param registry the repository registry
   * @param audit the audit log, if the application supplies one
   * @param clock the clock
   * @return the controller
   */
  @Bean
  public AdminController adminController(
      RepositoryRegistryService registry, ObjectProvider<AdminAuditLog> audit, Clock clock) {
    return new AdminController(registry, audit.getIfAvailable(LoggingAdminAuditLog::new), clock);
  }
}
