package com.debthunter.controlplane.egress.outbox;

/** How far each egress consumer has read in the transition log. */
public interface EgressCheckpoints {

  /**
   * Reads a consumer's position.
   *
   * @param name the consumer
   * @return the last position it has handled, or {@code 0} if it has not started
   */
  long read(String name);

  /**
   * Moves a consumer's position forward. A position behind the current one is ignored, so a late or
   * repeated call cannot send a consumer back.
   *
   * @param name the consumer
   * @param seq the last position handled
   */
  void advance(String name, long seq);
}
