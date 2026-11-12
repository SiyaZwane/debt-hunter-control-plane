package com.debthunter.controlplane.ai.remediation;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Links built from a configured base address: {@code <base>/runs/<run id>}. */
public final class ConfiguredRunLinks implements RunLinks {

  private final String base;

  /**
   * Creates the links.
   *
   * @param baseUrl the control plane's public address, with no trailing slash needed
   */
  public ConfiguredRunLinks(String baseUrl) {
    if (baseUrl == null || !(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
      throw new IllegalArgumentException("The base URL must be an http or https address");
    }
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Override
  public Optional<String> linkTo(String runId) {
    return Optional.of(base + "/runs/" + URLEncoder.encode(runId, StandardCharsets.UTF_8));
  }
}
