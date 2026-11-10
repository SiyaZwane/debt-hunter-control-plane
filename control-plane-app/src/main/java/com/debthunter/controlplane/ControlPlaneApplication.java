package com.debthunter.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the Debt Hunter control plane.
 *
 * <p>Lives in the root {@code com.debthunter.controlplane} package so component scanning picks up
 * every zone's sub-package ({@code api}, {@code ingest}, {@code store}, ...). This is the only
 * module in which Spring Boot is permitted; all business logic lives in the non-framework zones.
 */
@SpringBootApplication
public class ControlPlaneApplication {

  /**
   * Starts the control plane.
   *
   * @param args command-line arguments passed through to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(ControlPlaneApplication.class, args);
  }
}
