package com.debthunter.controlplane.analytics.readmodel;

import java.time.Instant;
import java.util.Objects;

/**
 * How often a hotspot file was changing at a commit.
 *
 * @param repository the repository
 * @param projectSlice the project slice
 * @param commit the commit
 * @param path the file
 * @param committedAt the commit date
 * @param changeFrequency the engine's measure of how often the file changes
 */
public record HotspotPoint(
    String repository,
    String projectSlice,
    String commit,
    String path,
    Instant committedAt,
    double changeFrequency) {

  /** Validates the components. */
  public HotspotPoint {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(committedAt, "committedAt");
  }
}
