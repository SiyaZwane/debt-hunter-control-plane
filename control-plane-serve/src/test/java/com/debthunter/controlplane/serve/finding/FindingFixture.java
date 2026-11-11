package com.debthunter.controlplane.serve.finding;

import com.debthunter.controlplane.ai.explain.ExplanationService;
import com.debthunter.controlplane.ai.explain.ModelExplainer;
import com.debthunter.controlplane.ai.explain.RuleBasedExplainer;
import com.debthunter.controlplane.ai.guard.DispatchLog;
import com.debthunter.controlplane.ai.guard.GuardedPrompt;
import com.debthunter.controlplane.ai.guard.ModelClient;
import com.debthunter.controlplane.ai.guard.ModelUnavailableException;
import com.debthunter.controlplane.ai.guard.PromptGuard;
import com.debthunter.controlplane.ai.guard.SourceSharing;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.controlplane.store.runs.EngineRecord;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Findings, runs and explanation services for the serve tests. */
public final class FindingFixture {

  public static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");
  public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  public static final String CONTRIBUTOR = "d".repeat(64);

  private FindingFixture() {}

  public static PseudonymisedFinding finding() {
    return new PseudonymisedFinding(
        "codemaat.hotspot:src/Foo.java",
        "codemaat.hotspot",
        Category.HOTSPOT,
        Severity.HIGH,
        0.9,
        "src/Foo.java",
        0,
        "src/Foo.java is a change hotspot (42 revisions)",
        Map.of("changeFrequency", 42.0, "engine", "code-maat"),
        1.0,
        true,
        "fp-foo",
        List.of(Pseudonym.fromStored(CONTRIBUTOR)));
  }

  public static RunRecord run(String runId, String repository) {
    return new RunRecord(
        runId,
        repository,
        "default",
        "c-" + runId,
        "0.1.0",
        "k-" + runId,
        false,
        false,
        List.of(new EngineRecord("code-maat", "OK")),
        NOW,
        RunStatus.ACTIVE,
        null);
  }

  /** A model that is down. */
  public static ModelClient downModel() {
    return new ModelClient() {
      @Override
      public String complete(GuardedPrompt prompt) {
        throw new ModelUnavailableException("model down", null);
      }

      @Override
      public String modelVersion() {
        return "down-model-1";
      }
    };
  }

  /** A model that answers. */
  public static ModelClient workingModel() {
    return new ModelClient() {
      @Override
      public String complete(GuardedPrompt prompt) {
        return "The model's explanation.";
      }

      @Override
      public String modelVersion() {
        return "working-model-1";
      }
    };
  }

  private static PromptGuard guard() {
    DispatchLog log = record -> {};
    return new PromptGuard(
        repository -> SourceSharing.NOT_OPTED_IN,
        (r, p, l) -> java.util.Optional.empty(),
        log,
        CLOCK);
  }

  /** Model first, templates as the floor. */
  public static ExplanationService modelThenTemplates(ModelClient model) {
    return new ExplanationService(
        List.of(new ModelExplainer(guard(), model, CLOCK), new RuleBasedExplainer(CLOCK)),
        new com.debthunter.controlplane.ai.explain.InMemoryExplanationCache());
  }

  /** The model only: when it is down there is nothing to show. */
  public static ExplanationService modelOnly(ModelClient model) {
    return new ExplanationService(
        List.of(new ModelExplainer(guard(), model, CLOCK)),
        new com.debthunter.controlplane.ai.explain.InMemoryExplanationCache());
  }

  /** Templates only: what is wired today. */
  public static ExplanationService templatesOnly() {
    return new ExplanationService(
        List.of(new RuleBasedExplainer(CLOCK)),
        new com.debthunter.controlplane.ai.explain.InMemoryExplanationCache());
  }
}
