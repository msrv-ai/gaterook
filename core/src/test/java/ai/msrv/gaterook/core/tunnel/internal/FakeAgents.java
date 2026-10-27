package ai.msrv.gaterook.core.tunnel.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The fake tunnel agents as the core sees them: an executable per agent (a {@code sh} wrapper that {@code exec}s
 * {@link FakeCloudflared} or {@link FakeNgrok} in a JVM of its own, so the pid the core holds is the agent's), a pins
 * file naming it by path with its real SHA-256 ({@code gateway.test.tunnel-pins}), and a control file the test
 * rewrites to change what the agent does next time without changing its hash.
 */
public final class FakeAgents {

  /** One fake agent: what to pin, how to steer it, where it reports. */
  public record Agent(String tool, Path executable, Path control, Path reportFile) {

    /** What the next start of this agent does ({@code mode}, {@code hostname}, …). */
    public void control(Map<String, String> values) {
      Properties p = new Properties();
      p.putAll(values);
      p.setProperty("report", reportFile.toString());
      try {
        Files.deleteIfExists(reportFile);
        try (OutputStream out = Files.newOutputStream(control)) {
          p.store(out, "control of the fake " + tool);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /** What the running agent reported (argv, token, edge port), waiting up to 20 s for it. */
    public Properties report() {
      long deadline = System.nanoTime() + 20_000_000_000L;
      while (!Files.isRegularFile(reportFile)) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("The fake " + tool + " never reported");
        }
        sleep(50);
      }
      Properties p = new Properties();
      try (InputStream in = Files.newInputStream(reportFile)) {
        p.load(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return p;
    }

    public List<String> argv() {
      return List.of(report().getProperty("argv").split("\u0001"));
    }

    public int edgePort() {
      return Integer.parseInt(report().getProperty("edgePort"));
    }

    public long pid() {
      return Long.parseLong(report().getProperty("pid"));
    }

    public String sha256() {
      try {
        return TunnelBinaries.sha256(executable);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private FakeAgents() {}

  public static Agent cloudflared(Path dir) {
    return agent(dir, "cloudflared", FakeCloudflared.class);
  }

  public static Agent ngrok(Path dir) {
    return agent(dir, "ngrok", FakeNgrok.class);
  }

  private static Agent agent(Path dir, String tool, Class<?> main) {
    try {
      Files.createDirectories(dir);
      Path control = dir.resolve(tool + ".properties").toAbsolutePath();
      Path report = dir.resolve(tool + ".report").toAbsolutePath();
      Path executable = dir.resolve(tool).toAbsolutePath();
      String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      String classes = Path.of(main.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
      // C1 only: the fake has nothing to optimise, and a quick start keeps the tests short.
      Files.writeString(executable, "#!/bin/sh\nexec '" + java + "' -XX:TieredStopAtLevel=1 -Xshare:auto -cp '"
          + classes + "' " + main.getName() + " '" + control + "' \"$@\"\n", StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
      Agent agent = new Agent(tool, executable, control, report);
      agent.control(Map.of("mode", "ready"));
      return agent;
    } catch (IOException | java.net.URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A pins file for {@code gateway.test.tunnel-pins} naming the agents by path; {@code null} leaves a tool out
   * (unavailable on this platform). {@code shaOverride} pins a wrong hash for the swapped-binary tests.
   */
  public static Path pins(Path dir, Agent cloudflared, Agent ngrok, String shaOverride) {
    ObjectNode root = JsonMapper.builder().build().createObjectNode();
    String platform = TunnelBinaries.platform(System.getProperty("os.name"), System.getProperty("os.arch"));
    for (Agent a : new Agent[] {cloudflared, ngrok}) {
      if (a == null) {
        continue;
      }
      ObjectNode tool = root.putObject(a.tool());
      tool.put("version", a.tool().equals("cloudflared") ? "2026.10.0" : "3.39.11");
      ObjectNode p = tool.putObject("platforms").putObject(platform);
      p.put("path", a.executable().toString());
      p.put("binary", a.tool());
      p.put("binarySha256", shaOverride != null ? shaOverride : a.sha256());
    }
    try {
      Path file = dir.resolve("pins-" + System.nanoTime() + ".json").toAbsolutePath();
      Files.writeString(file, root.toPrettyString(), StandardCharsets.UTF_8);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Whether the process {@code pid} still runs (the agent the core stopped must be gone). */
  public static boolean alive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  /** Waits up to 10 s for {@code pid} to end. */
  public static void awaitGone(long pid) {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (alive(pid)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Process " + pid + " still runs");
      }
      sleep(50);
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
