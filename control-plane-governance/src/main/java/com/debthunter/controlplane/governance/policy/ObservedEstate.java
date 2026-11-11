package com.debthunter.controlplane.governance.policy;

import java.util.List;

/**
 * The observed distribution of findings across the estate: one {@link ObservedRepository} per
 * repository that has recent evidence. Repositories with no usable evidence are not in it, so the
 * sample size is honest about how much the observations can support.
 *
 * @param repositories the observed repositories
 */
public record ObservedEstate(List<ObservedRepository> repositories) {

  /** Defensively copies the list. */
  public ObservedEstate {
    repositories = repositories == null ? List.of() : List.copyOf(repositories);
  }

  /**
   * No observations at all.
   *
   * @return an estate with nothing observed
   */
  public static ObservedEstate none() {
    return new ObservedEstate(List.of());
  }

  /**
   * How many repositories were observed.
   *
   * @return the sample size
   */
  public int sampleSize() {
    return repositories.size();
  }
}
