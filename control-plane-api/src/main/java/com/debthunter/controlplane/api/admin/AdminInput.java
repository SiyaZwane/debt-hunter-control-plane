package com.debthunter.controlplane.api.admin;

/**
 * Validation of text supplied to the administration API. These values become identifiers, log lines
 * and rows, so they are checked strictly rather than cleaned up: silently altering an identifier
 * ("acme/widgets " becoming "acme/widgets") is how two people end up administering different things
 * under the same name.
 */
final class AdminInput {

  /** The longest identifier or label accepted. */
  static final int MAX_LENGTH = 200;

  private AdminInput() {}

  /**
   * Checks one field.
   *
   * @param field the field's name, for the error message
   * @param value its value
   * @return the value, unchanged
   * @throws InvalidAdminInputException if it is missing, blank, too long, padded with whitespace,
   *     or contains control characters
   */
  static String text(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new InvalidAdminInputException(field + " is required");
    }
    if (value.length() > MAX_LENGTH) {
      throw new InvalidAdminInputException(
          field + " must be at most " + MAX_LENGTH + " characters");
    }
    if (!value.equals(value.strip())) {
      throw new InvalidAdminInputException(field + " must not start or end with whitespace");
    }
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidAdminInputException(field + " must not contain control characters");
    }
    return value;
  }

  /**
   * Checks a repository identifier taken from a URL path. The path variable arrives with a leading
   * slash, which is not part of the identifier.
   *
   * @param captured the captured remainder of the path
   * @return the identifier
   * @throws InvalidAdminInputException if it is not a valid identifier
   */
  static String repositoryFromPath(String captured) {
    String id = captured != null && captured.startsWith("/") ? captured.substring(1) : captured;
    return text("repository", id);
  }
}
