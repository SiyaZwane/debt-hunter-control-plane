package com.debthunter.controlplane.store.rollout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class StageRepositoryContract {

  static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
  static final Instant T1 = Instant.parse("2026-04-01T10:00:00Z");

  protected abstract StageRepository newRepository();

  private StageRepository repository;

  @BeforeEach
  void create() {
    repository = newRepository();
  }

  private static StageRecord record(String repo, RolloutStage stage, Instant from) {
    return new StageRecord(repo, stage, from, "team-a", Map.of(), null, null);
  }

  private static StageEvent enrolled(String repo, Instant at) {
    return new StageEvent(
        repo, StageEvent.Kind.ENROLLED, null, RolloutStage.STAGE_1, at, "lead", Map.of(), null);
  }

  private static StageEvent advanced(
      String repo, RolloutStage from, RolloutStage to, Instant at, Map<String, String> criteria) {
    return new StageEvent(repo, StageEvent.Kind.ADVANCED, from, to, at, "lead", criteria, null);
  }

  @Test
  void anEnrolledRepositoryReadsBackWithItsEvent() {
    repository.apply(record("acme/a", RolloutStage.STAGE_1, T0), enrolled("acme/a", T0));

    assertThat(repository.find("acme/a"))
        .get()
        .extracting(StageRecord::stage)
        .isEqualTo(RolloutStage.STAGE_1);
    assertThat(repository.events("acme/a")).hasSize(1);
    assertThat(repository.events("acme/a").get(0).fromStage()).isNull();
  }

  @Test
  void anUnknownRepositoryIsEmpty() {
    assertThat(repository.find("nope")).isEmpty();
    assertThat(repository.events("nope")).isEmpty();
  }

  @Test
  void evidenceExceptionAndEscalationMarkerSurviveARoundTrip() {
    StageExemption exemption = new StageExemption("big migration \"in flight\"", "cto", T0, T1);
    StageRecord full =
        new StageRecord(
            "acme/a",
            RolloutStage.STAGE_2,
            T0,
            "team-a",
            Map.of("baseline", "https://x/1?a=b&c=d", "owner-signoff", "é ü"),
            exemption,
            T1);
    repository.apply(record("acme/a", RolloutStage.STAGE_1, T0), enrolled("acme/a", T0));
    repository.apply(
        full,
        advanced(
            "acme/a", RolloutStage.STAGE_1, RolloutStage.STAGE_2, T0, Map.of("baseline", "x")));

    assertThat(repository.find("acme/a")).contains(full);
  }

  @Test
  void enrollingTwiceIsRefused() {
    repository.apply(record("acme/a", RolloutStage.STAGE_1, T0), enrolled("acme/a", T0));

    assertThatThrownBy(
            () ->
                repository.apply(
                    record("acme/a", RolloutStage.STAGE_1, T1), enrolled("acme/a", T1)))
        .isInstanceOf(StageConflictException.class);
    assertThat(repository.events("acme/a")).hasSize(1);
    assertThat(repository.find("acme/a").orElseThrow().effectiveFrom()).isEqualTo(T0);
  }

  @Test
  void aChangeAssumingTheWrongCurrentStageIsRefusedAndWritesNothing() {
    repository.apply(record("acme/a", RolloutStage.STAGE_1, T0), enrolled("acme/a", T0));

    assertThatThrownBy(
            () ->
                repository.apply(
                    record("acme/a", RolloutStage.STAGE_4, T1),
                    advanced("acme/a", RolloutStage.STAGE_3, RolloutStage.STAGE_4, T1, Map.of())))
        .isInstanceOf(StageConflictException.class);
    assertThat(repository.find("acme/a").orElseThrow().stage()).isEqualTo(RolloutStage.STAGE_1);
    assertThat(repository.events("acme/a")).hasSize(1);
  }

  @Test
  void aChangeToARepositoryNotInTheRolloutIsRefused() {
    assertThatThrownBy(
            () ->
                repository.apply(
                    record("ghost", RolloutStage.STAGE_2, T1),
                    advanced("ghost", RolloutStage.STAGE_1, RolloutStage.STAGE_2, T1, Map.of())))
        .isInstanceOf(StageConflictException.class);
    assertThat(repository.find("ghost")).isEmpty();
  }

  @Test
  void eventsComeBackOldestFirstAndOnlyForTheRepositoryAsked() {
    repository.apply(record("acme/a", RolloutStage.STAGE_1, T0), enrolled("acme/a", T0));
    repository.apply(record("acme/b", RolloutStage.STAGE_1, T0), enrolled("acme/b", T0));
    repository.apply(
        record("acme/a", RolloutStage.STAGE_2, T1),
        advanced("acme/a", RolloutStage.STAGE_1, RolloutStage.STAGE_2, T1, Map.of("c", "e")));

    assertThat(repository.events("acme/a"))
        .extracting(StageEvent::kind)
        .containsExactly(StageEvent.Kind.ENROLLED, StageEvent.Kind.ADVANCED);
    assertThat(repository.events("acme/b")).hasSize(1);
  }

  @Test
  void updateChangesTheRecordWithoutAnEventAndRefusesAnUnknownRepository() {
    repository.apply(record("acme/a", RolloutStage.STAGE_1, T0), enrolled("acme/a", T0));

    repository.update(record("acme/a", RolloutStage.STAGE_1, T0).escalated(T1));

    assertThat(repository.find("acme/a").orElseThrow().escalatedAt()).isEqualTo(T1);
    assertThat(repository.events("acme/a")).hasSize(1);
    assertThatThrownBy(() -> repository.update(record("ghost", RolloutStage.STAGE_1, T0)))
        .isInstanceOf(StageConflictException.class);
  }

  @Test
  void currentStagesListsEveryRepositoryInTheRolloutAndNoOther() {
    repository.apply(record("acme/b", RolloutStage.STAGE_1, T0), enrolled("acme/b", T0));
    repository.apply(record("acme/a", RolloutStage.STAGE_1, T0), enrolled("acme/a", T0));
    repository.apply(
        record("acme/a", RolloutStage.STAGE_2, T1),
        advanced("acme/a", RolloutStage.STAGE_1, RolloutStage.STAGE_2, T1, Map.of()));

    assertThat(repository.currentStages())
        .containsOnly(
            Map.entry("acme/a", RolloutStage.STAGE_2), Map.entry("acme/b", RolloutStage.STAGE_1));
    assertThat(repository.all())
        .extracting(StageRecord::repository)
        .containsExactly("acme/a", "acme/b");
  }

  @Test
  void stagesAreOrderedAndStageFourIsLast() {
    assertThat(RolloutStage.STAGE_1.next()).isEqualTo(RolloutStage.STAGE_2);
    assertThat(RolloutStage.STAGE_4.next()).isNull();
    assertThat(RolloutStage.STAGE_2.createsWorkItems()).isFalse();
    assertThat(RolloutStage.STAGE_3.createsWorkItems()).isTrue();
    assertThat(RolloutStage.STAGE_4.createsWorkItems()).isTrue();
    assertThat(RolloutStage.of(3)).isEqualTo(RolloutStage.STAGE_3);
    assertThatThrownBy(() -> RolloutStage.of(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RolloutStage.of(5)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recordsAndEventsRefuseNonsense() {
    assertThatThrownBy(
            () -> new StageRecord("r", RolloutStage.STAGE_1, T0, " ", Map.of(), null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StageExemption("why", "who", T1, T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StageEvent(
                    "r",
                    StageEvent.Kind.ENROLLED,
                    null,
                    RolloutStage.STAGE_1,
                    T0,
                    "",
                    Map.of(),
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
