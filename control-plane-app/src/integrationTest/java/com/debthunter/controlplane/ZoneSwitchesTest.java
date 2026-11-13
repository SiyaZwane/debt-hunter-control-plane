package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.serve.query.QueryService;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The zones are switches: off, a zone's beans are absent and it needs none of its configuration;
 * on, it refuses to start with configuration that is half there rather than run without it.
 */
@Tag("integration")
class ZoneSwitchesTest {

  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();
  private static final String ZONES = "debthunter.controlplane.zones.";

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  private static ConfigurableApplicationContext start(AppProperties properties) {
    return new SpringApplicationBuilder(ControlPlaneApplication.class)
        .web(WebApplicationType.REACTIVE)
        .run(properties.args());
  }

  private static void assertRefusesToStart(AppProperties properties, String expectedMessage) {
    assertThatThrownBy(() -> start(properties).close())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .rootCause()
        .hasMessageContaining(expectedMessage);
  }

  @Test
  void serveAndEgressAreOnAndDigestsAreOffByDefault() {
    try (ConfigurableApplicationContext context = start(AppProperties.valid(ISSUER))) {
      assertThat(context.getBeansOfType(ServeZone.class)).hasSize(1);
      assertThat(context.getBeansOfType(WorkerZone.class)).containsOnlyKeys("egressZone");
      assertThat(context.getBean(ServeZone.class).isRunning()).isTrue();
      assertThat(context.getBean("egressZone", WorkerZone.class).isRunning()).isTrue();
    }
  }

  @Test
  void aSwitchedOffServeZoneHasNoReadApisAtAll() {
    try (ConfigurableApplicationContext context =
        start(AppProperties.valid(ISSUER).with(ZONES + "serve.enabled", "false"))) {
      assertThat(context.getBeansOfType(ServeZone.class)).isEmpty();
      assertThat(context.getBeansOfType(QueryService.class)).isEmpty();
    }
  }

  @Test
  void aSwitchedOffEgressZoneHasNoWorkerAndNeedsNoTrackers() {
    try (ConfigurableApplicationContext context =
        start(AppProperties.valid(ISSUER).with(ZONES + "egress.enabled", "false"))) {
      assertThat(context.getBeansOfType(WorkerZone.class)).isEmpty();
    }
  }

  @Test
  void aSwitchedOnDigestZoneNeedsSomewhereToPointSquads() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER).with(ZONES + "digests.enabled", "true"), "squad-link-base");
  }

  @Test
  void aSwitchedOnDigestZoneRuns() {
    try (ConfigurableApplicationContext context =
        start(
            AppProperties.valid(ISSUER)
                .with(ZONES + "digests.enabled", "true")
                .with(ZONES + "digests.squad-link-base", "https://cp.example/squads"))) {
      assertThat(context.getBeansOfType(WorkerZone.class)).containsKeys("egressZone", "digestZone");
      assertThat(context.getBean("digestZone", WorkerZone.class).isRunning()).isTrue();
    }
  }

  @Test
  void aTrackerWithoutACredentialRefusesToStart() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER)
            .with(ZONES + "egress.trackers.jira.base-url", "https://acme.atlassian.net"),
        "trackers.jira.authorization is not set");
  }

  @Test
  void aTrackerWithoutAnAddressRefusesToStart() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER)
            .with(ZONES + "egress.trackers.jira.authorization", "Basic c2VjcmV0"),
        "trackers.jira.base-url is not set");
  }

  @Test
  void aTrackerAddressThatIsNotHttpsRefusesToStartSoTheCredentialIsNeverSentInTheClear() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER)
            .with(ZONES + "egress.trackers.jira.base-url", "http://tracker.example")
            .with(ZONES + "egress.trackers.jira.authorization", "Basic c2VjcmV0"),
        "must be https");
  }

  @Test
  void aSuppressionCapOutsideItsBoundsRefusesToStart() {
    assertRefusesToStart(
        AppProperties.valid(ISSUER)
            .with("debthunter.controlplane.suppressions.max-expiry-days", "0"),
        "max-expiry-days");
    assertRefusesToStart(
        AppProperties.valid(ISSUER)
            .with("debthunter.controlplane.suppressions.max-expiry-days", "366"),
        "max-expiry-days");
  }
}
