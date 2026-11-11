package com.debthunter.controlplane.governance.publication;

/** A tag already points at different content. Version tags are immutable. */
public final class TagConflictException extends RegistryException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param tag the tag
   * @param existingDigest what it already points at
   */
  public TagConflictException(String tag, String existingDigest) {
    super("Tag " + tag + " already points at " + existingDigest);
  }
}
