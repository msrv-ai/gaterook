package com.gaterook.core.tunnel.internal;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One tunnel agent process ({@code cloudflared} or {@code ngrok}): started with an argv (never a shell), a minimal
 * environment — of the core's only what a networked program needs to run on this OS ({@link #INHERITED}: home,
 * temporary directory, locale, the system's proxy and CA settings), never a {@code TUNNEL_*} or {@code NGROK_*} — plus
 * what the agent needs (its token travels only there, never in argv, which {@code ps} shows), output appended to
 * {@code logs/<agent>.log}, a pid file in {@code run/} so the next start of the core kills an orphan of a crash.
 * Stopping takes the whole tree down: TERM, 5 s, then KILL.
 */
public final class AgentProcess {

  /** The log is rotated once at this size before a start. */
  static final long LOG_ROTATE_BYTES = 10L * 1024 * 1024;

  /**
   * Variables of the core's environment an agent keeps (compared ignoring case, as Windows does). Electron starts the
   * core with an allowlist of its own; a core started otherwise (development, tests) must not hand the agents
   * whatever its shell had.
   */
  static final java.util.Set<String> INHERITED = java.util.Set.of("PATH", "HOME", "USER", "LOGNAME", "USERNAME",
      "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "TMP", "TEMP", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "APPDATA",
      "LOCALAPPDATA", "PROGRAMDATA", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "HTTPS_PROXY", "HTTP_PROXY",
      "NO_PROXY", "ALL_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR");

  private static final Logger log = LoggerFactory.getLogger(AgentProcess.class);

  private final Process process;
  private final Path logFile;
  private final Path pidFile;
  private final long logStart;

  private AgentProcess(Process process, Path logFile, Path pidFile, long logStart) {
    this.process = process;
    this.logFile = logFile;
    this.pidFile = pidFile;
    this.logStart = logStart;
  }

