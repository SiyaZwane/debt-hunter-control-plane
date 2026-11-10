/**
 * Zone B — system of record: repository registry, runs, findings, transition log, suppressions,
 * baseline pointers, policy catalogue, and evidence storage.
 *
 * <p>Every other zone depends on this one and never the reverse. Persistence seams are interfaces
 * owned here with adapters injected, so the infrastructure behind them can be swapped without
 * touching callers.
 */
package com.debthunter.controlplane.store;
