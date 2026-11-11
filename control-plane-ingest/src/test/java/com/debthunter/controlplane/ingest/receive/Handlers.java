package com.debthunter.controlplane.ingest.receive;

import java.util.function.Consumer;

/** Adapts plain consumers to {@link RunBundleHandler} for tests. */
public final class Handlers {

  private Handlers() {}

  /** A handler that runs an action and reports the bundle as processed. */
  public static RunBundleHandler processing(
      Consumer<com.debthunter.controlplane.ingest.intake.IntakeEnvelope> action) {
    return envelope -> {
      action.accept(envelope);
      return RunBundleHandler.Disposition.PROCESSED;
    };
  }
}
