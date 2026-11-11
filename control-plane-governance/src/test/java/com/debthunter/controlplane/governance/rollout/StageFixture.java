package com.debthunter.controlplane.governance.rollout;

import com.debthunter.controlplane.store.rollout.InMemoryStageRepository;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A stage manager over a supplied repository, with a policy and a sink that remembers. */
public final class StageFixture {

  public static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
  public static final String REPO = "acme/widgets";

  public static final StagePolicy POLICY =
      StagePolicy.builder()
          .exitCriteria(RolloutStage.STAGE_1, "baseline-established", "owner-signoff")
          .exitCriteria(RolloutStage.STAGE_2, "false-positive-review")
          .exitCriteria(RolloutStage.STAGE_3)
          .maxHold(RolloutStage.STAGE_1, Duration.ofDays(30))
          .maxHold(RolloutStage.STAGE_2, Duration.ofDays(60))
          .maxHold(RolloutStage.STAGE_3, Duration.ofDays(90))
          .build();

  /** Escalations the sink was given. */
  public final List<StageEscalation> delivered = new ArrayList<>();

  /** Set to make the sink fail. */
  public volatile boolean sinkDown;

  public final StageRepository repository;
  public final StageManager manager;

  public StageFixture() {
    this(new InMemoryStageRepository());
  }

  public StageFixture(StageRepository repository) {
    this.repository = repository;
    this.manager =
        new StageManager(
            repository,
            POLICY,
            escalation -> {
              if (sinkDown) {
                throw new IllegalStateException("sink down");
              }
              delivered.add(escalation);
            });
  }

  public static Instant day(int n) {
    return T0.plus(Duration.ofDays(n));
  }

  public static Map<String, String> stage1Evidence() {
    return Map.of(
        "baseline-established", "https://evidence/baseline/42",
        "owner-signoff", "ticket OPS-17");
  }

  public static Map<String, String> stage2Evidence() {
    return Map.of("false-positive-review", "review 2026-03-20");
  }

  /** Enrols {@link #REPO} and moves it to the given stage at one-day intervals. */
  public StageFixture reach(RolloutStage target) {
    manager.enrol(REPO, "team-widgets", "lead", T0);
    RolloutStage at = RolloutStage.STAGE_1;
    int day = 1;
    while (at != target) {
      Map<String, String> evidence =
          at == RolloutStage.STAGE_1
              ? stage1Evidence()
              : at == RolloutStage.STAGE_2 ? stage2Evidence() : Map.of();
      manager.advance(REPO, "lead", day(day++), evidence);
      at = at.next();
    }
    return this;
  }
}
