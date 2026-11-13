package com.debthunter.controlplane;

import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Command-line arguments that configure the real application for a test. */
final class AppProperties {

  /** A valid intake encryption key (32 bytes, base64). */
  static final String INTAKE_KEY =
      Base64.getEncoder()
          .encodeToString(
              new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
                24, 25, 26, 27, 28, 29, 30, 31, 32
              });

  /** A valid pseudonymisation key (32 bytes, base64). */
  static final String PSEUDONYM_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  static final String DIRECTORY_KEY =
      Base64.getEncoder().encodeToString("directory-key-directory-key-0123".getBytes());

  private final List<String> args = new ArrayList<>();

  private AppProperties() {}

  /** Everything required for the application to start, with the intake worker switched off. */
  static AppProperties valid(TestJwtIssuer issuer) {
    PostgresTestDatabase.Connection db = PostgresTestDatabase.freshSchemaConnection();
    return new AppProperties()
        .with("server.port", "0")
        .with("debthunter.controlplane.security.jwk-set-uri", issuer.startJwksServer().toString())
        .with("debthunter.controlplane.security.issuer", TestJwtIssuer.ISSUER)
        .with("debthunter.controlplane.security.audience", TestJwtIssuer.AUDIENCE)
        .with("debthunter.controlplane.database.url", db.jdbcUrl())
        .with("debthunter.controlplane.database.username", db.username())
        .with("debthunter.controlplane.database.password", db.password())
        .with("debthunter.controlplane.pseudonym.key", PSEUDONYM_KEY)
        .with("debthunter.controlplane.pseudonym.directory-key", DIRECTORY_KEY)
        .with("debthunter.controlplane.intake.encryption-key", INTAKE_KEY)
        .with("debthunter.controlplane.intake.listener-enabled", "false");
  }

  /** Sets (or overrides) a property. Command-line arguments beat application.yml. */
  AppProperties with(String key, String value) {
    args.removeIf(a -> a.startsWith("--" + key + "="));
    args.add("--" + key + "=" + value);
    return this;
  }

  /** Blanks a property, as if it had not been configured. */
  AppProperties without(String key) {
    return with(key, "");
  }

  String[] args() {
    return args.toArray(String[]::new);
  }
}
