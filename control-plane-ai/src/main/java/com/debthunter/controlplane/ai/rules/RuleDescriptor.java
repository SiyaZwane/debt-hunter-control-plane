package com.debthunter.controlplane.ai.rules;

/**
 * A plain description of what a rule looks for. It is the "rule descriptor" that may be shown to a
 * reader or sent to a model: it says nothing about any particular repository.
 *
 * @param title a short name for the kind of finding
 * @param meaning what the finding means
 * @param impact why it can matter
 * @param lookAt where a reader might start
 */
public record RuleDescriptor(String title, String meaning, String impact, String lookAt) {}
