package com.debthunter.controlplane.serve.evidence;

import static com.debthunter.controlplane.serve.ServeFixtures.CLOCK;
import static com.debthunter.controlplane.serve.ServeFixtures.CONTRIBUTOR;
import static com.debthunter.controlplane.serve.ServeFixtures.NOW;
import static com.debthunter.controlplane.serve.ServeFixtures.THING;
import static com.debthunter.controlplane.serve.ServeFixtures.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.serve.ServeFixtures;
import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.access.NotFoundException;
import com.debthunter.controlplane.serve.access.ServiceUnavailableException;
import com.debthunter.controlplane.serve.finding.FindingFixture;
import com.debthunter.controlplane.store.evidence.EvidenceAccess;
import com.debthunter.controlplane.store.evidence.EvidenceAccessLog;
import com.debthunter.controlplane.store.evidence.EvidenceSnapshot;
import com.debthunter.controlplane.store.evidence.InMemoryEvidenceAccessLog;
import com.debthunter.controlplane.store.evidence.InMemoryEvidenceRepository;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.policy.InMemoryPolicyCatalogueRepository;
import com.debthunter.controlplane.store.policy.PolicyBundleRecord;
import com.debthunter.controlplane.store.runs.EngineRecord;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-81 to CP-AC-84: a run's evidence, from either tier, with its policy, and always audited.
 */
class EvidenceRetrievalServiceTest {

  private static final String PURPOSE = "investigating the regression in widgets";
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPseudonymisedFindingStore findings =
      new InMemoryPseudonymisedFindingStore();
  private final InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
  private final InMemoryPolicyCatalogueRepository policies =
      new InMemoryPolicyCatalogueRepository();
  private final InMemoryEvidenceAccessLog log = new InMemoryEvidenceAccessLog();
  private EvidenceRetrievalService service;

  @BeforeEach
  void setUp() {
    service = service(log);
  }

  private EvidenceRetrievalService service(EvidenceAccessLog accessLog) {
    return new EvidenceRetrievalService(
        runs, findings, evidence, policies, ServeFixtures.registry(), accessLog, CLOCK);
  }

  private RunRecord store(
      String runId, String repository, String bundleVersion, Instant committed) {
    RunRecord run =
        new RunRecord(
            runId,
            repository,
            "root",
            "c-" + runId,
            "0.1.0",
            "k-" + runId,
            false,
            false,
            List.of(new EngineRecord("code-maat", "OK")),
            committed,
            RunStatus.ACTIVE,
            null,
            "FAILED",
            committed,
            "FULL",
            bundleVersion);
    runs.save(run);
    findings.saveRun(runId, repository, List.of(FindingFixture.finding()));
    return run;
  }

  private void hot(String runId, String repository) {
    evidence.saveHot(
        EvidenceSnapshot.capture(
            runId, repository, FindingFixture.finding(), NOW, Duration.ofDays(30)));
  }

  @Test
  void aRunsMetadataFindingsEvidenceAndPolicyComeBackTogether() {
    policies.save(new PolicyBundleRecord("2.3.0", DIGEST, "approver", NOW, NOW, null));
    store("r1", WIDGETS, "2.3.0", NOW);
    hot("r1", WIDGETS);

    RunEvidence e = service.retrieve(ServeFixtures.investigatorA(), "r1", PURPOSE);

    assertThat(e.run().repository()).isEqualTo(WIDGETS);
    assertThat(e.run().commit()).isEqualTo("c-r1");
    assertThat(e.run().engines()).containsExactly(new EngineRecord("code-maat", "OK"));
    assertThat(e.findings()).hasSize(1);
    assertThat(e.evidence().tier()).isEqualTo(RunEvidence.Tier.HOT);
    assertThat(e.evidence().snapshots()).hasSize(1);
    assertThat(e.evidence().snapshots().get(0).evidence()).containsEntry("changeFrequency", 42.0);
    assertThat(e.policy().known()).isTrue();
    assertThat(e.policy().bundleVersion()).isEqualTo("2.3.0");
    assertThat(e.policy().digest()).isEqualTo(DIGEST);
    assertThat(e.policy().outcome()).isEqualTo("FAILED");
    assertThat(e.generatedAt()).isEqualTo(NOW);
  }

  @Test
  void whenTheDetailHasAgedOutTheDaysSummariesAreReturnedAndMarkedWarm() {
    store("r1", WIDGETS, "2.3.0", NOW);
    hot("r1", WIDGETS);
    evidence.moveExpiredToWarm(NOW.plus(Duration.ofDays(60)));

    RunEvidence e = service.retrieve(ServeFixtures.investigatorA(), "r1", PURPOSE);

    assertThat(e.evidence().tier()).isEqualTo(RunEvidence.Tier.WARM);
    assertThat(e.evidence().snapshots()).isEmpty();
    assertThat(e.evidence().summaries()).hasSize(1);
    assertThat(e.evidence().summaries().get(0).ruleId()).isEqualTo("codemaat.hotspot");
  }

