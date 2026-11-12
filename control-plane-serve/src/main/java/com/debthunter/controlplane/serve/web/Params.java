package com.debthunter.controlplane.serve.web;

import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.query.QueryParams;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Reads query-string values into a {@link QueryParams}, refusing anything that does not parse. */
final class Params {

  private Params() {}

  static QueryParams of(
      String repository,
      String severity,
      String category,
      String state,
      String from,
      String to,
      String limit,
      String cursor) {
    return new QueryParams(
        blankToNull(repository),
        enumOf(Severity.class, "severity", severity),
        enumOf(Category.class, "category", category),
        enumOf(FindingState.class, "state", state),
        instant("from", from),
        instant("to", to),
        integer("limit", limit),
        blankToNull(cursor));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static <E extends Enum<E>> E enumOf(Class<E> type, String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Unknown " + name + " value");
    }
  }

  private static Instant instant(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new InvalidRequestException(name + " must be an ISO-8601 instant");
    }
  }

  private static Integer integer(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      throw new InvalidRequestException(name + " must be a whole number");
    }
  }
}
