package com.debthunter.controlplane;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the real data-plane CLI as a separate operating-system process, exactly as a CI pipeline
 * would, so these tests exercise the actual published contract rather than an imitation of it.
 */
final class DataPlaneRunner {

  /** What a scan did. */
  record Result(int exitCode, String output) {}

  private DataPlaneRunner() {}

  /** The data-plane jar, copied from the local Maven repository during the build. */
  static Path jar() {
    String configured = System.getProperty("debthunter.dataplane.jar");
    if (configured == null || !Files.isRegularFile(Path.of(configured))) {
      throw new IllegalStateException(
          "The data-plane CLI jar is missing ("
              + configured
              + "). Install it first: in the debt-hunter repository run"
              + " `./mvnw -pl cli -am install -DskipTests -Djacoco.skip=true`.");
    }
    return Path.of(configured);
  }

  /** Creates a small Git repository with a commit, so a scan has something to inspect. */
  static Path createRepository(Path directory) throws IOException, InterruptedException {
    Files.createDirectories(directory);
    Path real = directory.toRealPath();
    run(real, "git", "init", "-q", "-b", "main");
    Files.writeString(real.resolve("Foo.java"), "class Foo {}\n");
    run(real, "git", "add", ".");
    run(
        real,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-q",
        "-m",
        "add Foo");
    return real;
  }

  /**
   * Scans a repository, optionally publishing the result.
   *
   * @param repository the repository to scan
   * @param outputDirectory where reports are written
   * @param publishEndpoint where to publish the result, or {@code null} for no publication
   * @param bearerToken the token to publish with, or {@code null}
   */
  static Result scan(
      Path repository, Path outputDirectory, String publishEndpoint, String bearerToken)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(jar().toString());
    command.add("scan");
    command.add("--repo");
    command.add(repository.toString());
    command.add("--output-dir");
    command.add(outputDirectory.toString());
    if (publishEndpoint != null) {
      command.add("--publish-endpoint");
      command.add(publishEndpoint);
      command.add("--publish-timeout");
      command.add("PT10S");
    }
    if (bearerToken != null) {
      command.add("--publish-api-key");
      command.add(bearerToken);
    }
    return run(repository, command.toArray(String[]::new));
  }

  private static Result run(Path workingDirectory, String... command)
      throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Timed out: " + String.join(" ", command));
    }
    int exit = process.exitValue();
    if (exit != 0 && command[0].equals("git")) {
      throw new IllegalStateException("git failed (" + exit + "): " + output);
    }
    return new Result(exit, output);
  }
}
