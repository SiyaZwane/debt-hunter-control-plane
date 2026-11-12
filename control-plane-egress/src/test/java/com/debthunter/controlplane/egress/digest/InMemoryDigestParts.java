package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.egress.MutableClock;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.InMemoryTransitionLogRepository;
import com.debthunter.controlplane.store.queue.InMemoryWorkQueue;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry;
import com.debthunter.controlplane.store.suppressions.InMemorySuppressionRepository;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Stores that live in memory, for unit tests. */
public final class InMemoryDigestParts {

  public static final Map<String, com.debthunter.controlplane.store.queue.QueuePolicy> POLICIES =
      DigestQueue.policies(3, Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(8));

  private InMemoryDigestParts() {}

  /** Open counts a test sets by hand. */
  public static final class FakeOpen implements OpenFindingCounts {
    public final Map<String, Map<Severity, Integer>> byRepository = new HashMap<>();

    @Override
    public Map<Severity, Integer> openBy(String repository) {
      return byRepository.getOrDefault(repository, new EnumMap<>(Severity.class));
    }
  }

  public static DigestHarness.Parts create(MutableClock clock, OpenFindingCounts open) {
    return new DigestHarness.Parts(
        new InMemoryTransitionLogRepository(),
        new InMemoryPseudonymisedFindingStore(),
        new InMemorySuppressionRepository(),
        new InMemoryRepositoryRegistry(),
        new InMemoryDigestLedger(),
        new InMemorySquadChannelRepository(),
        open,
        new InMemoryWorkQueue(POLICIES, clock));
  }

  public static DigestHarness harness() {
    MutableClock clock = new MutableClock();
    return new DigestHarness(create(clock, new FakeOpen()), clock);
  }

  public static DigestHarness harness(DigestSettings settings) {
    MutableClock clock = new MutableClock();
    return new DigestHarness(create(clock, new FakeOpen()), clock, settings);
  }
}
