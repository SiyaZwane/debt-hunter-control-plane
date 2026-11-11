package com.debthunter.controlplane.ai.explain;

import static com.debthunter.controlplane.ai.explain.ExplainFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ai.AiFixtures;
import com.debthunter.controlplane.ai.guard.FakeModelClient;
import com.debthunter.controlplane.ai.guard.PromptGuard;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExplanationServiceTest {

  private final ExplainFixture f = new ExplainFixture();

  // ---- templates only (what is wired today) ------------------------------------------------

  @Test
  void withNoModelAFindingIsExplainedFromItsTemplateAndLabelledAsNotModelWritten() {
    Explanation e = f.templatesOnly.explain(request("acme/a")).orElseThrow();

    assertThat(e.modelGenerated()).isFalse();
    assertThat(e.provenance().source()).isEqualTo(Provenance.Source.RULE_TEMPLATE);
    assertThat(e.provenance().generatorVersion()).isEqualTo(RuleBasedExplainer.TEMPLATE_VERSION);
    assertThat(e.text()).contains("change hotspot").contains("src/Foo.java");
  }

  @Test
  void templateOutputIsNotCachedBecauseItIsCheapAndDeterministic() {
    f.templatesOnly.explain(request("acme/a"));

    assertThat(f.cache.size()).isZero();
  }

  // ---- CP-AC-55: cached across repositories -------------------------------------------------

  @Test
  void aSecondRepositoryAsksingForTheSameFindingIsServedFromTheCacheWithoutCallingTheModel() {
    Explanation first = f.withModel.explain(request("acme/a")).orElseThrow();
    Explanation second = f.withModel.explain(request("acme/b")).orElseThrow();

    assertThat(f.guard.model.calls()).isEqualTo(1);
    assertThat(second).isEqualTo(first);
    assertThat(f.cache.size()).isEqualTo(1);
  }

  @Test
  void aDifferentFindingIsExplainedAfresh() {
    f.withModel.explain(request("acme/a"));

    f.withModel.explain(
        new ExplainRequest(
            "acme/a",
            AiFixtures.finding(
                "id-2",
                "codemaat.hotspot",
                com.debthunter.domain.Category.HOTSPOT,
                com.debthunter.domain.Severity.HIGH,
                "src/Bar.java",
                "m",
                java.util.Map.of(),
                "fp-other")));

    assertThat(f.guard.model.calls()).isEqualTo(2);
  }

  @Test
  void aNewModelVersionDoesNotReuseTheOldModelsText() {
    f.withModel.explain(request("acme/a"));
    FakeModelClient newer = new FakeModelClient("fake-model-2");
    ExplanationService upgraded =
        new ExplanationService(
            List.of(new ModelExplainer(f.guard.guard, newer, AiFixtures.CLOCK), f.templates),
            f.cache);

    Explanation e = upgraded.explain(request("acme/b")).orElseThrow();

    assertThat(newer.calls()).isEqualTo(1);
    assertThat(e.provenance().generator()).isEqualTo("fake-model-2");
  }

  // ---- CP-AC-56: labelled -------------------------------------------------------------------

  @Test
  void modelTextIsLabelledAsModelGeneratedWithTheModelAndPromptVersion() {
    Explanation e = f.withModel.explain(request("acme/a")).orElseThrow();

    assertThat(e.modelGenerated()).isTrue();
    assertThat(e.provenance().source()).isEqualTo(Provenance.Source.MODEL);
    assertThat(e.provenance().generator()).isEqualTo("fake-model-1");
    assertThat(e.provenance().generatorVersion()).isEqualTo(PromptGuard.PROMPT_VERSION);
    assertThat(e.provenance().generatedAt()).isEqualTo(AiFixtures.NOW);
  }

  @Test
  void aCachedExplanationIsStillLabelledAsModelGenerated() {
    f.withModel.explain(request("acme/a"));

    Explanation cached = f.withModel.explain(request("acme/b")).orElseThrow();

    assertThat(cached.modelGenerated()).isTrue();
    assertThat(cached.provenance().generator()).isEqualTo("fake-model-1");
  }

  // ---- model unavailable (CP-AC-57 at the service) ------------------------------------------

  @Test
  void aModelThatIsDownFallsThroughToTheLabelledTemplate() {
    f.guard.model.down = true;

    Explanation e = f.withModel.explain(request("acme/a")).orElseThrow();

    assertThat(e.modelGenerated()).isFalse();
    assertThat(e.text()).contains("change hotspot");
  }

  @Test
  void aModelThatIsDownStillServesWhatIsCached() {
    f.withModel.explain(request("acme/a"));
    f.guard.model.down = true;

    Explanation e = f.withModel.explain(request("acme/b")).orElseThrow();

    assertThat(e.modelGenerated()).isTrue();
    assertThat(f.guard.model.calls()).isEqualTo(1);
  }

  @Test
  void ifNothingCanExplainTheAnswerIsEmptyNotAPlaceholder() {
    f.guard.model.down = true;
    ExplanationService modelOnly = new ExplanationService(List.of(f.model), f.cache);

    assertThat(modelOnly.explain(request("acme/a"))).isEmpty();
  }

  @Test
  void aBlankModelAnswerIsTreatedAsNoAnswer() {
    f.guard.model.answer = p -> "   ";

    Explanation e = f.withModel.explain(request("acme/a")).orElseThrow();

    assertThat(e.modelGenerated()).isFalse();
    assertThat(f.cache.size()).isZero();
  }

  @Test
  void aVeryLongModelAnswerIsCapped() {
    f.guard.model.answer = p -> "x".repeat(50_000);

    assertThat(f.withModel.explain(request("acme/a")).orElseThrow().text().length())
        .isLessThan(3_100);
  }

  @Test
  void aModelWhoseDispatchCannotBeRecordedFallsThroughToTheTemplate() {
    f.guard.logDown = true;

    Explanation e = f.withModel.explain(request("acme/a")).orElseThrow();

    assertThat(e.modelGenerated()).isFalse();
    assertThat(f.guard.model.calls()).isZero();
  }

  // ---- the cache and explainers cannot make the service throw ------------------------------

  private static ExplanationCache brokenCache() {
    return new ExplanationCache() {
      @Override
      public Optional<Explanation> get(Key key) {
        throw new IllegalStateException("redis down");
      }

      @Override
      public void put(Key key, Explanation explanation) {
        throw new IllegalStateException("redis down");
      }
    };
  }

  @Test
  void aBrokenCacheCostsAModelCallButNeverTheAnswer() {
    ExplanationService service =
        new ExplanationService(List.of(f.model, f.templates), brokenCache());

    Explanation e = service.explain(request("acme/a")).orElseThrow();

    assertThat(e.modelGenerated()).isTrue();
  }

  @Test
  void anExplainerThatThrowsIsSkipped() {
    Explainer exploding =
        new Explainer() {
          @Override
          public Optional<Explanation> explain(ExplainRequest r) {
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
    ExplanationService service = new ExplanationService(List.of(exploding, f.templates), f.cache);

    assertThat(service.explain(request("acme/a"))).isPresent();
  }

  // ---- source-informed text never crosses repositories --------------------------------------

  @Test
  void textWrittenWithAnOptedInRepositorysSourceIsNeverCachedOrSharedWithAnother() {
    f.guard.optedIn("acme/a");
    f.guard.model.answer = p -> p.sourceIncluded() ? "quotes SOURCE-MARKER" : "no source here";

    Explanation forA = f.withModel.explain(request("acme/a")).orElseThrow();
    Explanation forB = f.withModel.explain(request("acme/b")).orElseThrow();

    assertThat(forA.sourceInformed()).isTrue();
    assertThat(forA.text()).contains("SOURCE-MARKER");
    assertThat(forB.text()).doesNotContain("SOURCE-MARKER");
    assertThat(forB.sourceInformed()).isFalse();
    assertThat(f.guard.model.calls()).isEqualTo(2);
  }

  @Test
  void aSourceFreeExplanationIsCachedAndServedToAnOptedInRepositoryToo() {
    f.guard.optedIn("acme/a");
    f.withModel.explain(request("acme/b"));

    f.withModel.explain(request("acme/a"));

    assertThat(f.guard.model.calls()).as("the source-free text is reused").isEqualTo(1);
  }

  // ---- concurrent requests share one call -------------------------------------------------

  private static void awaitWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
  }

  @Test
  void twoRequestsForTheSameUncachedFindingShareOneModelCall() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    f.guard.model.answer =
        p -> {
          entered.countDown();
          await(release);
          return "shared";
        };
    CompletableFuture<Optional<Explanation>> leader =
        CompletableFuture.supplyAsync(() -> f.withModel.explain(request("acme/a")));
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    Thread[] follower = new Thread[1];
    CompletableFuture<Optional<Explanation>> second =
        CompletableFuture.supplyAsync(
            () -> {
              follower[0] = Thread.currentThread();
              return f.withModel.explain(request("acme/b"));
            });
    while (follower[0] == null) {
      Thread.sleep(2);
    }
    awaitWaiting(follower[0]);

    release.countDown();

    assertThat(leader.get(5, TimeUnit.SECONDS).orElseThrow().text()).isEqualTo("shared");
    assertThat(second.get(5, TimeUnit.SECONDS).orElseThrow().text()).isEqualTo("shared");
    assertThat(f.guard.model.calls()).isEqualTo(1);
  }

  @Test
  void aWaiterIsNeverGivenAnotherRepositorysSourceInformedAnswer() throws Exception {
    f.guard.optedIn("acme/a");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    f.guard.model.answer =
        p -> {
          if (p.sourceIncluded()) {
            entered.countDown();
            await(release);
            return "quotes SOURCE-MARKER";
          }
          return "no source here";
        };
    CompletableFuture<Optional<Explanation>> leader =
        CompletableFuture.supplyAsync(() -> f.withModel.explain(request("acme/a")));
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    Thread[] follower = new Thread[1];
    CompletableFuture<Optional<Explanation>> second =
        CompletableFuture.supplyAsync(
            () -> {
              follower[0] = Thread.currentThread();
              return f.withModel.explain(request("acme/b"));
            });
    while (follower[0] == null) {
      Thread.sleep(2);
    }
    awaitWaiting(follower[0]);

    release.countDown();

    assertThat(leader.get(5, TimeUnit.SECONDS).orElseThrow().text()).contains("SOURCE-MARKER");
    assertThat(second.get(5, TimeUnit.SECONDS).orElseThrow().text()).isEqualTo("no source here");
  }

  @Test
  void aWaiterWhoseLeaderFailedMakesItsOwnAttempt() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
    f.guard.model.answer =
        p -> {
          if (n.getAndIncrement() == 0) {
            entered.countDown();
            await(release);
            throw new IllegalStateException("first call fails");
          }
          return "second call works";
        };
    CompletableFuture<Optional<Explanation>> leader =
        CompletableFuture.supplyAsync(() -> f.withModel.explain(request("acme/a")));
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    Thread[] follower = new Thread[1];
    CompletableFuture<Optional<Explanation>> second =
        CompletableFuture.supplyAsync(
            () -> {
              follower[0] = Thread.currentThread();
              return f.withModel.explain(request("acme/b"));
            });
    while (follower[0] == null) {
      Thread.sleep(2);
    }
    awaitWaiting(follower[0]);

    release.countDown();

    assertThat(leader.get(5, TimeUnit.SECONDS).orElseThrow().modelGenerated()).isFalse();
    assertThat(second.get(5, TimeUnit.SECONDS).orElseThrow().text()).isEqualTo("second call works");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void theFindingAlonePicksTheCacheEntryNotTheRepository() {
    PseudonymisedFinding finding = AiFixtures.hotspot();

    f.withModel.explain(new ExplainRequest("acme/a", finding));
    f.withModel.explain(new ExplainRequest("zzz/other", finding));

    assertThat(f.guard.model.calls()).isEqualTo(1);
  }

  @Test
  void aRequestNeedsARepository() {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> new ExplainRequest(" ", AiFixtures.hotspot()));
  }
}
