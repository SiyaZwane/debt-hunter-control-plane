package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.TestJwtIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * CP-AC-05 and CP-AC-94 — the most important test in the control plane, and a release gate.
 *
 * <p>The architectural guarantee as code: the control plane being offline changes nothing about
 * what a pipeline delivers. The build runs this test in its own failsafe execution ({@code
 * offline-conformance-gate} in this module's pom) that no skip flag or test filter can switch off,
 * and fails if it did not run. It is never disabled, and a test in {@code src/test} checks that.
 *
 * <p>It lives in the application module rather than in the test kit, because it starts the
 * application and the test kit sits below it.
 *
 * <p>The control plane is started, so its port is real and was serving, and then stopped. A real
 * data-plane scan is then run with publication pointed at that dead endpoint. The pipeline must
 * complete exactly as it would have anyway: a normal exit code, every report written, a real policy
 * verdict — and only a warning in the summary to show the publication failed. Nothing about the
 * control plane being gone may change what the pipeline delivers.
 */
@Tag("integration")
class ControlPlaneOfflineConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void cpAc05_cpAc94_aPipelineCompletesNormallyWhenTheControlPlaneHasBeenStopped()
      throws Exception {
    Path repository = DataPlaneRunner.createRepository(temp.resolve("repo"));
    int stoppedPort = startThenStopControlPlane();
    Path output = temp.resolve("out");

    DataPlaneRunner.Result scan =
        DataPlaneRunner.scan(
            repository, output, "http://127.0.0.1:" + stoppedPort + "/api/v1/runs", "any-token");

    assertThat(scan.exitCode()).as(scan.output()).isIn(0, 1);
    for (String report :
        List.of("debt-hunter.json", "debt-hunter.sarif", "summary.md", "metrics.json")) {
      assertThat(output.resolve(report)).as(report).exists();
      assertThat(Files.size(output.resolve(report))).as(report + " is non-empty").isPositive();
    }
    assertThat(Files.readString(output.resolve("summary.md")))
        .contains("failed to publish scan result");
    JsonNode report = MAPPER.readTree(output.resolve("debt-hunter.json").toFile());
    assertThat(report.path("policy").path("status").asText())
        .isIn("PASSED", "FAILED", "WOULD_FAIL");
  }

  private int startThenStopControlPlane() {
    try (TestJwtIssuer issuer = new TestJwtIssuer()) {
      // Command-line arguments take precedence over application.yml; builder properties would not.
      ConfigurableApplicationContext controlPlane =
          new SpringApplicationBuilder(ControlPlaneApplication.class)
              .web(WebApplicationType.REACTIVE)
              .run(AppProperties.valid(issuer).args());
      int port = Integer.parseInt(controlPlane.getEnvironment().getProperty("local.server.port"));
      controlPlane.close();
      return port;
    }
  }
}
