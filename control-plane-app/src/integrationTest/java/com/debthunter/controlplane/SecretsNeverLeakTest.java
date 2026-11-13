package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The release checklist's secrets rule: the pseudonymisation key is never logged, never serialised
 * to any output, and the other keys with it. The whole application runs with recognisable keys, a
 * run is ingested (with a real contributor identity in it), requests are refused, and then nothing
 * the application printed, returned or stored may contain any key, in any of the ways it could be
 * written. The contributor's identity may be stored only sealed.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SecretsNeverLeakTest {

  private static final TestJwtIssuer ISSUER = new TestJwtIssuer();
  private static final String PSEUDONYM_SECRET = "pseudonym-secret-key-0123456789-abcdef";
  private static final String INTAKE_SECRET = "intake-secret-key-0123456789-abcdefghi";
  private static final String DIRECTORY_SECRET = "directory-secret-key-0123456789-abcde";
  private static final String IDENTITY = "alice.leak-check@example.com";

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    ZoneTestSupport.baseline(registry, ISSUER);
    registry.add("debthunter.controlplane.pseudonym.key", () -> encode(PSEUDONYM_SECRET));
    registry.add("debthunter.controlplane.intake.encryption-key", () -> encode(INTAKE_SECRET));
    registry.add("debthunter.controlplane.pseudonym.directory-key", () -> encode(DIRECTORY_SECRET));
    registry.add(
        "debthunter.controlplane.zones.egress.trackers.jira.base-url",
        () -> "https://jira.example");
    registry.add(
        "debthunter.controlplane.zones.egress.trackers.jira.authorization",
        () -> "Basic bGVhay1jaGVjay1jcmVkZW50aWFs");
  }

  private static String encode(String secret) {
    return Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
  }

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  @Autowired private WebTestClient client;
  @Autowired private RepositoryRegistryService registry;
  @Autowired private FindingStateRepository states;
  @Autowired private DataSource dataSource;
  @Autowired private Environment environment;
  @Autowired private ControlPlaneProperties properties;

  /** The ways a key could end up written down: as configured, decoded, and hex-encoded. */
  private static List<String> forms(String secret) {
    return List.of(
        secret, encode(secret), HexFormat.of().formatHex(secret.getBytes(StandardCharsets.UTF_8)));
  }

  private static List<String> allSecrets() {
    List<String> needles = new ArrayList<>();
    for (String secret : List.of(PSEUDONYM_SECRET, INTAKE_SECRET, DIRECTORY_SECRET)) {
      needles.addAll(forms(secret));
    }
    needles.add("bGVhay1jaGVjay1jcmVkZW50aWFs");
    return needles;
  }

  private final List<String> responses = new ArrayList<>();

  private String body(WebTestClient.RequestHeadersSpec<?> request) {
    String text =
        request
            .exchange()
            .returnResult(String.class)
            .getResponseBody()
            .collectList()
            .block()
            .stream()
            .reduce("", String::concat);
    responses.add(text);
    return text;
  }

  private String dumpOfTheDatabase() throws Exception {
    StringBuilder dump = new StringBuilder();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      List<String> tables = new ArrayList<>();
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT table_name FROM information_schema.tables"
                  + " WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'")) {
        while (rs.next()) {
          tables.add(rs.getString(1));
        }
      }
      for (String table : tables) {
        try (ResultSet rs = statement.executeQuery("SELECT t::text FROM \"" + table + "\" t")) {
          while (rs.next()) {
            dump.append(rs.getString(1)).append('\n');
          }
        }
      }
    }
    return dump.toString();
  }

  @Test
  void noKeyIsLoggedReturnedStoredOrPrintedAndAnIdentityIsOnlyEverStoredSealed(
      CapturedOutput output) throws Exception {
    assertThat(environment.getProperty("debthunter.controlplane.pseudonym.key"))
        .as("the test's own keys are the ones in use")
        .isEqualTo(encode(PSEUDONYM_SECRET));

    ZoneTestSupport.onboard(registry, "acme/leak-check", "jira", "WID");
    String run =
        RunBundleBuilder.run()
            .id("l1")
            .repository("acme/leak-check")
            .commit("l1")
            .observing("fp-leak")
            .authoredBy(IDENTITY)
            .build();
    client
        .post()
        .uri("/api/v1/runs")
        .header("Authorization", "Bearer " + ISSUER.token(ZoneTestSupport.PIPELINE))
        .header("Content-Type", "application/json")
        .bodyValue(run)
        .exchange()
        .expectStatus()
        .isAccepted();
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> states.find("acme/leak-check", "default", "fp-leak").isPresent());

    // Failure paths: a refused token, a malformed bundle, a run for a repository not granted.
    body(
        client
            .post()
            .uri("/api/v1/runs")
            .header("Content-Type", "application/json")
            .bodyValue(run));
    body(
        client
            .post()
            .uri("/api/v1/runs")
            .header("Authorization", "Bearer " + ISSUER.token(ZoneTestSupport.PIPELINE))
            .header("Content-Type", "application/json")
            .bodyValue("{not json " + IDENTITY));
    body(
        client
            .post()
            .uri("/api/v1/runs")
            .header("Authorization", "Bearer " + ISSUER.token("someone-else"))
            .header("Content-Type", "application/json")
            .bodyValue(run));
    // Read paths.
    body(
        client
            .get()
            .uri("/api/v1/portfolio")
            .header(
                "Authorization",
                "Bearer " + ISSUER.tokenWithRoles("leader", "ControlPlane.Leader")));
    body(
        client
            .get()
            .uri("/api/v1/squads/team-a/findings")
            .header(
                "Authorization",
                "Bearer "
                    + ISSUER.tokenWithRolesAndSquads(
                        "s", List.of("ControlPlane.Squad"), List.of("team-a"))));

    String everything =
        output.getAll()
            + "\n"
            + String.join("\n", responses)
            + "\n"
            + properties
            + "\n"
            + dumpOfTheDatabase();

    for (String needle : allSecrets()) {
      assertThat(everything).as("a secret in some written form").doesNotContain(needle);
    }
    // The identity travels in the request and is then sealed. It must not be readable anywhere
    // the application wrote: plain, base64 or as the hex a bytea column prints.
    for (String form : forms(IDENTITY)) {
      assertThat(everything).as("a contributor identity in some written form").doesNotContain(form);
    }
    assertThat(properties.toString()).contains("<redacted>");
  }
}
