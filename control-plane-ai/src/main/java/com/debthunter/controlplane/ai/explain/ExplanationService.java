package com.debthunter.controlplane.ai.explain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explains findings by trying explainers in order and using the first that produces something.
 *
 * <p>Output that is worth caching is cached by what was explained (fingerprint and rule) and what
 * produced it, not by who asked, so one repository's explanation serves another. The exception is
 * text produced from a prompt that contained source code: it may quote that source, so it is never
 * cached and is never handed to a second caller. Concurrent requests for the same uncached
 * explanation share one model call, unless the shared answer turns out to be source-informed.
 *
 * <p>The cache is an optimisation and the explainers may fail; neither can make this throw. If
 * nothing produces an explanation the answer is empty. It is called by readers only, never by
 * ingestion.
 */
public final class ExplanationService {

  private static final Logger LOG = LoggerFactory.getLogger(ExplanationService.class);

  private final List<Explainer> chain;
  private final ExplanationCache cache;
  private final ConcurrentMap<ExplanationCache.Key, CompletableFuture<Optional<Explanation>>>
      inFlight = new ConcurrentHashMap<>();

  /**
   * Creates the service.
   *
   * @param chain the explainers, most preferred first
   * @param cache the shared cache
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public ExplanationService(List<Explainer> chain, ExplanationCache cache) {
    this.chain = List.copyOf(chain);
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  /**
   * Explains a finding.
   *
   * @param request what to explain
   * @return an explanation, or empty if no explainer could produce one
   */
  public Optional<Explanation> explain(ExplainRequest request) {
    for (Explainer explainer : chain) {
      Optional<Explanation> result =
          explainer.cacheable() ? viaCache(explainer, request) : attempt(explainer, request);
      if (result.isPresent()) {
        return result;
      }
    }
    return Optional.empty();
  }

  private Optional<Explanation> viaCache(Explainer explainer, ExplainRequest request) {
    ExplanationCache.Key key =
        new ExplanationCache.Key(
            request.finding().fingerprint(),
            request.finding().ruleId(),
            explainer.generatorVersion());
    Optional<Explanation> hit = read(key);
    if (hit.isPresent()) {
      return hit;
    }

    CompletableFuture<Optional<Explanation>> mine = new CompletableFuture<>();
    CompletableFuture<Optional<Explanation>> other = inFlight.putIfAbsent(key, mine);
    if (other != null) {
      Optional<Explanation> shared = other.join();
      if (shared.isPresent() && !shared.get().sourceInformed()) {
        return shared;
      }
      // Nothing shared, or what was shared may quote another repository's source: do our own.
      return attempt(explainer, request);
    }
    try {
      Optional<Explanation> made = attempt(explainer, request);
      made.filter(e -> !e.sourceInformed()).ifPresent(e -> write(key, e));
      mine.complete(made);
      return made;
    } finally {
      mine.complete(Optional.empty());
      inFlight.remove(key, mine);
    }
  }

  private Optional<Explanation> attempt(Explainer explainer, ExplainRequest request) {
    try {
      return explainer.explain(request);
    } catch (RuntimeException e) {
      LOG.warn(
          "Explainer {} failed for {}",
          explainer.getClass().getSimpleName(),
          request.finding().id(),
          e);
      return Optional.empty();
    }
  }

  private Optional<Explanation> read(ExplanationCache.Key key) {
    try {
      return cache.get(key);
    } catch (RuntimeException e) {
      LOG.warn("Explanation cache read failed; treating as a miss", e);
      return Optional.empty();
    }
  }

  private void write(ExplanationCache.Key key, Explanation explanation) {
    try {
      cache.put(key, explanation);
    } catch (RuntimeException e) {
      LOG.warn("Explanation cache write failed; continuing", e);
    }
  }
}
