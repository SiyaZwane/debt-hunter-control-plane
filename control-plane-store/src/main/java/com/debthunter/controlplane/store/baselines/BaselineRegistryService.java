package com.debthunter.controlplane.store.baselines;

import java.util.Objects;
import java.util.Optional;

/**
 * Records where baselines live. The artefacts themselves are in the container registry; this
 * service keeps only the pointer and the metadata needed to find and verify it.
 */
public final class BaselineRegistryService {

  private final BaselineRegistryRepository baselines;

  /**
   * Creates the service.
   *
   * @param baselines where pointers are kept
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public BaselineRegistryService(BaselineRegistryRepository baselines) {
    this.baselines = Objects.requireNonNull(baselines, "baselines");
  }

  /**
   * Records a baseline pointer.
   *
   * @param baseline the pointer
   * @throws IllegalArgumentException (from the record) if the reference is not an OCI digest
   *     reference
   */
  public void register(BaselineRecord baseline) {
    baselines.save(baseline);
  }

  /** The pointer for a slice at a commit. */
  public Optional<BaselineRecord> forCommit(String repository, String projectSlice, String commit) {
    return baselines.find(repository, projectSlice, commit);
  }

  /** The most recently recorded pointer for a slice. */
  public Optional<BaselineRecord> latest(String repository, String projectSlice) {
    return baselines.latest(repository, projectSlice);
  }
}
