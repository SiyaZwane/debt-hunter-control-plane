package com.debthunter.controlplane.analytics.readmodel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A {@link ReadModelRepository} held in memory, for tests and local development. */
public final class InMemoryReadModelRepository implements ReadModelRepository {

  private final Map<String, Long> checkpoints = new HashMap<>();
  private Instant heartbeat;
  private final Map<String, RunSnapshot> snapshots = new LinkedHashMap<>();
  private final Map<String, HotspotPoint> hotspots = new LinkedHashMap<>();
  private final Map<String, RuleDelta> ruleDaily = new LinkedHashMap<>();
  private final Map<String, OpenFinding> open = new LinkedHashMap<>();
  private final Map<String, ResolutionRecord> resolutions = new LinkedHashMap<>();

  private static String sliceKey(String repository, String slice) {
    return repository + '\u0000' + slice;
  }

  @Override
  public synchronized long checkpoint(String name) {
    return checkpoints.getOrDefault(name, 0L);
  }

  @Override
  public synchronized Optional<Instant> lastUpdated() {
    return Optional.ofNullable(heartbeat);
  }

  @Override
  public synchronized Optional<OpenFinding> openFinding(
      String repository, String projectSlice, String fingerprint) {
    return Optional.ofNullable(
        open.get(sliceKey(repository, projectSlice) + '\u0000' + fingerprint));
  }

  @Override
  public synchronized void commit(ProjectionBatch batch, Instant now) {
    checkpoints.putAll(batch.checkpoints());
    for (OpenFinding gone : batch.closed()) {
      open.remove(sliceKey(gone.repository(), gone.projectSlice()) + '\u0000' + gone.fingerprint());
    }
    for (OpenFinding f : batch.opened()) {
      open.put(sliceKey(f.repository(), f.projectSlice()) + '\u0000' + f.fingerprint(), f);
    }
    for (ResolutionRecord r : batch.resolutions()) {
      resolutions.putIfAbsent(
          sliceKey(r.repository(), r.projectSlice())
              + '\u0000'
              + r.fingerprint()
              + '\u0000'
              + r.resolvedAt(),
          r);
    }
    for (RuleDelta d : batch.ruleDeltas()) {
      ruleDaily.merge(
          sliceKey(d.repository(), d.projectSlice()) + '\u0000' + d.ruleId() + '\u0000' + d.day(),
          d,
          (a, b) ->
              new RuleDelta(
                  a.repository(),
                  a.projectSlice(),
                  a.ruleId(),
                  a.day(),
                  a.created() + b.created(),
                  a.resolved() + b.resolved(),
                  a.regressed() + b.regressed()));
    }
    for (RunSnapshot s : batch.snapshots()) {
      String commitKey = sliceKey(s.repository(), s.projectSlice()) + '\u0000' + s.commit();
      snapshots.put(commitKey, s);
      hotspots.keySet().removeIf(k -> k.startsWith(commitKey + '\u0000'));
    }
    for (HotspotPoint h : batch.hotspots()) {
      hotspots.put(
          sliceKey(h.repository(), h.projectSlice()) + '\u0000' + h.commit() + '\u0000' + h.path(),
          h);
    }
    heartbeat = now;
  }

  @Override
  public synchronized List<RunSnapshot> latestSnapshots(java.util.Collection<String> repositories) {
    java.util.Map<String, RunSnapshot> latest = new java.util.HashMap<>();
    for (RunSnapshot s : snapshots.values()) {
      if (repositories.contains(s.repository())) {
        latest.merge(
            sliceKey(s.repository(), s.projectSlice()),
            s,
            (a, b) -> b.committedAt().isAfter(a.committedAt()) ? b : a);
      }
    }
    return latest.values().stream()
        .sorted(
            Comparator.comparing(RunSnapshot::repository).thenComparing(RunSnapshot::projectSlice))
        .toList();
  }

  @Override
  public synchronized List<RunSnapshot> snapshots(
      String repository, String projectSlice, Instant from, Instant to) {
    return snapshots.values().stream()
        .filter(s -> s.repository().equals(repository) && s.projectSlice().equals(projectSlice))
        .filter(s -> !s.committedAt().isBefore(from) && s.committedAt().isBefore(to))
        .sorted(Comparator.comparing(RunSnapshot::committedAt))
        .toList();
  }

  @Override
  public synchronized List<RuleDelta> ruleDeltas(
      String repository, String projectSlice, Instant from, Instant to) {
    LocalDate fromDay = from.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate toDay = to.atZone(ZoneOffset.UTC).toLocalDate();
    return ruleDaily.values().stream()
        .filter(d -> d.repository().equals(repository) && d.projectSlice().equals(projectSlice))
        .filter(d -> !d.day().isBefore(fromDay) && d.day().isBefore(toDay))
        .sorted(Comparator.comparing(RuleDelta::day).thenComparing(RuleDelta::ruleId))
        .toList();
  }

  @Override
  public synchronized List<ResolutionRecord> resolutions(
      String repository, String projectSlice, Instant from, Instant to) {
    return resolutions.values().stream()
        .filter(r -> r.repository().equals(repository) && r.projectSlice().equals(projectSlice))
        .filter(r -> !r.resolvedAt().isBefore(from) && r.resolvedAt().isBefore(to))
        .sorted(Comparator.comparing(ResolutionRecord::resolvedAt))
        .toList();
  }

  @Override
  public synchronized List<OpenFinding> openFindings(String repository, String projectSlice) {
    return new ArrayList<>(
        open.values().stream()
            .filter(f -> f.repository().equals(repository) && f.projectSlice().equals(projectSlice))
            .sorted(
                Comparator.comparing(OpenFinding::since).thenComparing(OpenFinding::fingerprint))
            .toList());
  }

  @Override
  public synchronized List<HotspotPoint> hotspots(
      String repository, String projectSlice, Instant from, Instant to) {
    return hotspots.values().stream()
        .filter(h -> h.repository().equals(repository) && h.projectSlice().equals(projectSlice))
        .filter(h -> !h.committedAt().isBefore(from) && h.committedAt().isBefore(to))
        .sorted(Comparator.comparing(HotspotPoint::committedAt).thenComparing(HotspotPoint::path))
        .toList();
  }
}
