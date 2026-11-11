package com.debthunter.controlplane.serve.finding;

import static com.debthunter.controlplane.serve.finding.FindingFixture.CONTRIBUTOR;
import static com.debthunter.controlplane.serve.finding.FindingFixture.finding;
import static com.debthunter.controlplane.serve.finding.FindingFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ai.explain.ExplainRequest;
import com.debthunter.controlplane.ai.explain.Explainer;
import com.debthunter.controlplane.ai.explain.Explanation;
import com.debthunter.controlplane.ai.explain.ExplanationService;
import com.debthunter.controlplane.ai.explain.InMemoryExplanationCache;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FindingDetailServiceTest {

  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPseudonymisedFindingStore findings =
      new InMemoryPseudonymisedFindingStore();

  private FindingDetailService service(ExplanationService explanations) {
    return new FindingDetailService(runs, findings, explanations);
  }

  private void store(String runId, String repository) {
    runs.save(run(runId, repository));
    findings.saveRun(runId, repository, List.of(finding()));
  }

  @Test
  void aFindingIsShownWithItsLabelledExplanation() {
    store("r1", "acme/a");

    FindingDetail d =
        service(FindingFixture.templatesOnly())
            .detail("acme/a", "r1", finding().id())
            .orElseThrow();

    assertThat(d.finding().ruleId()).isEqualTo("codemaat.hotspot");
    assertThat(d.explanation()).isPresent();
    assertThat(d.explanation().get().modelGenerated()).isFalse();
  }

  @Test
  void theViewNeverCarriesContributors() {
    store("r1", "acme/a");

    FindingDetail d =
        service(FindingFixture.templatesOnly())
            .detail("acme/a", "r1", finding().id())
            .orElseThrow();

    assertThat(d.toString()).doesNotContain(CONTRIBUTOR);
    assertThat(
            java.util.Arrays.stream(FindingView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
        .doesNotContain("contributors", "fingerprint");
  }

  @Test
  void aRunThatBelongsToAnotherRepositoryIsNotFound() {
    store("r1", "acme/a");

    assertThat(service(FindingFixture.templatesOnly()).detail("acme/other", "r1", finding().id()))
        .isEmpty();
  }

  @Test
  void anUnknownRunOrFindingIsNotFound() {
    store("r1", "acme/a");

    assertThat(service(FindingFixture.templatesOnly()).detail("acme/a", "nope", finding().id()))
        .isEmpty();
    assertThat(service(FindingFixture.templatesOnly()).detail("acme/a", "r1", "no-such-finding"))
        .isEmpty();
  }

  @Test
  void anExplainerThatThrowsStillLeavesTheFindingShown() {
    store("r1", "acme/a");
    Explainer exploding =
        new Explainer() {
          @Override
          public Optional<Explanation> explain(ExplainRequest request) {
            throw new IllegalStateException("bug");
          }

          @Override
          public boolean cacheable() {
            return false;
          }

          @Override
          public String generatorVersion() {
            return "x";
          }
        };
    ExplanationService broken =
        new ExplanationService(List.of(exploding), new InMemoryExplanationCache());

    FindingDetail d = service(broken).detail("acme/a", "r1", finding().id()).orElseThrow();

    assertThat(d.finding().id()).isEqualTo(finding().id());
    assertThat(d.explanation()).isEmpty();
  }
}
