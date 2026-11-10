/**
 * REST edge of the control plane: ingestion, query, squad view, and portfolio endpoints.
 *
 * <p>The defining constraint: no endpoint here may be a synchronous interface that a pipeline waits
 * on. A pipeline that publishes a run bundle receives 202 Accepted and moves on; one that cannot
 * reach the control plane completes normally.
 */
package com.debthunter.controlplane.api;
