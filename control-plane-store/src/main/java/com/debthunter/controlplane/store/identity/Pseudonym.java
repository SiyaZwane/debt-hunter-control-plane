package com.debthunter.controlplane.store.identity;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A contributor's identity after pseudonymisation: a keyed digest that is stable for one person but
 * cannot be turned back into who they are without the key and the permissioned re-identification
 * path.
 *
 * <p>This type is how the rule "contributor identity never reaches a store" is made enforceable. A
 * store accepts a {@code Pseudonym}, never a bare string, and the only code allowed to create one
 * from an identity is {@code IdentityPseudonymiser} (an architecture test fails the build
 * otherwise). Code that reads a pseudonym back from storage uses {@link #fromStored}.
 */
public final class Pseudonym {

  private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

  private final String value;

  private Pseudonym(String value) {
    this.value = value;
  }

  /**
   * Wraps a freshly computed digest. <strong>Only {@code IdentityPseudonymiser} may call this.
   * </strong>
   *
   * @param hexDigest the 64-character lowercase hex HMAC-SHA256 of the normalised identity
   * @return the pseudonym
   * @throws IllegalArgumentException if the text is not a 64-character lowercase hex digest
   */
  public static Pseudonym issue(String hexDigest) {
    return of(hexDigest);
  }

  /**
   * Restores a pseudonym that was previously stored or transmitted.
   *
   * @param stored the pseudonym's text form
   * @return the pseudonym
   * @throws IllegalArgumentException if the text is not a 64-character lowercase hex digest, which
   *     is also what stops a raw identity being passed off as a pseudonym
   */
  public static Pseudonym fromStored(String stored) {
    return of(stored);
  }

  private static Pseudonym of(String text) {
    Objects.requireNonNull(text, "pseudonym");
    if (!FORMAT.matcher(text).matches()) {
      throw new IllegalArgumentException("Not a pseudonym: expected 64 lowercase hex characters");
    }
    return new Pseudonym(text);
  }

  /**
   * The pseudonym's text form.
   *
   * @return 64 lowercase hex characters
   */
  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Pseudonym that && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}
