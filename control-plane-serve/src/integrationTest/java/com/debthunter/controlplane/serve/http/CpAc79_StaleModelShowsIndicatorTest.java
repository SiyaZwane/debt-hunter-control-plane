package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;

import java.time.Duration;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-79: when the read models are old the portfolio says so instead of looking current. */
@Tag("integration")
class CpAc79_StaleModelShowsIndicatorTest {

  @Test
  void freshReadModelsAreNotStale() {
    try (ServeHarness h = new ServeHarness()) {
      h.snapshot(WIDGETS, 0, 2, "PASSED", NOW.minus(Duration.ofMinutes(10)));

      h.get(h.leader(), "/api/v1/portfolio")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.stale")
          .isEqualTo(false)
          .jsonPath("$.readModelsAsOf")
          .isNotEmpty();
    }
  }

  @Test
  void readModelsOlderThanTheThresholdAreMarkedStaleAndStillShowTheirAge() {
    try (ServeHarness h = new ServeHarness()) {
      h.snapshot(WIDGETS, 0, 2, "PASSED", NOW.minus(Duration.ofHours(5)));

      h.get(h.leader(), "/api/v1/portfolio")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.stale")
          .isEqualTo(true)
          .jsonPath("$.readModelsAsOf")
          .isNotEmpty()
          .jsonPath("$.teams.length()")
          .isEqualTo(2);
    }
  }

  @Test
  void readModelsThatNeverRanAreStale() {
    try (ServeHarness h = new ServeHarness(NOW, Function.identity())) {
      h.get(h.leader(), "/api/v1/portfolio")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.stale")
          .isEqualTo(true)
          .jsonPath("$.readModelsAsOf")
          .doesNotExist();
    }
  }
}
