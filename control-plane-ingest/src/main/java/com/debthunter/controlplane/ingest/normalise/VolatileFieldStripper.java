package com.debthunter.controlplane.ingest.normalise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Removes the fields that differ between two runs of the same code — the run id, the timestamp, and
 * each engine's duration — so what remains can be compared or hashed deterministically.
 *
 * <p>Works on the bundle layout, which the domain model's serialised form shares ({@code run.id},
 * {@code run.timestamp}, {@code run.engines[].durationMs}), so it applies equally to a raw bundle
 * and to a serialised {@link com.debthunter.domain.ScanResult}.
 */
public final class VolatileFieldStripper {

  /**
   * Returns a copy of the bundle without its volatile fields. The input is not modified.
   *
   * @param bundle a bundle or serialised scan result
   * @return a deep copy with {@code run.id}, {@code run.timestamp} and every {@code
   *     run.engines[].durationMs} removed
   */
  public JsonNode strip(JsonNode bundle) {
    JsonNode copy = bundle.deepCopy();
    if (copy.get("run") instanceof ObjectNode run) {
      run.remove("id");
      run.remove("timestamp");
      if (run.get("engines") instanceof ArrayNode engines) {
        for (JsonNode engine : engines) {
          if (engine instanceof ObjectNode engineObject) {
            engineObject.remove("durationMs");
          }
        }
      }
    }
    return copy;
  }
}