  @Test
  void summariesFromAnotherDayAreNotMistakenForTheRunsOwn() {
    store("r1", WIDGETS, "2.3.0", NOW);
    evidence.saveHot(
        EvidenceSnapshot.capture(
            "r0",
            WIDGETS,
            FindingFixture.finding(),
            NOW.minus(Duration.ofDays(5)),
            Duration.ofDays(1)));
    evidence.moveExpiredToWarm(NOW);

    RunEvidence e = service.retrieve(ServeFixtures.investigatorA(), "r1", PURPOSE);

    assertThat(e.evidence().tier()).isEqualTo(RunEvidence.Tier.NONE);
    assertThat(e.evidence().summaries()).isEmpty();
  }

  @Test
  void aRunWithNothingRetainedSaysSo() {
    store("r1", WIDGETS, "2.3.0", NOW);

    assertThat(service.retrieve(ServeFixtures.investigatorA(), "r1", PURPOSE).evidence().tier())
        .isEqualTo(RunEvidence.Tier.NONE);
  }

  @Test
  void theHistoricalPolicyIsTheOneTheRunWasAssessedAgainstNotTheCurrentOne() {
    policies.save(new PolicyBundleRecord("1.0.0", DIGEST, "approver", NOW, NOW, "2.0.0"));
    policies.save(
        new PolicyBundleRecord("2.0.0", "sha256:" + "b".repeat(64), "approver", NOW, NOW, null));
    store("old", WIDGETS, "1.0.0", NOW);

    RunEvidence.Policy policy =
        service.retrieve(ServeFixtures.investigatorA(), "old", PURPOSE).policy();

    assertThat(policy.bundleVersion()).isEqualTo("1.0.0");
    assertThat(policy.digest()).isEqualTo(DIGEST);
    assertThat(policy.supersededBy()).isEqualTo("2.0.0");
  }

  @Test
  void
      aRunReceivedBeforeTheBundleWasRecordedSaysItIsUnknownAndAVersionNotInTheCatalogueHasNoDigest() {
    store("legacy", WIDGETS, null, NOW);
    store("gone", WIDGETS, "9.9.9", NOW);

    RunEvidence.Policy legacy =
        service.retrieve(ServeFixtures.investigatorA(), "legacy", PURPOSE).policy();
    RunEvidence.Policy gone =
        service.retrieve(ServeFixtures.investigatorA(), "gone", PURPOSE).policy();

    assertThat(legacy.known()).isFalse();
    assertThat(legacy.bundleVersion()).isNull();
    assertThat(legacy.outcome()).isEqualTo("FAILED");
    assertThat(gone.known()).isTrue();
    assertThat(gone.bundleVersion()).isEqualTo("9.9.9");
    assertThat(gone.digest()).isNull();
  }

  @Test
  void everyRequestIsRecordedWithTheCallerThePurposeAndWhatWasDecided() {
    store("r1", WIDGETS, "2.3.0", NOW);
    store("other", THING, "2.3.0", NOW);

    service.retrieve(ServeFixtures.investigatorA(), "r1", PURPOSE);
    assertThatThrownBy(() -> service.retrieve(ServeFixtures.investigatorA(), "other", PURPOSE))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.retrieve(ServeFixtures.investigatorA(), "nope", PURPOSE))
        .isInstanceOf(NotFoundException.class);

