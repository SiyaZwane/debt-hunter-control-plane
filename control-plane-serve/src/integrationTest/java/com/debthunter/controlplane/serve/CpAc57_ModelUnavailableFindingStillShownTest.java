package com.debthunter.controlplane.serve;

import static com.debthunter.controlplane.serve.finding.FindingFixture.finding;
import static com.debthunter.controlplane.serve.finding.FindingFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ai.explain.ExplanationService;
import com.debthunter.controlplane.serve.finding.FindingDetail;
import com.debthunter.controlplane.serve.finding.FindingDetailService;
import com.debthunter.controlplane.serve.finding.FindingFixture;
import com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.runs.JdbcRunRepository;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-57: when the model is unavailable the finding is still shown. Findings come from
 * PostgreSQL; the explanation is an extra that may be a labelled template, or absent, but never a
 * placeholder and never a reason to hide the finding.
 */
@Tag("integration")
class CpAc57_ModelUnavailableFindingStillShownTest {

  private final DataSource dataSource = PostgresTestDatabase.freshSchema();

  private FindingDetailService service(ExplanationService explanations) {
    StoreMigrations.migrate(dataSource);
    JdbcRunRepository runs = new JdbcRunRepository(dataSource);
    JdbcPseudonymisedFindingStore findings = new JdbcPseudonymisedFindingStore(dataSource);
    runs.save(run("r1", "acme/a"));
    findings.saveRun("r1", "acme/a", List.of(finding()));
    return new FindingDetailService(runs, findings, explanations);
  }

  @Test
  void cpAc57_withTheModelDownTheFindingIsShownWithALabelledTemplateExplanation() {
    FindingDetailService service =
        service(FindingFixture.modelThenTemplates(FindingFixture.downModel()));

    FindingDetail d = service.detail("acme/a", "r1", finding().id()).orElseThrow();

    assertThat(d.finding().message()).contains("change hotspot");
    assertThat(d.explanation()).isPresent();
    assertThat(d.explanation().get().modelGenerated()).isFalse();
  }

  @Test
  void cpAc57_withOnlyAModelAndItDownTheFindingIsShownWithNoExplanationAtAll() {
    FindingDetailService service = service(FindingFixture.modelOnly(FindingFixture.downModel()));

    FindingDetail d = service.detail("acme/a", "r1", finding().id()).orElseThrow();

    assertThat(d.finding().id()).isEqualTo(finding().id());
    assertThat(d.explanation()).as("no placeholder").isEmpty();
  }

  @Test
  void cpAc57_aWorkingModelsExplanationIsLabelledAsModelGenerated() {
    FindingDetailService service =
        service(FindingFixture.modelThenTemplates(FindingFixture.workingModel()));

    FindingDetail d = service.detail("acme/a", "r1", finding().id()).orElseThrow();

    assertThat(d.explanation().orElseThrow().modelGenerated()).isTrue();
    assertThat(d.explanation().orElseThrow().text()).isEqualTo("The model's explanation.");
  }

  @Test
  void cpAc57_aFindingFromAnotherRepositoryIsNotRevealedEvenWithAnExplanationAvailable() {
    FindingDetailService service = service(FindingFixture.templatesOnly());

    assertThat(service.detail("acme/other", "r1", finding().id())).isEmpty();
  }
}
