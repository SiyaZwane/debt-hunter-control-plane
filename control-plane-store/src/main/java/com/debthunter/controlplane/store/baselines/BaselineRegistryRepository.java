package com.debthunter.controlplane.store.baselines;

import java.util.Optional;

/** Persistence for baseline pointers. */
public interface BaselineRegistryRepository {

  /** Inserts or replaces the pointer for a slice at a commit. */
  void save(BaselineRecord baseline);

  /** The pointer for a slice at a commit. */
  Optional<BaselineRecord> find(String repository, String projectSlice, String commit);

  /** The most recently recorded pointer for a slice. */
  Optional<BaselineRecord> latest(String repository, String projectSlice);
}
