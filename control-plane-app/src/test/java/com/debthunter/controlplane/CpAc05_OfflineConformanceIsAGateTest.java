package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-05, CP-AC-94: the offline-conformance test is a release gate, not just a test.
 *
 * <p>The behaviour itself is {@code ControlPlaneOfflineConformanceTest}, an integration test that
 * runs the real pipeline against a control plane that is down. This class guards the test: that it
 * exists, is not disabled, claims both ids, and that the build runs it in an execution that no skip
 * flag can switch off. Deleting, disabling or filtering it out fails the build here.
 */
class CpAc05_OfflineConformanceIsAGateTest {

  /** Any disabling annotation, qualified or not. Built in parts so this file does not match it. */
  private static final java.util.regex.Pattern DISABLED =
      java.util.regex.Pattern.compile("@(?:\\w+\\.)*Dis" + "abled");

  private static final Path CONFORMANCE =
      RepositoryFiles.root()
          .resolve(
              "control-plane-app/src/integrationTest/java/com/debthunter/controlplane/"
                  + "ControlPlaneOfflineConformanceTest.java");

  private static final Path APP_POM = RepositoryFiles.root().resolve("control-plane-app/pom.xml");

  @Test
  void cpAc05_theConformanceTestExistsIsTaggedAndIsNotDisabled() {
    assertThat(CONFORMANCE).exists();
    String source = RepositoryFiles.read(CONFORMANCE);
    assertThat(source).contains("@Tag(\"integration\")");
    assertThat(DISABLED.matcher(source).find()).as("disabled").isFalse();
    assertThat(source).doesNotContain("assumeTrue");
  }

  @Test
  void cpAc94_theConformanceTestClaimsBothAcceptanceCriteria() {
    String source = RepositoryFiles.read(CONFORMANCE);
    assertThat(source).contains("CP-AC-05").contains("CP-AC-94");
    assertThat(source).contains("void cpAc05_cpAc94_");
  }

  @Test
  void cpAc94_theBuildRunsItInAnExecutionNoSkipFlagCanSwitchOff() {
    String pom = RepositoryFiles.read(APP_POM);
    int start = pom.indexOf("<id>offline-conformance-gate</id>");
    assertThat(start).as("the gate execution is in the application pom").isPositive();
    String gate = pom.substring(start, pom.indexOf("</execution>", start));

    assertThat(gate)
        .contains("<skip>false</skip>")
        .contains("<skipTests>false</skipTests>")
        .contains("<skipITs>false</skipITs>")
        .contains("<groups>integration</groups>")
        .contains("<test>ControlPlaneOfflineConformanceTest</test>")
        .contains("<failIfNoSpecifiedTests>true</failIfNoSpecifiedTests>")
        .contains("<goal>verify</goal>");
    assertThat(gate).doesNotContain("<profile").doesNotContain("<activation");
  }

  @Test
  void cpAc94_theOrdinaryIntegrationRunLeavesItToTheGateSoItRunsOnce() {
    String pom = RepositoryFiles.read(APP_POM);
    assertThat(pom).contains("<exclude>**/ControlPlaneOfflineConformanceTest.java</exclude>");
    assertThat(Files.exists(CONFORMANCE)).isTrue();
  }
}
