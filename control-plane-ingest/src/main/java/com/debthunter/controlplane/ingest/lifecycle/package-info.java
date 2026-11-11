/**
 * Run idempotency and the finding lifecycle: deciding whether a run is new, a duplicate, a re-run
 * or late; and turning what a run observed into state transitions, recorded in an append-only log.
 */
package com.debthunter.controlplane.ingest.lifecycle;
