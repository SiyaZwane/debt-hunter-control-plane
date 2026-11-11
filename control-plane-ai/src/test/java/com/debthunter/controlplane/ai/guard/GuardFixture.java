package com.debthunter.controlplane.ai.guard;

import com.debthunter.controlplane.ai.AiFixtures;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** A guard over controllable collaborators. */
public final class GuardFixture {

  public static final String SOURCE = "int secret = readSecret(); // SOURCE-MARKER";

  public final Map<String, SourceSharing> choices = new HashMap<>();
  public volatile boolean lookupDown;
  public volatile boolean snippetsDown;
  public volatile boolean logDown;
  public final List<String> snippetRequests = new CopyOnWriteArrayList<>();
  public final InMemoryDispatchLog log = new InMemoryDispatchLog();
  public final FakeModelClient model = new FakeModelClient();
  public final PromptGuard guard;

  public GuardFixture() {
    SourceSharingLookup lookup =
        repository -> {
          if (lookupDown) {
            throw new IllegalStateException("registry unavailable");
          }
          return choices.getOrDefault(repository, SourceSharing.NOT_OPTED_IN);
        };
    SourceSnippetProvider snippets =
        (repository, path, line) -> {
          snippetRequests.add(repository + ":" + path);
          if (snippetsDown) {
            throw new IllegalStateException("source host unavailable");
          }
          return Optional.of(SOURCE);
        };
    DispatchLog dispatchLog =
        record -> {
          if (logDown) {
            throw new IllegalStateException("log unavailable");
          }
          log.record(record);
        };
    this.guard = new PromptGuard(lookup, snippets, dispatchLog, AiFixtures.CLOCK);
  }

  public GuardFixture optedIn(String repository) {
    choices.put(repository, SourceSharing.OPTED_IN);
    return this;
  }
}
