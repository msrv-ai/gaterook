package com.gaterook.core.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Directory redirects without requiring Windows symbolic-link privileges. */
public final class TestFileLinks {
  private TestFileLinks() {}

  public static boolean windows() {
    return System.getProperty("os.name").startsWith("Windows");
  }

  public static Path directoryLink(Path link, Path target) throws IOException {
    if (!windows()) return Files.createSymbolicLink(link, target);
    Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
        link.toAbsolutePath().toString(), target.toAbsolutePath().toString()).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("Creating a test junction timed out");
      }
      if (process.exitValue() != 0) {
        throw new IOException("Cannot create test junction: "
            + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Creating a test junction was interrupted", e);
    }
    return link;
  }
}
