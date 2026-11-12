package com.debthunter.controlplane.ai.remediation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A queued request to propose a remediation. It holds identifiers only: the finding itself is read
 * when the request is handled, so no finding content, and nothing about contributors, is ever in
 * the queue.
 *
 * @param repository the repository
 * @param runId the run the finding was reported in
 * @param findingId the finding
 * @param baseBranch the branch the pull request should target
 * @param requestedBy who asked
 */
public record RemediationJob(
    String repository, String runId, String findingId, String baseBranch, String requestedBy) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Encodes the job. */
  public byte[] encode() {
    try {
      return JSON.writeValueAsBytes(this);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not encode a remediation job", e);
    }
  }

  /**
   * Decodes a job.
   *
   * @param bytes what {@link #encode} produced
   * @return the job
   * @throws IllegalArgumentException if the bytes are not a job
   */
  public static RemediationJob decode(byte[] bytes) {
    try {
      RemediationJob job = JSON.readValue(bytes, RemediationJob.class);
      if (job.repository() == null
          || job.runId() == null
          || job.findingId() == null
          || job.baseBranch() == null) {
        throw new IllegalArgumentException("Incomplete remediation job");
      }
      return job;
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Unreadable remediation job", e);
    }
  }
}
