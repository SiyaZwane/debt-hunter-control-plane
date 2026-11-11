package com.debthunter.controlplane.serve.finding;

import com.debthunter.controlplane.ai.explain.Explanation;
import java.util.Optional;

/**
 * A finding and, if one could be produced, its explanation.
 *
 * @param repository the repository it belongs to
 * @param runId the run it was reported in
 * @param finding the finding
 * @param explanation the explanation with its provenance, or empty if there is none. The finding is
 *     shown either way.
 */
public record FindingDetail(
    String repository, String runId, FindingView finding, Optional<Explanation> explanation) {}
