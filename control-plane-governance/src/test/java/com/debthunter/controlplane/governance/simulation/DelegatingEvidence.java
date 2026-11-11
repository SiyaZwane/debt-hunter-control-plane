package com.debthunter.controlplane.governance.simulation;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import java.time.Instant;
import java.util.List;

/** Forwards every read; tests override the one they want to break or observe. */
public class DelegatingEvidence implements RetainedEvidence {

  private final RetainedEvidence delegate;

  public DelegatingEvidence(RetainedEvidence delegate) {
    this.delegate = delegate;
  }

  @Override
  public List<OnboardedRepository> estate() {
    return delegate.estate();
  }

  @Override
  public List<RunRecord> recentRuns(String repository, int limit) {
    return delegate.recentRuns(repository, limit);
  }

  @Override
  public List<PseudonymisedFinding> findingsOf(String runId) {
    return delegate.findingsOf(runId);
  }

  @Override
  public boolean suppressedAt(String repository, String fingerprint, Instant at) {
    return delegate.suppressedAt(repository, fingerprint, at);
  }
}