  /**
   * Starts {@code argv} in {@code workDir}.
   *
   * @param marker an argument only our launches carry (the path of the agent's config in {@code run/}): what tells
   *               an orphan of ours from another program that got the same pid
   */
  static AgentProcess start(List<String> argv, Map<String, String> secretEnv, Path workDir, Path logFile, Path pidFile,
      String marker) throws TunnelException {
    try {
      Files.createDirectories(logFile.getParent());
      if (Files.exists(logFile) && Files.size(logFile) > LOG_ROTATE_BYTES) {
        Files.move(logFile, logFile.resolveSibling(logFile.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
      }
      Files.writeString(logFile, "--- " + java.time.Instant.now() + " starting " + Path.of(argv.getFirst())
          .getFileName() + " ---\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
      // Where this run's output begins: what came before is an earlier run's.
      long logStart = Files.size(logFile);
      ProcessBuilder pb = new ProcessBuilder(argv).directory(workDir.toFile()).redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
          .redirectInput(ProcessBuilder.Redirect.PIPE);
      Map<String, String> env = pb.environment();
      Map<String, String> kept = environment(env);
      env.clear();
      env.putAll(kept);
      env.putAll(secretEnv);
      Process p = pb.start();
      try {
        ProcessHandle.Info identity = p.info();
        long started = identity.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
        Files.writeString(pidFile, p.pid() + "\n" + marker + "\n" + started + "\n"
            + identity.command().orElse("") + "\n", StandardCharsets.UTF_8);
      } catch (IOException e) {
        // Without its pid file the next start of the core couldn't find it: it doesn't outlive this failure.
        destroyTree(p.toHandle());
        throw e;
      }
      return new AgentProcess(p, logFile, pidFile, logStart);
    } catch (IOException e) {
      throw new TunnelException("Can't start " + Path.of(argv.getFirst()).getFileName() + ": " + e.getMessage());
    }
  }

  /** What of the core's environment {@code core} an agent gets: the {@link #INHERITED} variables only. */
  static Map<String, String> environment(Map<String, String> core) {
    Map<String, String> out = new HashMap<>();
    core.forEach((name, value) -> {
      if (INHERITED.contains(name.toUpperCase(Locale.ROOT))) {
        out.put(name, value);
      }
    });
    return out;
  }

  /**
   * Kills an agent left by a crashed core after checking its command marker or its recorded process identity.
   * Windows does not expose arguments through ProcessHandle, so it requires both start time and executable.
   * Called once at start, before anything publishes.
   */
  public static void killOrphan(Path pidFile) {
    if (!Files.isRegularFile(pidFile)) {
      return;
    }
    try {
      List<String> lines = Files.readAllLines(pidFile, StandardCharsets.UTF_8);
      Files.deleteIfExists(pidFile);
      if (lines.size() < 2) {
        return;
      }
      long pid = Long.parseLong(lines.get(0).strip());
      String marker = lines.get(1).strip();
      Optional<ProcessHandle> handle = ProcessHandle.of(pid);
      if (handle.isEmpty() || !handle.get().isAlive()) {
        return;
      }
      ProcessHandle.Info identity = handle.get().info();
      String[] args = identity.arguments().orElse(new String[0]);
      boolean ours = false;
      for (String a : args) {
        ours |= a.equals(marker);
      }
      if (lines.size() >= 4) {
        long started = Long.parseLong(lines.get(2));
        boolean sameStart = started > 0
            && identity.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L) == started;
        boolean sameCommand = !lines.get(3).isEmpty() && identity.command().orElse("").equals(lines.get(3));
        // A Unix launcher may exec its agent, changing the executable without changing the process identity.
        ours = sameStart && (ours || (identity.arguments().isEmpty() && sameCommand));
      }
      if (ours) {
        log.warn("Stopping tunnel agent {} left from the previous run", pid);
        destroyTree(handle.get());
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Couldn't check the tunnel agent of the previous run: {}", e.toString());
    }
  }

  public long pid() {
    return process.pid();
  }

  public boolean alive() {
    return process.isAlive();
  }

  /** Exit code once the process ended, else empty. */
  public Optional<Integer> exitCode() {
    return process.isAlive() ? Optional.empty() : Optional.of(process.exitValue());
  }

  /** Waits for the process to end, interruptibly. */
  public int waitFor() throws InterruptedException {
    return process.waitFor();
  }

  /** TERM, up to 5 s, then KILL — the agent and everything it started. */
  public void stop() {
    destroyTree(process.toHandle());
    try {
      Files.deleteIfExists(pidFile);
    } catch (IOException ignored) {
      // The next start checks the command line before it kills anything.
    }
  }

  private static void destroyTree(ProcessHandle root) {
    List<ProcessHandle> all = new ArrayList<>(root.descendants().toList());
    all.add(root);
    for (ProcessHandle h : all) {
      h.destroy();
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    for (ProcessHandle h : all) {
      long left = deadline - System.nanoTime();
      try {
        if (left > 0) {
          h.onExit().get(left, TimeUnit.NANOSECONDS);
        }
      } catch (Exception ignored) {
        // Killed below.
      }
      if (h.isAlive()) {
        h.destroyForcibly();
      }
    }
  }

  /** The last lines of the agent's log (≤ 8 KiB), for an error the person can act on. */
  public List<String> logTail() {
    return tail(logFile);
  }

  /** What this run of the agent writes to its log, from its first byte on: never a line of an earlier run. */
  public LogFollower followLog() {
    return new LogFollower(logFile, logStart);
  }

  /**
   * An agent's log as it grows: each {@link #next()} returns the lines completed since the previous one. A line the
   * agent is still writing waits for its end; one longer than {@link #LINE_LIMIT} is skipped. For one thread.
   */
  public static final class LogFollower {

    /** The most of the log one {@link #next()} reads; the rest waits for the next. */
    static final int READ_LIMIT = 1024 * 1024;

    /** No status line of an agent comes near this. */
    static final int LINE_LIMIT = 64 * 1024;

    private final Path file;
    private final java.io.ByteArrayOutputStream partial = new java.io.ByteArrayOutputStream();
    private long position;
    private boolean overlong;

    LogFollower(Path file, long position) {
      this.file = file;
      this.position = position;
    }

    /** The lines completed since the previous call, without their line ends; none while the log can't be read. */
    public List<String> next() {
      List<String> lines = new ArrayList<>();
      byte[] chunk;
      try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
        long length = raf.length();
        if (length < position) {
          // Emptied under the agent, which appends: it writes on from the new end.
          position = 0;
          partial.reset();
          overlong = false;
        }
        chunk = new byte[(int) Math.min(length - position, READ_LIMIT)];
        raf.seek(position);
        raf.readFully(chunk);
      } catch (IOException e) {
        return lines;
      }
      position += chunk.length;
      for (byte b : chunk) {
        if (b == '\n') {
          if (!overlong) {
            String line = partial.toString(StandardCharsets.UTF_8);
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
          }
          partial.reset();
          overlong = false;
        } else if (!overlong) {
          if (partial.size() < LINE_LIMIT) {
            partial.write(b);
          } else {
            overlong = true;
            partial.reset();
          }
        }
      }
      return lines;
    }
  }

  static List<String> tail(Path file) {
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long length = raf.length();
      int size = (int) Math.min(8 * 1024, length);
      byte[] buffer = new byte[size];
      raf.seek(length - size);
      raf.readFully(buffer);
      String text = new String(buffer, StandardCharsets.UTF_8);
      int start = text.lastIndexOf("--- ");
      if (start >= 0) {
        text = text.substring(start);
      }
      return List.of(text.split("\r?\n"));
    } catch (IOException e) {
      return List.of();
    }
  }

  /** Values handed to the agent through its environment only. */
  static Map<String, String> env(String name, char[] value) {
    Map<String, String> out = new HashMap<>();
    if (value != null) {
      out.put(name, new String(value));
    }
    return out;
  }
}
