package com.debthunter.controlplane.ingest.pseudonymise;

import java.util.List;

/**
 * Hands a pseudonymised bundle to several stages in turn. If one throws, the later ones do not run
 * and the bundle is retried, so every stage must be idempotent.
 */
public final class CompositeRunSink implements PseudonymisedRunSink {

  private final List<PseudonymisedRunSink> sinks;

  /**
   * Creates the composite.
   *
   * @param sinks the stages, in order
   */
  public CompositeRunSink(List<PseudonymisedRunSink> sinks) {
    this.sinks = List.copyOf(sinks);
  }

  @Override
  public void accept(PseudonymisedRunBundle bundle) {
    for (PseudonymisedRunSink sink : sinks) {
      sink.accept(bundle);
    }
  }
}
