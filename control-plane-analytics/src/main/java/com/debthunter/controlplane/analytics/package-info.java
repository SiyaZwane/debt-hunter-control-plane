/**
 * Zone C — analytics: asynchronous metric projection, trend and drift analysis, remediation
 * effectiveness, and coverage and adoption tracking.
 *
 * <p>Read models are eventually consistent and carry a staleness indicator. Nothing here is ever
 * consulted by the ingest path, and stopping it must never block ingest.
 */
package com.debthunter.controlplane.analytics;
