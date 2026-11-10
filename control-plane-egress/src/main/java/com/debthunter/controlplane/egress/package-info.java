/**
 * Zone F — egress: work-item orchestration, the transactional outbox, and digest notifications.
 *
 * <p>Delivery is at-least-once with exponential backoff and a dead-letter store; poison messages
 * are never silently discarded, and nothing here may make ingest wait on a downstream system.
 */
package com.debthunter.controlplane.egress;
