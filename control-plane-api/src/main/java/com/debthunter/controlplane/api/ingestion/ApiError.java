package com.debthunter.controlplane.api.ingestion;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A machine-readable error body. {@code error} is a stable code a client can switch on; {@code
 * message} is for humans and may change.
 *
 * @param error the stable error code, such as {@code schema_validation_failed}
 * @param message a human-readable explanation
 * @param details what specifically failed validation, when that applies
 * @param supportedMajors the schema versions that would have been accepted, when that applies
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
    String error, String message, List<SchemaViolation> details, List<Integer> supportedMajors) {

  /** Takes defensive copies of the lists. */
  public ApiError {
    details = details == null ? List.of() : List.copyOf(details);
    supportedMajors = supportedMajors == null ? List.of() : List.copyOf(supportedMajors);
  }

  /**
   * An error with no further detail.
   *
   * @param error the stable error code
   * @param message a human-readable explanation
   * @return the error body
   */
  public static ApiError of(String error, String message) {
    return new ApiError(error, message, null, null);
  }
}
