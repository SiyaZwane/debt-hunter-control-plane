package com.debthunter.controlplane.store.identity;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.queue.PayloadCipher;
import com.debthunter.controlplane.store.queue.PayloadCipherException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Keeps the directory in PostgreSQL ({@code pseudonym_directory}) with each identity sealed.
 *
 * <p>The identity is sealed with AES-256-GCM under its own key, separate from the intake key, and
 * the pseudonym is bound into the seal as context. A row copied onto another pseudonym, or read
 * with the wrong key, therefore fails to open and is reported as a {@link
 * PseudonymDirectoryException} rather than as an unknown pseudonym.
 *
 * <p>Nothing here removes or changes a row: the first identity seen for a pseudonym is the one
 * kept, and old entries are never purged, because re-identifying a person named in an old audit
 * entry must keep working.
 */
public final class JdbcPseudonymDirectory implements PseudonymDirectory {

  private final Jdbc jdbc;
  private final PayloadCipher cipher;

  /**
   * Creates the directory.
   *
   * @param dataSource where connections come from
   * @param cipher seals and opens identities; it must use a key of its own
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public JdbcPseudonymDirectory(DataSource dataSource, PayloadCipher cipher) {
    this.jdbc = new Jdbc(dataSource);
    this.cipher = Objects.requireNonNull(cipher, "cipher");
  }

  @Override
  public void remember(Pseudonym pseudonym, String identity) {
    byte[] sealed = cipher.seal(identity.getBytes(StandardCharsets.UTF_8), context(pseudonym));
    jdbc.update(
        "INSERT INTO pseudonym_directory (pseudonym, sealed_identity) VALUES (?, ?)"
            + " ON CONFLICT DO NOTHING",
        pseudonym.value(),
        sealed);
  }

  @Override
  public Optional<String> lookup(Pseudonym pseudonym) {
    return jdbc.queryOne(
            "SELECT sealed_identity FROM pseudonym_directory WHERE pseudonym = ?",
            row -> row.getBytes("sealed_identity"),
            pseudonym.value())
        .map(blob -> open(pseudonym, blob));
  }

  private String open(Pseudonym pseudonym, byte[] blob) {
    try {
      return new String(cipher.open(blob, context(pseudonym)), StandardCharsets.UTF_8);
    } catch (PayloadCipherException e) {
      throw new PseudonymDirectoryException(
          "A stored identity could not be opened; the key may be wrong or the row altered", e);
    }
  }

  private static String context(Pseudonym pseudonym) {
    return "pseudonym-directory:" + pseudonym.value();
  }
}
