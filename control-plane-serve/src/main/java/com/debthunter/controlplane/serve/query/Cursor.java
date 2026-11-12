package com.debthunter.controlplane.serve.query;

import com.debthunter.controlplane.serve.access.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Where a page stopped: the key of its last finding, as an opaque string. Paging by key rather than
 * by offset means findings that appear or resolve between requests cannot make a page repeat or
 * skip one.
 *
 * @param repository the last finding's repository
 * @param projectSlice its slice
 * @param fingerprint its fingerprint
 */
public record Cursor(String repository, String projectSlice, String fingerprint) {

  private static final char SEPARATOR = '\u0000';

  /** The cursor as a URL-safe string. */
  public String encode() {
    String raw = repository + SEPARATOR + projectSlice + SEPARATOR + fingerprint;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reads a cursor.
   *
   * @param text what {@link #encode} produced
   * @return the cursor
   * @throws InvalidRequestException if the text is not a cursor
   */
  public static Cursor decode(String text) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
      String[] parts = raw.split(String.valueOf(SEPARATOR), -1);
      if (parts.length != 3 || parts[0].isEmpty() || parts[2].isEmpty()) {
        throw new IllegalArgumentException("shape");
      }
      return new Cursor(parts[0], parts[1], parts[2]);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("The cursor is not valid");
    }
  }

  /** The cursor of the page that follows a row. */
  public static Cursor after(FindingRow row) {
    return new Cursor(row.repository(), row.projectSlice(), row.fingerprint());
  }

  /** Orders findings the way pages do. */
  public int compareTo(FindingRow row) {
    int byRepository = repository.compareTo(row.repository());
    if (byRepository != 0) {
      return byRepository;
    }
    int bySlice = projectSlice.compareTo(row.projectSlice());
    return bySlice != 0 ? bySlice : fingerprint.compareTo(row.fingerprint());
  }
}
