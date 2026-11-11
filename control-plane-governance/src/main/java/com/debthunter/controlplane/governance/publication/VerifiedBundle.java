package com.debthunter.controlplane.governance.publication;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A bundle whose content matches its digest and whose signature verified.
 *
 * @param digest the content digest
 * @param content the bundle bytes
 */
public record VerifiedBundle(String digest, byte[] content) {

  /** Copies the content. */
  public VerifiedBundle {
    content = Arrays.copyOf(content, content.length);
  }

  /** A copy of the content. */
  @Override
  public byte[] content() {
    return Arrays.copyOf(content, content.length);
  }

  /** The bundle text. */
  public String text() {
    return new String(content, StandardCharsets.UTF_8);
  }
}