    List<EvidenceAccess> entries = log.since(Instant.EPOCH);
    assertThat(entries)
        .extracting(
            EvidenceAccess::caller,
            EvidenceAccess::runId,
            EvidenceAccess::repository,
            EvidenceAccess::outcome)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "ivy", "r1", WIDGETS, EvidenceAccess.Outcome.GRANTED),
            org.assertj.core.groups.Tuple.tuple(
                "ivy", "other", THING, EvidenceAccess.Outcome.DENIED),
            org.assertj.core.groups.Tuple.tuple(
                "ivy", "nope", null, EvidenceAccess.Outcome.NOT_FOUND));
    assertThat(entries).allSatisfy(e -> assertThat(e.purpose()).isEqualTo(PURPOSE));
    assertThat(entries).allSatisfy(e -> assertThat(e.at()).isEqualTo(NOW));
  }

  @Test
  void aRunOfAnotherSquadLooksTheSameAsOneThatDoesNotExist() {
    store("other", THING, "2.3.0", NOW);

    Throwable denied =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> service.retrieve(ServeFixtures.investigatorA(), "other", PURPOSE));
    Throwable missing =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> service.retrieve(ServeFixtures.investigatorA(), "nope", PURPOSE));

    assertThat(denied).isInstanceOf(NotFoundException.class);
    assertThat(denied.getMessage()).isEqualTo(missing.getMessage());
  }

  @Test
  void ifTheRequestCannotBeRecordedNothingIsReleased() {
    store("r1", WIDGETS, "2.3.0", NOW);
    EvidenceAccessLog broken =
        new EvidenceAccessLog() {
          @Override
          public void record(EvidenceAccess access) {
            throw new IllegalStateException("log store down");
          }

          @Override
          public List<EvidenceAccess> since(Instant from) {
            return List.of();
          }

          @Override
          public List<EvidenceAccess> since(Instant from, int limit) {
            return List.of();
          }
        };

    assertThatThrownBy(() -> service(broken).retrieve(ServeFixtures.investigatorA(), "r1", PURPOSE))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("log store");
  }

  @Test
  void aStatedPurposeIsRequired() {
    store("r1", WIDGETS, "2.3.0", NOW);

    for (String bad : new String[] {null, "", "   ", "too short", "x".repeat(9)}) {
      assertThatThrownBy(() -> service.retrieve(ServeFixtures.investigatorA(), "r1", bad))
          .isInstanceOf(InvalidRequestException.class);
    }
    assertThat(log.since(Instant.EPOCH)).as("nothing is recorded for an invalid request").isEmpty();
  }

  @Test
  void aLongPurposeIsCutAndControlCharactersAreRemovedFromTheRecord() {
    store("r1", WIDGETS, "2.3.0", NOW);

    service.retrieve(
        ServeFixtures.investigatorA(), "r1", "line one\nFORGED ENTRY\u0007 " + "y".repeat(900));

    String recorded = log.since(Instant.EPOCH).get(0).purpose();
    assertThat(recorded).hasSizeLessThanOrEqualTo(EvidenceRetrievalService.MAX_PURPOSE);
    assertThat(recorded).doesNotContain("\n").doesNotContain("\u0007");
  }

  @Test
  void onlyCallersWithTheEvidenceRoleMayAskAndNothingIsRecordedForOthers() {
    store("r1", WIDGETS, "2.3.0", NOW);

    for (var caller :
        List.of(ServeFixtures.squadA(), ServeFixtures.leader(), ServeFixtures.nobody())) {
      assertThatThrownBy(() -> service.retrieve(caller, "r1", PURPOSE))
          .isInstanceOf(AccessDeniedException.class);
    }
    assertThat(log.since(Instant.EPOCH)).isEmpty();
  }

  @Test
  void aRunOfAnUnregisteredRepositoryIsNotReleasedToAnyone() {
    store("orphan", "ghost/repo", "2.3.0", NOW);

    assertThatThrownBy(() -> service.retrieve(ServeFixtures.investigatorA(), "orphan", PURPOSE))
        .isInstanceOf(NotFoundException.class);
    assertThat(log.since(Instant.EPOCH).get(0).outcome()).isEqualTo(EvidenceAccess.Outcome.DENIED);
  }

  @Test
  void nothingReturnedCarriesAContributor() {
    store("r1", WIDGETS, "2.3.0", NOW);
    hot("r1", WIDGETS);

    String json =
        ServeFixtures.json(service.retrieve(ServeFixtures.investigatorA(), "r1", PURPOSE));

    assertThat(json).doesNotContain(CONTRIBUTOR).doesNotContainIgnoringCase("contributor");
  }

  @Test
  void aStoreFailureWhileReadingIsReportedAsUnavailableNotAsAnInternalError() {
    store("r1", WIDGETS, "2.3.0", NOW);
    var failing =
        new EvidenceRetrievalService(
            runs,
            findings,
            new com.debthunter.controlplane.store.evidence.EvidenceRepository() {
              @Override
              public void saveHot(EvidenceSnapshot snapshot) {
                evidence.saveHot(snapshot);
              }

              @Override
              public List<EvidenceSnapshot> hotForRun(String runId) {
                throw new IllegalStateException("db down");
              }

              @Override
              public int moveExpiredToWarm(Instant asOf) {
                return evidence.moveExpiredToWarm(asOf);
              }

              @Override
              public List<com.debthunter.controlplane.store.evidence.EvidenceSummary> warmSummaries(
                  String repository) {
                return evidence.warmSummaries(repository);
              }
            },
            policies,
            ServeFixtures.registry(),
            log,
            CLOCK);

    assertThatThrownBy(() -> failing.retrieve(ServeFixtures.investigatorA(), "r1", PURPOSE))
        .isInstanceOf(ServiceUnavailableException.class);
  }
}
