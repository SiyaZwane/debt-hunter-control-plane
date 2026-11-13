package com.debthunter.controlplane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Finds and reads this repository's own files, for the tests that check the build's rules. */
final class RepositoryFiles {

  private RepositoryFiles() {}

  /** The root of the repository: the nearest ancestor that holds the application module. */
  static Path root() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("control-plane-app"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("Cannot find the repository root from user.dir");
    }
    return dir;
  }

  /** Every file under a module's {@code src} directory, across all control-plane modules. */
  static List<Path> sourceFiles() {
    try (Stream<Path> modules = Files.list(root())) {
      return modules
          .filter(p -> p.getFileName().toString().startsWith("control-plane-"))
          .map(p -> p.resolve("src"))
          .filter(Files::isDirectory)
          .flatMap(
              src -> {
                try {
                  return Files.walk(src);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              })
          .filter(Files::isRegularFile)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
