package com.debthunter.controlplane.egress.workitem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * "Look at this finding's work item." It says which finding and which run the news came from, and
 * nothing about what to do: the worker works that out from the finding's state at the time.
 *
 * @param repository the repository
 * @param projectSlice the project slice
 * @param fingerprint the finding's fingerprint
 * @param runId the run whose transition caused the job, which is where the finding's details are
 * @param eventId the transition event, which identifies the job
 */
public record SyncJob(
    String repository, String projectSlice, String fingerprint, String runId, String eventId) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Validates the components. */
  public SyncJob {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(eventId, "eventId");
  }

  /** The ordering key: all of one finding's jobs are handled one at a time, in order. */
  public String queueKey() {
    return repository + '|' + projectSlice + '|' + fingerprint;
  }

  /** The job as bytes. */
  public byte[] encode() {
    try {
      return JSON.writeValueAsBytes(this);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("A sync job could not be written", e);
    }
  }

  /**
   * Reads a job.
   *
   * @param bytes what {@link #encode} wrote
   * @return the job
   * @throws IllegalArgumentException if the bytes are not a job
   */
  public static SyncJob decode(byte[] bytes) {
    try {
      return JSON.readValue(new String(bytes, StandardCharsets.UTF_8), SyncJob.class);
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("Not a sync job", e);
    }
  }
}
