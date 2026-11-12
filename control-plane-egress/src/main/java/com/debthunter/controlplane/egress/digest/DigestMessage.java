package com.debthunter.controlplane.egress.digest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One squad's digest for one period, as data. There is no field that could hold a person: a finding
 * is described by rule, path, severity and message, and nothing else about it.
 *
 * <p>Text that came from a repository (a path, a message, a reason) is untrusted; the renderers
 * must treat it so.
 *
 * @param squad the owning team
 * @param periodStart the start of the period, ISO-8601
 * @param periodEnd the end of the period, ISO-8601
 * @param newBySeverity findings first seen in the period, by severity name
 * @param resolved findings resolved in the period
 * @param regressed findings that came back in the period
 * @param expiring suppressions ending within the look-ahead
 * @param openBySeverity findings open now, by severity name
 * @param stages the rollout stage of each repository the squad owns
 * @param stagesOmitted repositories not listed because of the limit
 * @param notable the findings listed by name
 * @param notableOmitted new or regressed findings not listed because of the limit
 * @param distinctFiles files with a new or regressed finding
 * @param link where to look at the squad's findings, or {@code null}
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "every collection is copied to an immutable one in the constructor")
public record DigestMessage(
    String squad,
    String periodStart,
    String periodEnd,
    Map<String, Integer> newBySeverity,
    int resolved,
    int regressed,
    List<Expiring> expiring,
    Map<String, Integer> openBySeverity,
    List<StageLine> stages,
    int stagesOmitted,
    List<Notable> notable,
    int notableOmitted,
    int distinctFiles,
    String link) {

  /**
   * A suppression about to end.
   *
   * @param repository its repository
   * @param endsOn when it ends, ISO-8601
   * @param reason why it was granted
   */
  public record Expiring(String repository, String endsOn, String reason) {}

  /**
   * A repository's stage.
   *
   * @param repository the repository
   * @param stage the stage, such as {@code STAGE_3}, or {@code not in rollout}
   */
  public record StageLine(String repository, String stage) {}

  /**
   * A finding listed by name.
   *
   * @param severity how severe
   * @param rule the rule
   * @param path the file
   * @param message the finding's message
   * @param regression whether it came back, rather than being new
   */
  public record Notable(
      String severity, String rule, String path, String message, boolean regression) {}

  /** Validates and copies the components. */
  public DigestMessage {
    Objects.requireNonNull(squad, "squad");
    Objects.requireNonNull(periodStart, "periodStart");
    Objects.requireNonNull(periodEnd, "periodEnd");
    newBySeverity = Map.copyOf(newBySeverity);
    expiring = List.copyOf(expiring);
    openBySeverity = Map.copyOf(openBySeverity);
    stages = List.copyOf(stages);
    notable = List.copyOf(notable);
  }

  /**
   * Whether anything changed in the period. The standing picture (open now, stages) is not news.
   */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isEmpty() {
    return newBySeverity.values().stream().mapToInt(Integer::intValue).sum() == 0
        && resolved == 0
        && regressed == 0
        && expiring.isEmpty();
  }
}
