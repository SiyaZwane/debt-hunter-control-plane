package com.debthunter.controlplane.serve.web;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Whether the serve zone is switched on ({@code debthunter.controlplane.zones.serve.enabled}, true
 * unless set otherwise). The serve configuration is under the application's component-scan root, so
 * without this a deployment that switched the zone off would still try to build it.
 */
final class ServeZoneCondition implements Condition {

  static final String PROPERTY = "debthunter.controlplane.zones.serve.enabled";

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return context.getEnvironment().getProperty(PROPERTY, Boolean.class, Boolean.TRUE);
  }
}
