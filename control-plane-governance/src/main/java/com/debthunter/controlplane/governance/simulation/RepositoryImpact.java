package com.debthunter.controlplane.governance.simulation;

import com.debthunter.domain.PolicyViolation;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a candidate policy would do to one repository: either a simulated verdict, or an honest
 * statement that it could not be simulated and why. There is no third case in which a repository
 * quietly counts as passing for want of evidence.
 */
public sealed interface RepositoryImpact {

  /**
   * The repository identifier.
   *
   * @return the identifier
   */
  String repository();

  /**
   * The repository's criticality tier.
   *
   * @return the tier
   */
  String tier();

  /**
   * The repository's rollout stage.
   *
   * @return the stage
   */
  String stage();

  /**
   * A repository that was simulated.
   *
   * @param repository the repository identifier
   * @param tier the criticality tier
   * @param stage the rollout stage
   * @param currentPolicyVersion the policy bundle version the repository is held to today
   * @param evaluatedRunId the latest complete run, which the verdict is about
   * @param evaluatedRunAt when that run was committed
   * @param candidatePasses whether the latest run would pass the candidate
   * @param candidateViolations every candidate rule the latest run breaks
   * @param delta how the verdict differs from the current policy's
   * @param newlyBlocking findings the candidate counts that the current policy does not; empty when
   *     {@code delta} is {@link Delta#UNKNOWN}, because nothing can be called new without something
   *     to compare against
   * @param runsEvaluated how many complete runs in the window were replayed
   * @param runsFailingUnderCandidate how many of them the candidate would have blocked
   * @param runsFailingUnderCurrent how many the current policy would have blocked, or {@code null}
   *     when the current policy is not available
   * @param runsUndeterminable replayed runs whose result could not be determined for want of a
   *     recorded history depth; counted in neither failing figure
   * @param windowTruncated whether older complete runs in the window were left out to bound cost
   */
  record Simulated(
      String repository,
      String tier,
      String stage,
      String currentPolicyVersion,
      String evaluatedRunId,
      Instant evaluatedRunAt,
      boolean candidatePasses,
      List<PolicyViolation> candidateViolations,
      Delta delta,
      List<BlockingFinding> newlyBlocking,
      int runsEvaluated,
      int runsFailingUnderCandidate,
      Integer runsFailingUnderCurrent,
      int runsUndeterminable,
      boolean windowTruncated)
      implements RepositoryImpact {

    /** Validates required fields and defensively copies the lists. */
    public Simulated {
      Objects.requireNonNull(repository, "repository");
      Objects.requireNonNull(tier, "tier");
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(evaluatedRunId, "evaluatedRunId");
      Objects.requireNonNull(delta, "delta");
      candidateViolations =
          candidateViolations == null ? List.of() : List.copyOf(candidateViolations);
      newlyBlocking = newlyBlocking == null ? List.of() : List.copyOf(newlyBlocking);
    }
  }

  /**
   * A repository that could not be simulated.
   *
   * @param repository the repository identifier
   * @param tier the criticality tier
   * @param stage the rollout stage
   * @param reason why
   */
  record NotSimulated(String repository, String tier, String stage, NotSimulatedReason reason)
      implements RepositoryImpact {

    /** Validates required fields. */
    public NotSimulated {
      Objects.requireNonNull(repository, "repository");
      Objects.requireNonNull(tier, "tier");
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(reason, "reason");
    }
  }
}
