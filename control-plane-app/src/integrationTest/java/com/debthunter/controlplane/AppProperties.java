package com.debthunter.controlplane;

import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Command-line arguments that configure the real application for a test. */
final class AppProperties {

  /** A valid pseudonymisation key (32 bytes, base64). */
  static final String PSEUDONYM_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final List<String> args = new ArrayList<>();

  private AppProperties() {}

  /** Everything required for the application to start, with the queue listener switched off. */
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
        .with("debthunter.controlplane.intake.bootstrap-servers", "127.0.0.1:1")
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
