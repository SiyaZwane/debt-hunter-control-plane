package com.debthunter.controlplane.ai.remediation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Names for the branch and file a proposal uses. Deterministic, so a retry finds its own work. */
public final class BranchNames {

  /** Every proposal branch starts with this. */
  public static final String PREFIX = "debt-hunter/fix-";

  /** Every proposal file lives under this directory. */
  public static final String DIRECTORY = ".debt-hunter/proposals/";

  private BranchNames() {}

  /**
   * A short, branch-safe identifier for a finding in a repository.
   *
   * @param repository the repository
   * @param fingerprint the finding's fingerprint
   * @return twelve hexadecimal characters
   */
  public static String idFor(String repository, String fingerprint) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((repository + "\0" + fingerprint).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** The branch a finding's proposal goes on. */
  public static String branchFor(String repository, String fingerprint) {
    return PREFIX + idFor(repository, fingerprint);
  }

  /** The file a finding's proposal is written to. */
  public static String fileFor(String repository, String fingerprint) {
    return DIRECTORY + idFor(repository, fingerprint) + ".md";
  }
}
