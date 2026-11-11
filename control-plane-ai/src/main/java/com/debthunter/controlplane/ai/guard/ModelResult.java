package com.debthunter.controlplane.ai.guard;

/**
 * What a guarded dispatch returned.
 *
 * @param text the model's text
 * @param modelVersion the model that wrote it
 * @param sourceIncluded whether source code was in the prompt
 */
public record ModelResult(String text, String modelVersion, boolean sourceIncluded) {}
