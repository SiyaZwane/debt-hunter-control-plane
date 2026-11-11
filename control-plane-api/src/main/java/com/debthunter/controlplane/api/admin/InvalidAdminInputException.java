package com.debthunter.controlplane.api.admin;

/** A request to the administration API was malformed. Maps to HTTP 400. */
final class InvalidAdminInputException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  InvalidAdminInputException(String message) {
    super(message);
  }
}
