package com.debthunter.controlplane.store.policy;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The catalogue of policy bundle versions: what exists, which digest each has, who ratified it,
 * when it takes effect, and what replaced it.
 *
 * <p>A version only takes effect once it has been ratified. Making one effective marks the version
 * it replaces as superseded, so the history of "which policy applied when" stays answerable.
 */
public final class PolicyCatalogueService {

  private final PolicyCatalogueRepository catalogue;

  /**
   * Creates the service.
   *
   * @param catalogue where bundle records are kept
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public PolicyCatalogueService(PolicyCatalogueRepository catalogue) {
    this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
  }

  /**
   * Adds a new, unratified version.
   *
   * @param version the version
   * @param digest its content digest
   * @return the record
   * @throws IllegalStateException if the version already exists
   */
  public PolicyBundleRecord register(String version, String digest) {
    if (catalogue.find(version).isPresent()) {
      throw new IllegalStateException("Policy bundle version already exists: " + version);
    }
    PolicyBundleRecord record = new PolicyBundleRecord(version, digest, null, null, null, null);
    catalogue.save(record);
    return record;
  }

  /**
   * Records a ratification.
   *
   * @param version the version
   * @param approver who ratified it
   * @param at when
   * @throws IllegalStateException if the version is unknown or already ratified
   */
  public void ratify(String version, String approver, Instant at) {
    PolicyBundleRecord bundle = require(version);
    if (bundle.isRatified()) {
      throw new IllegalStateException("Already ratified: " + version);
    }
    catalogue.save(bundle.ratified(approver, at));
  }

  /**
   * Schedules a ratified version to take effect, superseding the version in force before it.
   *
   * @param version the version
   * @param from when it takes effect
   * @throws IllegalStateException if the version is unknown or not ratified
   */
  public void makeEffective(String version, Instant from) {
    PolicyBundleRecord bundle = require(version);
    if (!bundle.isRatified()) {
      throw new IllegalStateException("Cannot take effect before it is ratified: " + version);
    }
    List<PolicyBundleRecord> scheduled =
        catalogue.all().stream()
            .filter(b -> !b.version().equals(version) && b.effectiveFrom() != null)
            .toList();
    Optional<PolicyBundleRecord> previous =
        scheduled.stream()
            .filter(b -> b.effectiveFrom().isBefore(from))
            .max(Comparator.comparing(PolicyBundleRecord::effectiveFrom));
    Optional<PolicyBundleRecord> next =
        scheduled.stream()
            .filter(b -> b.effectiveFrom().isAfter(from))
            .min(Comparator.comparing(PolicyBundleRecord::effectiveFrom));

    previous.ifPresent(p -> catalogue.save(p.supersededBy(version)));
    PolicyBundleRecord updated = bundle.effectiveFrom(from);
    catalogue.save(next.map(n -> updated.supersededBy(n.version())).orElse(updated));
  }

  /**
   * The version in force at a point in time.
   *
   * @param at the instant (a commit date, not the wall clock, when replaying history)
   * @return the ratified version with the latest effective date not after {@code at}
   */
  public Optional<PolicyBundleRecord> effectiveAt(Instant at) {
    return catalogue.all().stream()
        .filter(PolicyBundleRecord::isRatified)
        .filter(b -> b.effectiveFrom() != null && !b.effectiveFrom().isAfter(at))
        .max(Comparator.comparing(PolicyBundleRecord::effectiveFrom));
  }

  /** Looks a version up. */
  public Optional<PolicyBundleRecord> find(String version) {
    return catalogue.find(version);
  }

  private PolicyBundleRecord require(String version) {
    return catalogue
        .find(version)
        .orElseThrow(() -> new IllegalStateException("Unknown policy bundle version: " + version));
  }
}
