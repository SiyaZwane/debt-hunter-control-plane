package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.controlplane.store.identity.PseudonymDirectory;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Replaces a contributor's identity with a stable keyed pseudonym: {@code hex(HMAC-SHA256(key,
 * normalised identity))}.
 *
 * <p><strong>This is the only class permitted to create a {@link Pseudonym} from an
 * identity</strong> (an architecture test enforces it). The pseudonym is deterministic, so the same
 * person is the same pseudonym across runs, and keyed, so it cannot be recomputed from a guessed
 * identity without the deployment's secret.
 *
 * <p>The key is loaded on first use and then held. If it cannot be loaded, {@link
 * KeyStoreUnavailableException} propagates and nothing is pseudonymised: there is no fallback, and
 * a failure is not cached, so the next call retries.
 */
public final class IdentityPseudonymiser {

  private final PseudonymKeyStore keyStore;
  private final PseudonymDirectory directory;
  private final AtomicReference<PseudonymKey> key = new AtomicReference<>();

  /**
   * Creates a pseudonymiser.
   *
   * @param keyStore where the key comes from
   * @param directory remembers which identity each pseudonym stands for, for audited
   *     re-identification
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public IdentityPseudonymiser(PseudonymKeyStore keyStore, PseudonymDirectory directory) {
    this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  /**
   * Pseudonymises an identity.
   *
   * @param identity a contributor's identity, such as a name or email address
   * @return its pseudonym
   * @throws IllegalArgumentException if the identity is blank
   * @throws KeyStoreUnavailableException if the key cannot be obtained
   */
  public Pseudonym pseudonymise(String identity) {
    String normalised = normalise(identity);
    byte[] mac = key().hmac(normalised.getBytes(StandardCharsets.UTF_8));
    Pseudonym pseudonym = Pseudonym.issue(HexFormat.of().formatHex(mac));
    directory.remember(pseudonym, normalised);
    return pseudonym;
  }

  /**
   * Ensures the key is available without pseudonymising anything.
   *
   * @throws KeyStoreUnavailableException if the key cannot be obtained
   */
  public void requireKey() {
    key();
  }

  /**
   * Makes the cosmetic differences between spellings of one identity irrelevant: Unicode
   * compatibility form, surrounding whitespace, and letter case.
   *
   * @param identity an identity as it appeared in the bundle
   * @return the canonical form that is hashed
   * @throws IllegalArgumentException if nothing remains
   */
  static String normalise(String identity) {
    Objects.requireNonNull(identity, "identity");
    String normalised =
        Normalizer.normalize(identity, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    if (normalised.isEmpty()) {
      throw new IllegalArgumentException("A blank identity cannot be pseudonymised");
    }
    return normalised;
  }

  private PseudonymKey key() {
    PseudonymKey loaded = key.get();
    if (loaded == null) {
      loaded = keyStore.loadKey();
      key.compareAndSet(null, loaded);
    }
    return loaded;
  }
}
