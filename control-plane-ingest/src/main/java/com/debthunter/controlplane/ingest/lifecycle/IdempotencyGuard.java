package com.debthunter.controlplane.ingest.lifecycle;

import com.debthunter.controlplane.store.runs.RunRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Decides whether a run is something to process, discard, or treat as a re-run, using two keys:
 *
 * <ol>
 *   <li><b>The run id.</b> A run seen before is a redelivery: acknowledge and discard.
 *   <li><b>A composite key</b> of repository, commit, project slice and tool version. A different
 *       run id with the same composite key is a re-run of the same analysis: it supersedes the
 *       earlier one, and both are retained.
 * </ol>
 *
 * <p>The guard is read-only. A run is recorded as seen only when it has been fully applied, so a
 * failure part-way through leaves it unseen and the retry processes it.
 */
public final class IdempotencyGuard {

  /** What to do with an arriving run. */
  public enum Decision {
    /** First arrival: process normally. */
    FIRST,
    /** The same run id was already applied: discard. */
    DUPLICATE,
    /** A new run id for an analysis already applied: process, and supersede the earlier run. */
    RERUN
  }

  private final RunRepository runs;

  /**
   * Creates a guard.
   *
   * @param runs the run store
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public IdempotencyGuard(RunRepository runs) {
    this.runs = Objects.requireNonNull(runs, "runs");
  }

  /**
   * Classifies an arriving run.
   *
   * @param runId the run's own id
   * @param compositeKey the run's {@link #compositeKey composite key}
   * @return what to do with it
   */
  public Decision classify(String runId, String compositeKey) {
    if (runs.find(runId).isPresent()) {
      return Decision.DUPLICATE;
    }
    return runs.findByCompositeKey(compositeKey).isEmpty() ? Decision.FIRST : Decision.RERUN;
  }

  /**
   * Computes the composite key: {@code sha256(repository, commit, projectSlice, toolVersion)}.
   *
   * <p>The parts are length-prefixed rather than simply concatenated, because plain concatenation
   * is ambiguous: {@code ("ab","c")} and {@code ("a","bc")} would collide.
   *
   * @param repository the repository
   * @param commit the commit
   * @param projectSlice the project slice
   * @param toolVersion the data-plane version
   * @return 64 lowercase hex characters
   */
  public static String compositeKey(
      String repository, String commit, String projectSlice, String toolVersion) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : new String[] {repository, commit, projectSlice, toolVersion}) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
