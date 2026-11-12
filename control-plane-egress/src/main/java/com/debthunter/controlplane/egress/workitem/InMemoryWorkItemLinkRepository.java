package com.debthunter.controlplane.egress.workitem;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Keeps links in memory. For tests and for running without a database. */
public final class InMemoryWorkItemLinkRepository implements WorkItemLinkRepository {

  private record Claim(String owner, Instant until) {}

  private final Map<String, WorkItemLink> links = new HashMap<>();
  private final Map<String, Claim> claims = new HashMap<>();

  private static String id(String repository, String slice, String fingerprint) {
    return repository + '\u0000' + slice + '\u0000' + fingerprint;
  }

  @Override
  public synchronized Optional<WorkItemLink> find(
      String repository, String projectSlice, String fingerprint) {
    return Optional.ofNullable(links.get(id(repository, projectSlice, fingerprint)));
  }

  @Override
  public synchronized WorkItemLink reserve(
      String repository,
      String projectSlice,
      String fingerprint,
      String itemKey,
      String tracker,
      Instant now) {
    return links.computeIfAbsent(
        id(repository, projectSlice, fingerprint),
        k ->
            new WorkItemLink(
                repository, projectSlice, fingerprint, itemKey, tracker, null, null, null, now));
  }

  @Override
  public synchronized boolean claim(
      String repository,
      String projectSlice,
      String fingerprint,
      String owner,
      Instant now,
      Instant until) {
    String id = id(repository, projectSlice, fingerprint);
    if (!links.containsKey(id)) {
      return false;
    }
    Claim held = claims.get(id);
    if (held != null && held.until().isAfter(now)) {
      return false;
    }
    claims.put(id, new Claim(owner, until));
    return true;
  }

  @Override
  public synchronized void save(WorkItemLink link) {
    String id = id(link.repository(), link.projectSlice(), link.fingerprint());
    if (!links.containsKey(id)) {
      throw new IllegalStateException("No link has been reserved for this finding");
    }
    links.put(id, link);
  }

  @Override
  public synchronized void release(
      String repository, String projectSlice, String fingerprint, String owner) {
    String id = id(repository, projectSlice, fingerprint);
    Claim held = claims.get(id);
    if (held != null && held.owner().equals(owner)) {
      claims.remove(id);
    }
  }
}
