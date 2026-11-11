package com.debthunter.controlplane.analytics.readmodel;

import java.time.Instant;

/**
 * An analytics result together with how fresh the read models behind it are.
 *
 * <p>Read models are eventually consistent. A caller that shows this to a person should say so when
 * {@code stale} is true, rather than present old numbers as current.
 *
 * @param value the result
 * @param stale whether the read models have not been updated within the configured threshold, or
 *     have never been updated
 * @param lastUpdated when they were last brought up to date, or {@code null} if never
 * @param <T> the result type
 */
public record Trended<T>(T value, boolean stale, Instant lastUpdated) {}
