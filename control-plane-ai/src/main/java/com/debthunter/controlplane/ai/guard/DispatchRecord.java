package com.debthunter.controlplane.ai.guard;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What was sent to a model, written before it is sent.
 *
 * @param repository the repository the finding belongs to
 * @param findingIds the findings described in the prompt
 * @param fieldsIncluded the names of every field in the prompt
 * @param modelVersion the model it was sent to
 * @param sourceIncluded whether source code was in the prompt
 * @param restrictedByUnknownChoice true if the repository's choice could not be read, so the
 *     restrictive default applied
 * @param at when
 */
public record DispatchRecord(
    String repository,
    List<String> findingIds,
    Set<String> fieldsIncluded,
    String modelVersion,
    boolean sourceIncluded,
    boolean restrictedByUnknownChoice,
    Instant at) {

  /** Copies the collections. */
  public DispatchRecord {
    findingIds = List.copyOf(findingIds);
    fieldsIncluded = Set.copyOf(fieldsIncluded);
  }
}
