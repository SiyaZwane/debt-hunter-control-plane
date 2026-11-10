/**
 * Zone A — ingestion pipeline: receives run bundles from the intake queue, normalises them across
 * schema versions, pseudonymises contributor identity, de-duplicates, and resolves the finding
 * lifecycle state machine.
 *
 * <p>Contributor identity must never reach a store without passing through the pseudonymiser. If
 * the pseudonym key is unavailable, ingest holds rather than processing un-pseudonymised data.
 */
package com.debthunter.controlplane.ingest;
