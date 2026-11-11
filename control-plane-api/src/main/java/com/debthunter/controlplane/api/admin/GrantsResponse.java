package com.debthunter.controlplane.api.admin;

import java.util.List;

/**
 * The repositories a workload identity may publish for.
 *
 * @param workloadIdentity the identity
 * @param repositories the repositories, sorted
 */
public record GrantsResponse(String workloadIdentity, List<String> repositories) {

  /** Defensively copies the list. */
  public GrantsResponse {
    repositories = List.copyOf(repositories);
  }
}
