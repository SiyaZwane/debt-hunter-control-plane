package com.debthunter.controlplane.store.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of the audit trail: who did what to what, when, and whether it was allowed.
 *
 * <p>Text comes from callers and requests, so it is stripped of control characters and cut to a
 * fixed length when the entry is made; an entry can never carry a forged second line, and one
 * oversized value cannot bloat the trail.
 *
 * @param kind which trail the entry belongs to
 * @param at when
 * @param caller who (the token's subject)
 * @param action what they did, such as {@code SUPPRESS}
 * @param target what it was done to
 * @param detail anything else worth keeping, such as a stated reason; possibly empty
 * @param outcome whether it was allowed
 */
public record AuditEntry(
    Kind kind,
    Instant at,
    String caller,
    String action,
    String target,
    String detail,
    Outcome outcome) {

  /** The longest caller, action or target kept. */
  public static final int MAX_FIELD = 300;

  /** The longest detail kept. */
  public static final int MAX_DETAIL = 500;

  /** Which trail an entry belongs to. */
  public enum Kind {
    /** A change to who the control plane serves. */
    ADMIN,
    /** A request to turn a pseudonym back into a person. */
    REIDENTIFICATION,
    /** A suppression granted or withdrawn by a squad. */
    SUPPRESSION
  }

  /** Whether the action was allowed. */
  public enum Outcome {
    /** It was carried out, or is about to be. */
    ALLOWED,
    /** It was refused. */
    DENIED
  }

  /** Validates the components and makes the text safe to keep. */
  public AuditEntry {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(outcome, "outcome");
    caller = clean(Objects.requireNonNull(caller, "caller"), MAX_FIELD);
    action = clean(Objects.requireNonNull(action, "action"), MAX_FIELD);
    target = clean(Objects.requireNonNull(target, "target"), MAX_FIELD);
    detail = detail == null ? "" : clean(detail, MAX_DETAIL);
  }

  private static String clean(String text, int max) {
    String cleaned = text.replaceAll("\\p{Cntrl}", " ");
    return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "…";
  }
}
