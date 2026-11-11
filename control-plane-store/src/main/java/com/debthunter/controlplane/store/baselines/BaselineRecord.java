package com.debthunter.controlplane.store.baselines;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a baseline lives. This is a <b>pointer</b>: the baseline artefact itself stays in the
 * container registry, and the control plane records only enough to find and verify it. There is
 * deliberately no field that could hold content.
 *
 * @param repository the repository
 * @param projectSlice the project slice
 * @param commit the commit the baseline was taken at
 * @param ociReference the artefact's location, as {@code host/name@sha256:digest}
 * @param digest the artefact's digest, matching the reference
 * @param createdAt when the baseline was recorded
 * @param provenance who or what produced it
 */
public record BaselineRecord(
    String repository,
    String projectSlice,
    String commit,
    String ociReference,
    String digest,
    Instant createdAt,
    String provenance) {

  private static final Pattern REFERENCE =
      Pattern.compile(
          "[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]+)?/[a-z0-9._/-]+@(sha256:[0-9a-f]{64})");

  /** Validates the components, including that the reference really is an OCI digest reference. */
  public BaselineRecord {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(provenance, "provenance");
    var matcher = REFERENCE.matcher(Objects.requireNonNull(ociReference, "ociReference"));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "A baseline must be recorded as an OCI reference by digest, host/name@sha256:<64 hex>");
    }
    if (!matcher.group(3).equals(digest)) {
      throw new IllegalArgumentException("The digest must match the one in the OCI reference");
    }
  }
}
