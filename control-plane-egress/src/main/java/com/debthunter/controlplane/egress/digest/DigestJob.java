package com.debthunter.controlplane.egress.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * "Send this squad's digest for this period." It names the ledger row; the digest itself is read
 * from the ledger, as built, when it is sent.
 *
 * @param owner the squad
 * @param periodStart the start of the period, ISO-8601
 */
public record DigestJob(String owner, String periodStart) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Validates the components. */
  public DigestJob {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(periodStart, "periodStart");
  }

  /** The idempotency key: one message per squad per period. */
  public String idempotencyKey() {
    return owner + '|' + periodStart;
  }

  /** The start of the period. */
  public Instant start() {
    return Instant.parse(periodStart);
  }

  /** The job as bytes. */
  public byte[] encode() {
    try {
      return JSON.writeValueAsBytes(this);
    } catch (IOException e) {
      throw new IllegalStateException("A digest job could not be written", e);
    }
  }

  /**
   * Reads a job.
   *
   * @param bytes what {@link #encode} wrote
   * @return the job
   * @throws IllegalArgumentException if the bytes are not a job
   */
  public static DigestJob decode(byte[] bytes) {
    try {
      DigestJob job = JSON.readValue(new String(bytes, StandardCharsets.UTF_8), DigestJob.class);
      job.start();
      return job;
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("Not a digest job", e);
    }
  }
}
