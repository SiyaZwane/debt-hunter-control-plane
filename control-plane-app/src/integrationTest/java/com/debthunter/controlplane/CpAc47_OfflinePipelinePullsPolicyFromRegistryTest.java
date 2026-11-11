package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.governance.policy.PolicyBundleValidator;
import com.debthunter.controlplane.governance.publication.BundleDigest;
import com.debthunter.controlplane.governance.publication.InMemoryRegistryClient;
import com.debthunter.controlplane.governance.publication.LocalSigner;
import com.debthunter.controlplane.governance.publication.OrasRegistryClient;
import com.debthunter.controlplane.governance.publication.PolicyBundleFetcher;
import com.debthunter.controlplane.governance.publication.PolicyPublisher;
import com.debthunter.controlplane.governance.publication.PublishOutcome;
import com.debthunter.controlplane.governance.publication.PublishRequest;
import com.debthunter.controlplane.governance.publication.Signature;
import com.debthunter.controlplane.governance.publication.StoredArtifact;
import com.debthunter.controlplane.governance.publication.UntrustedBundleException;
import com.debthunter.controlplane.store.policy.InMemoryPolicyCatalogueRepository;
import com.debthunter.controlplane.store.policy.PolicyCatalogueService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CP-AC-47 — a pipeline obtains its policy from the registry alone.
 *
 * <p>The control plane is not running and the consuming side is built from nothing but a registry
 * client, the public key and a repository name: no catalogue, no database, no control-plane
 * service. It pulls the bundle by digest, verifies it, and the real data-plane CLI then gates a
 * real repository against it. A bundle that does not verify never reaches the CLI.
 */
@Tag("integration")
class CpAc47_OfflinePipelinePullsPolicyFromRegistryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String REPOSITORY = "policy/bundles";
  private static final String BUNDLE =
      """
      version: "7.3"
      policy:
        main:
          rules:
            - id: no-critical
              severity: CRITICAL
              maxCount: 0
        pullRequest:
          rules:
            - id: no-new-critical
              severity: CRITICAL
              maxCount: 0
      """;

  @TempDir Path temp;

  private final LocalSigner signer = LocalSigner.generate("policy-key-1");
  private final OrasRegistryClient registry = new InMemoryRegistryClient();

  /**
   * What the policy board does, on the control-plane side. Its catalogue is not given to the
   * pipeline.
   */
  private String publish() {
    PolicyCatalogueService catalogue =
        new PolicyCatalogueService(new InMemoryPolicyCatalogueRepository());
    PublishOutcome outcome =
        new PolicyPublisher(
                new PolicyBundleValidator(),
                signer,
                signer.verifier(),
                registry,
                catalogue,
                REPOSITORY,
                Clock.systemUTC())
            .publish(
                new PublishRequest(BUNDLE, "policy-board", Instant.parse("2026-01-01T00:00:00Z")));
    assertThat(outcome).isInstanceOf(PublishOutcome.Published.class);
    return BundleDigest.of(BUNDLE.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void cpAc47_aPipelineWithNoControlPlanePullsVerifiesAndGatesAgainstTheBundle() throws Exception {
    String digest = publish();
    Path repository = DataPlaneRunner.createRepository(temp.resolve("repo"));
    Path policyFile = temp.resolve("pulled-policy.yml");
    Path output = temp.resolve("out");

    // The consuming side: registry + public key + repository name. Nothing else.
    PolicyBundleFetcher pipelineSide =
        new PolicyBundleFetcher(registry, signer.verifier(), REPOSITORY);
    assertThat(pipelineSide.fetchTo(digest, policyFile)).isPresent();

    DataPlaneRunner.Result scan = DataPlaneRunner.scanUnderPolicy(repository, output, policyFile);

    assertThat(scan.exitCode()).as(scan.output()).isIn(0, 1);
    JsonNode report = MAPPER.readTree(output.resolve("debt-hunter.json").toFile());
    assertThat(report.path("policy").path("status").asText())
        .isIn("PASSED", "FAILED", "WOULD_FAIL");
    assertThat(report.toString()).contains("7.3");
  }

  @Test
  void cpAc47_aBundleTamperedWithInTheRegistryNeverReachesThePipeline() throws Exception {
    String digest = publish();
    StoredArtifact genuine = registry.pull(REPOSITORY, digest).orElseThrow();
    OrasRegistryClient tampering =
        new OrasRegistryClient() {
          @Override
          public String push(String repository, String tag, byte[] content, Signature signature) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Optional<StoredArtifact> pull(String repository, String reference) {
            return Optional.of(
                new StoredArtifact(
                    genuine.digest(),
                    BUNDLE
                        .replace("maxCount: 0", "maxCount: 9999")
                        .getBytes(StandardCharsets.UTF_8),
                    genuine.signature()));
          }
        };
    Path policyFile = temp.resolve("pulled-policy.yml");

    assertThatThrownBy(
            () ->
                new PolicyBundleFetcher(tampering, signer.verifier(), REPOSITORY)
                    .fetchTo(digest, policyFile))
        .isInstanceOf(UntrustedBundleException.class);
    assertThat(policyFile).doesNotExist();
  }

  @Test
  void cpAc47_theOfflinePathDoesNotTouchTheCatalogue() {
    String digest = publish();
    OrasRegistryClient theRegistry = registry;

    // The fetcher's constructor takes no catalogue: it cannot depend on control-plane state.
    PolicyBundleFetcher fetcher =
        new PolicyBundleFetcher(theRegistry, signer.verifier(), REPOSITORY);

    assertThat(fetcher.fetch(digest)).isPresent();
    assertThat(PolicyBundleFetcher.class.getConstructors()[0].getParameterTypes())
        .containsExactly(
            OrasRegistryClient.class,
            com.debthunter.controlplane.governance.publication.SignatureVerifier.class,
            String.class);
  }
}
