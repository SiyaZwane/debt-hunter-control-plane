package com.debthunter.controlplane.store.suppressions;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-26: a suppression's owner is warned seven days before its maximum life ends, once, judged
 * by commit date — with the state kept in the database, so the warning is not repeated after a
 * restart.
 */
@Tag("integration")
class CpAc26_SuppressionPreExpiryEventTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static Instant day(int n) {
    return T0.plus(Duration.ofDays(n));
  }

  private static DataSource migrated() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  @Test
  void cpAc26_theWarningFallsDueAtMaxExpiryDaysMinusSevenAndIsNotRepeatedAfterARestart() {
    DataSource dataSource = migrated();
    List<SuppressionPreExpiryEvent> events = new ArrayList<>();
    new SuppressionRegisterService(new JdbcSuppressionRepository(dataSource), events::add)
        .suppress("acme/widgets", "fp-a", "accepted risk", T0, day(90), 90);

    SuppressionRegisterService restarted =
        new SuppressionRegisterService(new JdbcSuppressionRepository(dataSource), events::add);
    assertThat(restarted.emitPreExpiryEvents(day(82))).isZero();
    assertThat(restarted.emitPreExpiryEvents(day(83))).isEqualTo(1);

    SuppressionRegisterService restartedAgain =
        new SuppressionRegisterService(new JdbcSuppressionRepository(dataSource), events::add);
    assertThat(restartedAgain.emitPreExpiryEvents(day(85))).isZero();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).raisedAsOf()).isEqualTo(day(83));
  }

  @Test
  void cpAc26_theLifecycleAsksTheDurableRegisterWhetherAFindingIsSuppressedAtACommitDate() {
    DataSource dataSource = migrated();
    SuppressionRegisterService register =
        new SuppressionRegisterService(new JdbcSuppressionRepository(dataSource), e -> {});
    register.suppress("acme/widgets", "fp-a", "accepted risk", T0, day(30), 90);

    SuppressionLookup lookup =
        new SuppressionRegisterService(new JdbcSuppressionRepository(dataSource), e -> {});

    assertThat(lookup.find("acme/widgets", "fp-a").get().appliesAt(day(10))).isTrue();
    assertThat(lookup.find("acme/widgets", "fp-a").get().appliesAt(day(31))).isFalse();
  }

  @Test
  void cpAc26_aPublishFailureLeavesTheWarningOwedAndItIsRaisedOnTheNextSweep() {
    DataSource dataSource = migrated();
    boolean[] up = {false};
    List<SuppressionPreExpiryEvent> events = new ArrayList<>();
    SuppressionRegisterService service =
        new SuppressionRegisterService(
            new JdbcSuppressionRepository(dataSource),
            event -> {
              if (!up[0]) {
                throw new IllegalStateException("notifier down");
              }
              events.add(event);
            });
    service.suppress("acme/widgets", "fp-a", "accepted risk", T0, day(90), 90);

    try {
      service.emitPreExpiryEvents(day(84));
    } catch (IllegalStateException expected) {
      // the notifier was down
    }
    up[0] = true;

    assertThat(service.emitPreExpiryEvents(day(85))).isEqualTo(1);
    assertThat(events).hasSize(1);
  }
}
