package com.debthunter.controlplane.egress.workitem;

import java.time.Instant;
import java.util.Optional;

/**
 * Where the link between a finding and its tracker item is kept.
 *
 * <p>Writing to a tracker happens under a claim on the finding's link, so that two workers (or a
 * worker that stalled past its lease and a second that took over) cannot both create or move the
 * same finding's item.
 */
public interface WorkItemLinkRepository {

  /** Looks a finding's link up. */
  Optional<WorkItemLink> find(String repository, String projectSlice, String fingerprint);

  /**
   * Records that a finding is going to have an item, if it has no record yet. Whatever is there
   * already is left alone.
   *
   * @return the finding's record, new or existing
   */
  WorkItemLink reserve(
      String repository,
      String projectSlice,
      String fingerprint,
      String itemKey,
      String tracker,
      Instant now);

  /**
   * Takes the right to write to the tracker for a finding.
   *
   * @param owner who is taking it
   * @param now the current time
   * @param until when the claim lapses if never released (a worker that dies)
   * @return false if someone else holds an unexpired claim
   */
  boolean claim(
      String repository,
      String projectSlice,
      String fingerprint,
      String owner,
      Instant now,
      Instant until);

  /** Replaces the link's item, status and time. The record must already exist. */
  void save(WorkItemLink link);

  /** Gives a claim up. Does nothing if the owner no longer holds it. */
  void release(String repository, String projectSlice, String fingerprint, String owner);
}
