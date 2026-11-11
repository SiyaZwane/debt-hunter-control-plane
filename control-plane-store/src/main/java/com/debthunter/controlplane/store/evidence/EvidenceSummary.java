package com.debthunter.controlplane.store.evidence;

import java.time.LocalDate;

/**
 * What remains of expired evidence: a count, with no detail. This is the warm tier.
 *
 * @param repository the repository
 * @param ruleId the rule
 * @param severity the severity
 * @param day the UTC day the evidence was captured
 * @param findingCount how many findings it summarises
 */
public record EvidenceSummary(
    String repository, String ruleId, String severity, LocalDate day, int findingCount) {}
