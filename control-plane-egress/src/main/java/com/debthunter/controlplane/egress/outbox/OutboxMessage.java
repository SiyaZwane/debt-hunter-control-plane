package com.debthunter.controlplane.egress.outbox;

import java.util.Arrays;
import java.util.Objects;

/**
 * A message handed to a handler.
 *
 * @param id the queue item's id
 * @param key the ordering key
 * @param payload what to deliver
 * @param attempts how many times it has now been tried, including this one
 */
public record OutboxMessage(long id, String key, byte[] payload, int attempts) {

  /** Validates and copies the components. */
  public OutboxMessage {
    Objects.requireNonNull(key, "key");
    payload = Arrays.copyOf(payload, payload.length);
  }

  /** A copy of the payload. */
  @Override
  public byte[] payload() {
    return Arrays.copyOf(payload, payload.length);
  }
}
