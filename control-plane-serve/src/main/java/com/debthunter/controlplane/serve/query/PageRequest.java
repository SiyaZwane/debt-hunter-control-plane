package com.debthunter.controlplane.serve.query;

import com.debthunter.controlplane.serve.access.InvalidRequestException;

/**
 * Which page of results.
 *
 * @param limit how many to return, 1 to {@value #MAX_LIMIT}
 * @param cursor where the previous page stopped, or null for the first page
 */
public record PageRequest(int limit, String cursor) {

  /** The page size when none is asked for. */
  public static final int DEFAULT_LIMIT = 50;

  /** The largest page. */
  public static final int MAX_LIMIT = 200;

  /** Validates the limit. */
  public PageRequest {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new InvalidRequestException("limit must be between 1 and " + MAX_LIMIT);
    }
  }

  /**
   * Builds a request from optional values.
   *
   * @param limit the limit, or null for the default
   * @param cursor the cursor, or null
   * @return the request
   */
  public static PageRequest of(Integer limit, String cursor) {
    return new PageRequest(limit == null ? DEFAULT_LIMIT : limit, cursor);
  }
}
