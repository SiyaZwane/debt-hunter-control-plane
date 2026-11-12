package com.debthunter.controlplane.api.ingestion;

import com.debthunter.controlplane.store.jdbc.StoreException;
import java.util.concurrent.TimeoutException;

/** Recognises a failure of the registry the scope check reads, as opposed to a bug. */
final class ScopeLookupFailure {

  private ScopeLookupFailure() {}

  /**
   * Whether a failure means the registry (the database) could not be read in time, which the caller
   * should be told to retry, rather than an unexpected error.
   */
  static boolean isDependencyFailure(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof StoreException || t instanceof TimeoutException) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }
}
