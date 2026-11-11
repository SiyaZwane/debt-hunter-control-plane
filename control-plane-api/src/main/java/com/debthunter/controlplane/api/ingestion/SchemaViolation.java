package com.debthunter.controlplane.api.ingestion;

/**
 * One way a bundle failed schema validation.
 *
 * @param path where in the bundle, as a JSONPath such as {@code $.run.repository}
 * @param message what is wrong there
 */
public record SchemaViolation(String path, String message) {}
