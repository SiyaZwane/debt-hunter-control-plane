package com.debthunter.controlplane.ai.guard;

import java.util.Set;

/**
 * A prompt that has been through the guard. It can only be made by {@link PromptGuard}, and a
 * {@link ModelClient} only accepts this type, so nothing reaches a model without passing the guard.
 */
public final class GuardedPrompt {

  private final String text;
  private final Set<String> fieldsIncluded;
  private final boolean sourceIncluded;

  GuardedPrompt(String text, Set<String> fieldsIncluded, boolean sourceIncluded) {
    this.text = text;
    this.fieldsIncluded = Set.copyOf(fieldsIncluded);
    this.sourceIncluded = sourceIncluded;
  }

  /** The text to send. */
  public String text() {
    return text;
  }

  /** The names of the fields in it. */
  public Set<String> fieldsIncluded() {
    return fieldsIncluded;
  }

  /** Whether source code is in it. */
  public boolean sourceIncluded() {
    return sourceIncluded;
  }
}
