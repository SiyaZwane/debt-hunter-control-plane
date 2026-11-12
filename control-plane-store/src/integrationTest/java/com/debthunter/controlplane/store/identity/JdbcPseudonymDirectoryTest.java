package com.debthunter.controlplane.store.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.queue.AesGcmPayloadCipher;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdbcPseudonymDirectoryTest extends PseudonymDirectoryContract {

  private static final byte[] KEY = new byte[32];
  private static final String IDENTITY = "alice@example.com";

  private static DataSource database() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  private static byte[] key(int fill) {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) fill);
    return key;
  }

  @Override
  protected PseudonymDirectory newDirectory() {
    return new JdbcPseudonymDirectory(database(), new AesGcmPayloadCipher(KEY));
  }

  @Test
  void theIdentityIsNotReadableInTheDatabase() {
    DataSource db = database();
    new JdbcPseudonymDirectory(db, new AesGcmPayloadCipher(KEY)).remember(A, IDENTITY);

    List<byte[]> stored =
        new Jdbc(db)
            .query(
                "SELECT sealed_identity FROM pseudonym_directory",
                row -> row.getBytes("sealed_identity"));

    assertThat(stored).hasSize(1);
    assertThat(new String(stored.get(0), java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain("alice")
        .doesNotContain("example.com");
  }

  @Test
  void aNewInstanceOverTheSameDatabaseStillFindsTheIdentityAsAfterARestart() {
    DataSource db = database();
    new JdbcPseudonymDirectory(db, new AesGcmPayloadCipher(KEY)).remember(A, IDENTITY);

    assertThat(new JdbcPseudonymDirectory(db, new AesGcmPayloadCipher(KEY)).lookup(A))
        .contains(IDENTITY);
  }

  @Test
  void theWrongKeyIsAnErrorNotAnUnknownPseudonym() {
    DataSource db = database();
    new JdbcPseudonymDirectory(db, new AesGcmPayloadCipher(KEY)).remember(A, IDENTITY);
    JdbcPseudonymDirectory wrongKey =
        new JdbcPseudonymDirectory(db, new AesGcmPayloadCipher(key(7)));

    assertThatThrownBy(() -> wrongKey.lookup(A))
        .isInstanceOf(PseudonymDirectoryException.class)
        .hasMessageNotContaining("alice");
  }

  @Test
  void aRowMovedOntoAnotherPseudonymCannotBeOpened() {
    DataSource db = database();
    JdbcPseudonymDirectory directory = new JdbcPseudonymDirectory(db, new AesGcmPayloadCipher(KEY));
    directory.remember(A, IDENTITY);
    new Jdbc(db)
        .update(
            "UPDATE pseudonym_directory SET pseudonym = ? WHERE pseudonym = ?",
            B.value(),
            A.value());

    assertThatThrownBy(() -> directory.lookup(B)).isInstanceOf(PseudonymDirectoryException.class);
  }

  @Test
  void aTamperedRowCannotBeOpened() {
    DataSource db = database();
    JdbcPseudonymDirectory directory = new JdbcPseudonymDirectory(db, new AesGcmPayloadCipher(KEY));
    directory.remember(A, IDENTITY);
    new Jdbc(db).update("UPDATE pseudonym_directory SET sealed_identity = ?", new byte[] {1, 2, 3});

    assertThatThrownBy(() -> directory.lookup(A)).isInstanceOf(PseudonymDirectoryException.class);
  }

  @Test
  void manyWritersRememberingTheSamePseudonymAtOnceAllSucceed() {
    PseudonymDirectory directory = newDirectory();

    CompletableFuture.allOf(
            java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> CompletableFuture.runAsync(() -> directory.remember(A, IDENTITY)))
                .toArray(CompletableFuture[]::new))
        .join();

    assertThat(directory.lookup(A)).contains(IDENTITY);
  }
}
