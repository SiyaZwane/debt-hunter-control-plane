package com.debthunter.controlplane.ai.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A model that records every prompt it is given. */
public final class FakeModelClient implements ModelClient {

  public final List<GuardedPrompt> received = new ArrayList<>();
  public volatile boolean down;
  public volatile Function<GuardedPrompt, String> answer = p -> "model says: explained";
  private final String version;

  public FakeModelClient() {
    this("fake-model-1");
  }

  public FakeModelClient(String version) {
    this.version = version;
  }

  @Override
  public String complete(GuardedPrompt prompt) {
    synchronized (received) {
      received.add(prompt);
    }
    if (down) {
      throw new ModelUnavailableException("model down", null);
    }
    return answer.apply(prompt);
  }

  @Override
  public String modelVersion() {
    return version;
  }

  public int calls() {
    synchronized (received) {
      return received.size();
    }
  }

  public String lastPromptText() {
    synchronized (received) {
      return received.get(received.size() - 1).text();
    }
  }
}
