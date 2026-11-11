package com.debthunter.controlplane.ai.guard;

/**
 * A language model. It takes only a {@link GuardedPrompt}, which only {@link PromptGuard} can make,
 * and only the guard calls it.
 *
 * <p>Failure contract: {@code complete} returns the model's text or throws {@link
 * ModelUnavailableException}.
 */
public interface ModelClient {

  /**
   * Sends a guarded prompt.
   *
   * @param prompt the prompt
   * @return the model's text
   * @throws ModelUnavailableException if the model cannot answer
   */
  String complete(GuardedPrompt prompt);

  /** The model and version that answers, as recorded in the dispatch log. */
  String modelVersion();
}
