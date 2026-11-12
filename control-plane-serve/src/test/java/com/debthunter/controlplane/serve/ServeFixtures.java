package com.debthunter.controlplane.serve;

import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.query.FindingRow;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

/** Shared test data: three repositories owned by two squads, callers, and rows. */
public final class ServeFixtures {

  public static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");
  public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  public static final String WIDGETS = "acme/widgets";
  public static final String GADGETS = "acme/gadgets";
  public static final String THING = "other/thing";
  public static final String TEAM_A = "team-a";
  public static final String TEAM_B = "team-b";
  public static final String CONTRIBUTOR = "c".repeat(64);

  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private ServeFixtures() {}

  public static RepositoryRegistry registry() {
    RepositoryRegistry registry = new InMemoryRepositoryRegistry();
    registry.save(entry(WIDGETS, TEAM_A));
    registry.save(entry(GADGETS, TEAM_A));
    registry.save(entry(THING, TEAM_B));
    return registry;
  }

  public static OnboardedRepository entry(String repository, String owner) {
    return new OnboardedRepository(
        repository, owner, "tier-1", "v1", "STAGE_3", new WorkItemRoute("jira", "DH"));
  }

  public static Caller leader() {
    return new Caller("lena", Set.of(Roles.LEADER), Set.of());
  }

  public static Caller squadA() {
    return new Caller("alice", Set.of(Roles.SQUAD), Set.of(TEAM_A));
  }

  public static Caller squadB() {
    return new Caller("bob", Set.of(Roles.SQUAD), Set.of(TEAM_B));
  }

  public static Caller investigatorA() {
    return new Caller("ivy", Set.of(Roles.EVIDENCE), Set.of(TEAM_A));
  }

  public static Caller investigatorB() {
    return new Caller("ian", Set.of(Roles.EVIDENCE), Set.of(TEAM_B));
  }

  public static Caller nobody() {
    return new Caller("nobody", Set.of(), Set.of());
  }

  public static FindingRow row(String repository, String fp, Severity severity) {
    return row(repository, fp, severity, FindingState.OPEN, NOW);
  }

  public static FindingRow row(
      String repository, String fp, Severity severity, FindingState state, Instant observed) {
    return new FindingRow(
        repository,
        "root",
        fp,
        state,
        "f-" + fp,
        "codemaat.hotspot",
        Category.HOTSPOT,
        severity,
        "src/" + fp + ".java",
        7,
        "message " + fp,
        "run-" + fp,
        observed);
  }

  /** Any response object as the JSON a client would receive. */
  public static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
