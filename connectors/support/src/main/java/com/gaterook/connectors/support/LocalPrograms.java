package com.gaterook.connectors.support;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Programs of this computer that GateRook runs for the person (a kubeconfig's credential command, the Azure CLI),
 * found where a terminal would find them. A desktop app doesn't inherit the shell's {@code PATH}: after the core's own
 * {@code PATH} and the folders installers use, the login shell is asked once for its {@code PATH}
 * ({@code $SHELL -ilc 'printf %s "$PATH"'}, a minimal environment, 5 s at most, its last line only, as an rc file may
 * print a banner first), and the answer is kept for the life of the process. Nothing runs while the program is found
 * before that. A Google Cloud SDK unpacked outside the usual folders is found only there: its installer adds its
 * {@code bin}, where {@code gke-gcloud-auth-plugin} lives, to the shell's {@code PATH}.
 */
public final class LocalPrograms {

  /** What the login shell gets of the core's environment, as an MCP server's launcher asks it. */
  private static final List<String> SHELL_ENV = List.of("HOME", "LOGNAME", "SHELL", "TERM", "USER", "LANG");
  private static final long SHELL_TIMEOUT_SECONDS = 5;

  /** The login shell's folders once asked; {@code null} until then. */
  private static volatile List<Path> loginShellDirs;

  private LocalPrograms() {}

  /** The program {@code name} in the core's {@code PATH}, then in {@code usual}, then in the login shell's. */
  public static Optional<Path> find(String name, List<Path> usual) {
    return find(name, usual, LocalPrograms::loginShellDirs);
  }

  /** {@link #find(String, List)} with the login shell's folders from {@code shell}, asked only when needed. */
  public static Optional<Path> find(String name, List<Path> usual, Supplier<List<Path>> shell) {
    Optional<Path> found = firstIn(name, ownPath());
    if (found.isEmpty()) {
      found = firstIn(name, usual);
    }
    if (found.isEmpty() && !windows()) {
      found = firstIn(name, shell.get());
    }
    return found;
  }

  /**
   * The {@code PATH} a program found here runs with: its own folder first (the SDK's {@code gke-gcloud-auth-plugin}
   * runs {@code gcloud} from there), then the core's {@code PATH}, then the login shell's when it was asked.
   */
  public static String pathFor(Path program) {
    Set<String> dirs = new LinkedHashSet<>();
    Path parent = program.toAbsolutePath().getParent();
    if (parent != null) {
      dirs.add(parent.toString());
    }
    ownPath().forEach(d -> dirs.add(d.toString()));
    List<Path> shell = loginShellDirs;
    if (shell != null) {
      shell.forEach(d -> dirs.add(d.toString()));
    }
    return String.join(File.pathSeparator, dirs);
  }

  /** The login shell's {@code PATH} folders, asked once; none on Windows or when the shell doesn't answer. */
  public static List<Path> loginShellDirs() {
    List<Path> dirs = loginShellDirs;
    if (dirs == null) {
      synchronized (LocalPrograms.class) {
        dirs = loginShellDirs;
        if (dirs == null) {
          dirs = windows() ? List.of() : loginShellPath(System.getenv()).map(LocalPrograms::split).orElse(List.of());
          loginShellDirs = dirs;
        }
      }
    }
    return dirs;
  }

  /**
   * What {@code $SHELL -ilc 'printf %s "$PATH"'} prints last, the shell and its few variables taken from {@code env};
   * empty when there is no shell, it fails, or it takes longer than 5 s.
   */
  public static Optional<String> loginShellPath(Map<String, String> env) {
    String shell = env.get("SHELL");
    if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) {
      return Optional.empty();
    }
    Process p = null;
    try {
      ProcessBuilder pb = new ProcessBuilder(shell, "-ilc", "printf %s \"$PATH\"");
      pb.environment().clear();
      for (String name : SHELL_ENV) {
        String v = env.get(name);
        if (v != null) {
          pb.environment().put(name, v);
        }
      }
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      p = pb.start();
      p.getOutputStream().close();
      if (!p.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        return Optional.empty();
      }
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      String[] lines = out.split("\n");
      String last = lines[lines.length - 1].strip();
      return last.contains("/") ? Optional.of(last) : Optional.empty();
    } catch (IOException | InvalidPathException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      if (p != null && p.isAlive()) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
      }
    }
  }

  private static Optional<Path> firstIn(String name, List<Path> dirs) {
    for (Path d : dirs) {
      for (String candidate : names(name)) {
        try {
          Path p = d.resolve(candidate);
          if (Files.isRegularFile(p) && Files.isExecutable(p)) {
            return Optional.of(p.toAbsolutePath().normalize());
          }
        } catch (InvalidPathException e) {
          // A folder of PATH that isn't a path: the next one.
        }
      }
    }
    return Optional.empty();
  }

  /** {@code name}, and on Windows also with the extensions a bare name runs with. */
  private static List<String> names(String name) {
    if (!windows() || name.contains(".")) {
      return List.of(name);
    }
    return List.of(name, name + ".exe", name + ".cmd", name + ".bat");
  }

  private static List<Path> ownPath() {
    String path = System.getenv("PATH");
    return path == null ? List.of() : split(path);
  }

  private static List<Path> split(String path) {
    List<Path> out = new ArrayList<>();
    for (String d : path.split(File.pathSeparator)) {
      if (d.isBlank()) {
        continue;
      }
      try {
        out.add(Path.of(d.strip()));
      } catch (InvalidPathException e) {
        // Not a folder: left out.
      }
    }
    return List.copyOf(out);
  }

  private static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
  }
}
