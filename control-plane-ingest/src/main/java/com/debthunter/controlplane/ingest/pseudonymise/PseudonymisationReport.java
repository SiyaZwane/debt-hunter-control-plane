package com.debthunter.controlplane.ingest.pseudonymise;

/**
 * What pseudonymisation left out, so a reader can tell "no data" from "data withheld".
 *
 * @param evidenceEntriesDropped evidence entries removed because their key is not known to be free
 *     of identity, or because an identity list had an unexpected shape
 * @param blankIdentitiesDropped contributor identities that were blank and so identified nobody
 */
public record PseudonymisationReport(int evidenceEntriesDropped, int blankIdentitiesDropped) {}
