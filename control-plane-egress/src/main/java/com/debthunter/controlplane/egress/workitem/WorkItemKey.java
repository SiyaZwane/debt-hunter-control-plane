package com.debthunter.controlplane.egress.workitem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The key recorded on a tracker item so the item for a finding can always be found again. It is
 * derived from the finding's identity, so it is the same every time and says nothing about the
 * finding.
 */
public final class WorkItemKey {

  private static final int HEX_LENGTH = 32;

  private WorkItemKey() {}

  /**
   * The key for a finding.
   *
   * @param repository the repository
   * @param projectSlice the project slice
   * @param fingerprint the finding's fingerprint
   * @return a short, label-safe key such as {@code dh-3f2a...}
   */
  public static String of(String repository, String projectSlice, String fingerprint) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      String identity = repository + '\u0000' + projectSlice + '\u0000' + fingerprint;
      byte[] digest = sha.digest(identity.getBytes(StandardCharsets.UTF_8));
      return "dh-" + HexFormat.of().formatHex(digest).substring(0, HEX_LENGTH);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
