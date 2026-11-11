package com.debthunter.controlplane.store.runs;

/**
 * An engine's outcome within a run.
 *
 * @param id the engine
 * @param status {@code OK}, {@code DEGRADED} or {@code FAILED}
 */
public record EngineRecord(String id, String status) {}
