package ai.msrv.gaterook.e2e.clients;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Where the K1 process tests put a gateway home and a user home: under {@code target/<name>}, or under {@code /tmp} on
 * macOS — the broker's socket lives in {@code <gateway home>/run/}, and a Unix socket's name has at most 103 bytes
 * there, fewer than a worktree's {@code target/} path takes.
 */
final class TestRoots {

  private TestRoots() {}

  static Path create(String name) throws IOException {
    Path base = System.getProperty("os.name", "").startsWith("Mac") ? Path.of("/tmp")
        : Files.createDirectories(Path.of("target", name).toAbsolutePath());
    return Files.createTempDirectory(base, "k1-");
  }

  /** Removes a root made under {@code /tmp}; one under {@code target/} goes with {@code mvn clean}. */
  static void remove(Path root) {
    if (root == null || !root.startsWith("/tmp")) {
      return;
    }
    try (Stream<Path> files = Files.walk(root)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // Best effort: the system cleans /tmp too.
        }
      });
    } catch (IOException ignored) {
      // Already gone.
    }
  }
}
