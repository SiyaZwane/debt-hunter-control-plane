package com.debthunter.controlplane.egress.workitem;

/**
 * The tracker answered and refused (no such project, a field it will not accept, a transition that
 * does not exist). Trying again unchanged may not help, so the attempt counts and the message is
 * eventually set aside with an alert.
 */
public final class TrackerRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what the tracker refused; never contains a credential
   */
  public TrackerRejectedException(String message) {
    super(message);
  }
}
