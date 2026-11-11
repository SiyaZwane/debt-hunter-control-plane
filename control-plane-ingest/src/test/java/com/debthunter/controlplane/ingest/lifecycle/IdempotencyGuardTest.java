package com.debthunter.controlplane.ingest.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.lifecycle.IdempotencyGuard.Decision;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdempotencyGuardTest {

  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final IdempotencyGuard guard = new IdempotencyGuard(runs);

  private static String key(String commit) {
    return IdempotencyGuard.compositeKey("acme/widgets", commit, "default", "0.1.0");
  }

  private void applied(String runId, String commit) {
    runs.save(
        new RunRecord(
            runId,
            "acme/widgets",
            "default",
            commit,
            "0.1.0",
            key(commit),
            false,
            false,
            List.of(),
            LifecycleFixtures.day(1),
            RunStatus.ACTIVE,
            null));
  }

  @Test
  void cpAc21_aRunNeverSeenBeforeIsFirst() {
    assertThat(guard.classify("run-1", key("c1"))).isEqualTo(Decision.FIRST);
  }

  @Test
  void cpAc21_aRunIdAlreadyAppliedIsADuplicate() {
    applied("run-1", "c1");

    assertThat(guard.classify("run-1", key("c1"))).isEqualTo(Decision.DUPLICATE);
  }

  @Test
  void cpAc21_aDuplicateIsRecognisedEvenIfItsCommitDiffers() {
    applied("run-1", "c1");

    assertThat(guard.classify("run-1", key("c2"))).isEqualTo(Decision.DUPLICATE);
  }

  @Test
  void cpAc22_aNewRunIdForTheSameAnalysisIsARerun() {
    applied("run-1", "c1");

    assertThat(guard.classify("run-2", key("c1"))).isEqualTo(Decision.RERUN);
  }

  @Test
  void aDifferentCommitIsNotARerun() {
    applied("run-1", "c1");

    assertThat(guard.classify("run-2", key("c2"))).isEqualTo(Decision.FIRST);
  }

  @Test
  void cpAc22_savingARerunSupersedesTheEarlierRunButKeepsBoth() {
    applied("run-1", "c1");
    applied("run-2", "c1");

    assertThat(runs.find("run-1"))
        .get()
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo(RunStatus.SUPERSEDED);
              assertThat(r.supersededBy()).isEqualTo("run-2");
            });
    assertThat(runs.find("run-2")).get().extracting(RunRecord::status).isEqualTo(RunStatus.ACTIVE);
    assertThat(runs.findByCompositeKey(key("c1"))).hasSize(2);
  }

  @Test
  void theCompositeKeyChangesWithEachOfItsFourParts() {
    String base = IdempotencyGuard.compositeKey("r", "c", "s", "v");

    assertThat(IdempotencyGuard.compositeKey("r2", "c", "s", "v")).isNotEqualTo(base);
    assertThat(IdempotencyGuard.compositeKey("r", "c2", "s", "v")).isNotEqualTo(base);
    assertThat(IdempotencyGuard.compositeKey("r", "c", "s2", "v")).isNotEqualTo(base);
    assertThat(IdempotencyGuard.compositeKey("r", "c", "s", "v2")).isNotEqualTo(base);
  }

  @Test
  void theCompositeKeyIsNotFooledByWhereOnePartEndsAndTheNextBegins() {
    assertThat(IdempotencyGuard.compositeKey("ab", "c", "s", "v"))
        .isNotEqualTo(IdempotencyGuard.compositeKey("a", "bc", "s", "v"));
  }

  @Test
  void theCompositeKeyIsAStable64CharacterHexDigest() {
    String key = IdempotencyGuard.compositeKey("acme/widgets", "c1", "default", "0.1.0");

    assertThat(key).matches("[0-9a-f]{64}");
    assertThat(IdempotencyGuard.compositeKey("acme/widgets", "c1", "default", "0.1.0"))
        .isEqualTo(key);
  }
}
