package com.debthunter.controlplane.api.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Loads run-bundle schemas shipped on the classpath as {@code schemas/run-bundle-vN.schema.json}.
 */
public final class ClasspathSchemaSource implements SchemaSource {

  @Override
  public Optional<String> schemaFor(int major) {
    String resource = "/schemas/run-bundle-v" + major + ".schema.json";
    try (InputStream in = ClasspathSchemaSource.class.getResourceAsStream(resource)) {
      if (in == null) {
        return Optional.empty();
      }
      return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + resource, e);
    }
  }
}
