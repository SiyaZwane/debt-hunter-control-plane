package com.debthunter.controlplane.governance.rollout;

import com.debthunter.controlplane.store.rollout.RolloutStage;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rules of the rollout: what must be shown to leave each stage, and how long a repository may
 * stay at each. These are configuration, not code, because they are the organisation's decision.
 *
 * <p>A stage with no maximum hold may be held indefinitely (normally the last). A stage with no
 * exit criteria can be left on an approver's say-so alone, which is almost never what is wanted, so
 * the builder makes that an explicit choice rather than a default.
 */
public final class StagePolicy {

  private final Map<RolloutStage, Set<String>> exitCriteria;
  private final Map<RolloutStage, Duration> maxHold;

  private StagePolicy(
      Map<RolloutStage, Set<String>> exitCriteria, Map<RolloutStage, Duration> maxHold) {
    this.exitCriteria = exitCriteria;
    this.maxHold = maxHold;
  }

  /** Starts a policy. */
  public static Builder builder() {
    return new Builder();
  }

  /** The criteria that must each be evidenced to leave {@code stage}. */
  public Set<String> exitCriteria(RolloutStage stage) {
    return exitCriteria.getOrDefault(stage, Set.of());
  }

  /** The longest a repository may stay at {@code stage} without an exemption, or {@code null}. */
  public Duration maxHold(RolloutStage stage) {
    return maxHold.get(stage);
  }

  /** Builds a {@link StagePolicy}. */
  public static final class Builder {
    private final Map<RolloutStage, Set<String>> exitCriteria = new EnumMap<>(RolloutStage.class);
    private final Map<RolloutStage, Duration> maxHold = new EnumMap<>(RolloutStage.class);

    /**
     * Says what must be evidenced to leave a stage.
     *
     * @param stage the stage being left
     * @param criteria the criteria ids; may be empty only if the stage is meant to be freely left
     * @return this builder
     */
    public Builder exitCriteria(RolloutStage stage, String... criteria) {
      Set<String> set = new TreeSet<>();
      for (String criterion : criteria) {
        if (criterion == null || criterion.isBlank()) {
          throw new IllegalArgumentException("A criterion needs an id");
        }
        set.add(criterion);
      }
      exitCriteria.put(stage, Collections.unmodifiableSet(set));
      return this;
    }

    /**
     * Sets how long a repository may stay at a stage.
     *
     * @param stage the stage
     * @param hold the longest stay; must be positive
     * @return this builder
     */
    public Builder maxHold(RolloutStage stage, Duration hold) {
      if (hold == null || hold.isZero() || hold.isNegative()) {
        throw new IllegalArgumentException("A maximum hold must be positive");
      }
      maxHold.put(stage, hold);
      return this;
    }

    /**
     * Builds the policy.
     *
     * @return the policy
     * @throws IllegalStateException if a stage that can be left has no stated exit criteria
     */
    public StagePolicy build() {
      for (RolloutStage stage : RolloutStage.values()) {
        if (stage.next() != null && !exitCriteria.containsKey(stage)) {
          throw new IllegalStateException(
              "Say what must be evidenced to leave "
                  + stage
                  + " (pass no criteria to allow it to be left freely)");
        }
      }
      return new StagePolicy(new EnumMap<>(exitCriteria), new EnumMap<>(maxHold));
    }
  }
}
