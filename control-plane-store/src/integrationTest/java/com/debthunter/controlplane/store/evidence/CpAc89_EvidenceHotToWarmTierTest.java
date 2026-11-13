package com.debthunter.controlplane.store.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-89: evidence detail is kept in full for its hot-tier lifetime, then the sweep moves it to
 * the warm tier, where only a per-day summary remains. Run against real PostgreSQL through the same
 * service the application's housekeeping calls.
 */
@Tag("integration")
class CpAc89_EvidenceHotToWarmTierTest {

  private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
  private static final Duration TTL = Duration.ofDays(30);

  private final DataSource db = freshDatabase();
  private final EvidenceStorageService service =
      new EvidenceStorageService(new JdbcEvidenceRepository(db), TTL);

  private static DataSource freshDatabase() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  private static PseudonymisedFinding finding(String id, String rule, Severity severity) {
    return new PseudonymisedFinding(
        id,
        rule,
        Category.HOTSPOT,
        severity,
        0.9,
        "src/A.java",
        0,
        "msg",
        Map.of("changeFrequency", 3.0),
        1.0,
        true,
        "fp-" + id,
        List.of());
  }

  @Test
  void detailStaysHotUntilTheTtlThenMovesAndOnlyASummaryRemains() {
    service.store("r1", "acme/widgets", List.of(finding("a", "rule-a", Severity.HIGH)), T0);

    assertThat(service.expire(T0.plus(TTL).minusSeconds(1))).isZero();
    assertThat(service.hotFor("r1")).hasSize(1);
    assertThat(service.warmSummaryFor("acme/widgets")).isEmpty();

    assertThat(service.expire(T0.plus(TTL))).isEqualTo(1);

    assertThat(service.hotFor("r1")).isEmpty();
    assertThat(service.warmSummaryFor("acme/widgets"))
        .containsExactly(
            new EvidenceSummary(
                "acme/widgets",
                "rule-a",
                "HIGH",
                T0.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                1));
  }

  @Test
  void theSummaryIsPerRuleSeverityAndDayAndCountsEveryFindingItReplaces() {
    service.store(
        "r1",
        "acme/widgets",
        List.of(
            finding("a", "rule-a", Severity.HIGH),
            finding("b", "rule-a", Severity.HIGH),
            finding("c", "rule-a", Severity.LOW)),
        T0);

    service.expire(T0.plus(TTL));

    assertThat(service.warmSummaryFor("acme/widgets"))
        .extracting(EvidenceSummary::severity, EvidenceSummary::findingCount)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("HIGH", 2),
            org.assertj.core.groups.Tuple.tuple("LOW", 1));
  }

  @Test
  void onlyEvidenceThatHasExpiredMovesAndNewerEvidenceStaysHot() {
    service.store("old", "acme/widgets", List.of(finding("a", "rule-a", Severity.HIGH)), T0);
    service.store(
        "new",
        "acme/widgets",
        List.of(finding("b", "rule-b", Severity.HIGH)),
        T0.plus(Duration.ofDays(20)));

    assertThat(service.expire(T0.plus(Duration.ofDays(31)))).isEqualTo(1);

    assertThat(service.hotFor("old")).isEmpty();
    assertThat(service.hotFor("new")).hasSize(1);
  }

  @Test
  void runningTheSweepAgainMovesNothingTwice() {
    service.store("r1", "acme/widgets", List.of(finding("a", "rule-a", Severity.HIGH)), T0);

    assertThat(service.expire(T0.plus(TTL))).isEqualTo(1);
    assertThat(service.expire(T0.plus(TTL).plusSeconds(60))).isZero();

    assertThat(service.warmSummaryFor("acme/widgets"))
        .singleElement()
        .satisfies(s -> assertThat(s.findingCount()).isEqualTo(1));
  }

  @Test
  void theWarmTierTableHoldsOnlyTheSummaryColumns() {
    List<String> columns =
        new Jdbc(db)
            .query(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_name = 'evidence_warm' AND table_schema = current_schema()"
                    + " ORDER BY column_name",
                row -> row.getString("column_name"));

    assertThat(columns)
        .containsExactly("day", "finding_count", "repository", "rule_id", "severity");
  }
}
