package com.debthunter.controlplane.api.admin;

import java.util.List;

/**
 * Every onboarded repository.
 *
 * @param repositories the entries, ordered by repository identifier
 */
public record RepositoryListResponse(List<RepositoryResponse> repositories) {

  /** Defensively copies the list. */
  public RepositoryListResponse {
    repositories = List.copyOf(repositories);
  }
}
