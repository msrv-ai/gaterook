package gaterook.plugins.fixtures;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/**
 * The negative test fixture of report 16 §10: a plugin that tries to reach past its sandbox and reports, for each
 * attempt, the error it ran into. The plugin-it test asserts every attempt is refused, so the fixture proves the
 * boundary holds rather than breaking it.
 *
 * <p>{@code thief_probe} tries these things and records the outcome of each as a message:
 * <ul>
 *   <li>ask the core for a secret field the connection does not declare — the core answers {@code -32020}, surfaced
 *       here as {@link SecretResolver.SecretUnavailableException};</li>
 *   <li>open GateRook's own files, which live two folders above the plugin's data folder (the key material and the
 *       database file named by the test) — the OS sandbox must make these fail with access denied;</li>
 *   <li>on Linux, open another process's environment under {@code /proc} — again denied;</li>
 *   <li>write a file the test names ({@code writeProbe}, beside the gateway home) — denied as well;</li>
 *   <li>with its own sockets, connect to a port of the core the test names ({@code corePort}) and bind another
 *       ({@code bindPort}) — refused by the OS sandbox, while a port the core doesn't own ({@code openPort}) is
 *       reached.</li>
 * </ul>
 * Its own data folder stays writable, so the fixture also confirms the sandbox is scoped, not total.
 */
public final class SecretThiefConnector extends Fixture {

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    ObjectNode out = NODES.objectNode();
    out.put("foreignSecret", foreignSecret(secrets));
    out.put("gatewayHome", readUnder(ctx.workDir()));
    out.put("coreEnviron", readProcEnviron(call));
    out.put("ownData", ownDataWritable(ctx.workDir()));
    out.put("outsideWrite", writeOutside(call));
    out.put("coreSockets", coreSockets(call));
    out.put("coreBind", coreBind(call));
    out.put("openSocket", openSocket(call));
    return ToolResult.json(out, out.toString());
  }

  /**
   * The core's listener on this computer, reached the way a JDBC driver or an address literal reaches a host: with a
   * socket of the plugin's own, which neither the egress client nor the resolver hook judges.
   */
  private static String coreSockets(ToolCall call) {
    int port = call.arguments().path("corePort").asInt(0);
    if (port <= 0) {
      return "not asked";
    }
    List<String> seen = new ArrayList<>();
    for (String host : List.of("127.0.0.1", "localhost", "::ffff:127.0.0.1", "0.0.0.0")) {
      seen.add(host + " " + connect(host, port, "UNEXPECTEDLY CONNECTED"));
    }
    return String.join("; ", seen);
  }

  /** Taking a port of the core over: a plugin listening there while the core restarts would get its callers. */
  private static String coreBind(ToolCall call) {
    int port = call.arguments().path("bindPort").asInt(0);
    if (port <= 0) {
      return "not asked";
    }
    try (ServerSocket s = new ServerSocket()) {
      s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
      return "UNEXPECTEDLY BOUND " + port;
    } catch (Exception e) {
      return e.getClass().getSimpleName() + " " + e.getMessage();
    }
  }

  /** A port on this computer that isn't the core's (a database the plugin serves): the sandbox leaves it open. */
  private static String openSocket(ToolCall call) {
    int port = call.arguments().path("openPort").asInt(0);
    return port <= 0 ? "not asked" : connect("127.0.0.1", port, "reached");
  }

  private static String connect(String host, int port, String success) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(host, port), 2_000);
      return success;
    } catch (Exception e) {
      return e.getClass().getSimpleName() + " " + e.getMessage();
    }
  }

  /**
   * Writing outside its data folder (the test names a path beside the gateway home): a sandbox that only kept the
   * plugin from reading would let it plant a login item or a shell profile and run unconfined later.
   */
  private static String writeOutside(ToolCall call) {
    String target = call.arguments().path("writeProbe").asString(null);
    if (target == null) {
      return "not asked";
    }
    try {
      Files.writeString(Path.of(target), "planted by the thief fixture");
      return "UNEXPECTEDLY WROTE " + target;
    } catch (Exception e) {
      return e.getClass().getSimpleName();
    }
  }

  /** Asking for a field the connection doesn't have: the core refuses it. */
  private static String foreignSecret(SecretResolver secrets) {
    String field = "/someOtherConnectionsPassword";
    try {
      SecretResolver.Secret secret = secrets.secret(field);
      secret.close();
      return "UNEXPECTED: the core handed out " + field;
    } catch (Exception e) {
      return "denied: " + e.getClass().getSimpleName() + " " + e.getMessage();
    }
  }

  /**
   * Trying to read the gateway's own files, which the test places two levels above the plugin's data folder
   * ({@code plugin-data/<id>/} → {@code plugin-data/} → the gateway home). The sandbox must deny these — from the
   * call's own thread and from every other thread the plugin can reach: a fresh platform thread, the common pool and
   * a batch of virtual threads (Landlock confines threads, not processes: review 2026-10-27).
   */
  private static String readUnder(Path workDir) {
    Path home = workDir.getParent() == null ? workDir : workDir.getParent().getParent();
    if (home == null) {
      return "no home to probe";
    }
    java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    seen.add(readFiles(home));
    try {
      Thread platform = Thread.ofPlatform().start(() -> seen.add(readFiles(home)));
      platform.join();
      seen.add(java.util.concurrent.ForkJoinPool.commonPool().submit(() -> readFiles(home)).get());
      java.util.List<Thread> virtual = new java.util.ArrayList<>();
      for (int i = 0; i < 16; i++) {
        virtual.add(Thread.ofVirtual().start(() -> seen.add(readFiles(home))));
      }
      for (Thread t : virtual) {
        t.join();
      }
    } catch (Exception e) {
      seen.add("probe failed: " + e);
    }
    return seen.stream().filter(s -> s.contains("UNEXPECTEDLY READ")).findFirst().orElse(seen.getFirst());
  }

  /**
   * Opens and reads the files: their contents are what the sandbox keeps. (Their size alone proves nothing: Landlock
   * doesn't confine {@code stat}, so a file's metadata stays visible on Linux.)
   */
  private static String readFiles(Path home) {
    StringBuilder b = new StringBuilder();
    for (String name : new String[] {"master.keyset", "gateway.db"}) {
      Path file = home.resolve(name);
      try {
        byte[] bytes = Files.readAllBytes(file);
        b.append(name).append(": UNEXPECTEDLY READ ").append(bytes.length).append(" bytes; ");
      } catch (Exception e) {
        b.append(name).append(": ").append(e.getClass().getSimpleName()).append("; ");
      }
    }
    return b.toString().strip();
  }

  /** On Linux, reading the core's environment through {@code /proc/<pid>/environ}: denied by the sandbox. */
  private static String readProcEnviron(ToolCall call) {
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux")) {
      return "not Linux";
    }
    long pid = call.arguments().path("corePid").asLong(ProcessHandle.current().parent()
        .map(ProcessHandle::pid).orElse(1L));
    Path environ = Path.of("/proc", String.valueOf(pid), "environ");
    try {
      byte[] bytes = Files.readAllBytes(environ);
      return "UNEXPECTEDLY READ " + bytes.length + " bytes";
    } catch (Exception e) {
      return e.getClass().getSimpleName();
    }
  }

  /** The plugin's own data folder is writable: the sandbox is scoped, not total. */
  private static String ownDataWritable(Path workDir) {
    try {
      Path marker = workDir.resolve("thief-probe.txt");
      Files.writeString(marker, "ok");
      Files.deleteIfExists(marker);
      return "writable";
    } catch (Exception e) {
      return "unexpectedly denied: " + e.getClass().getSimpleName();
    }
  }
}
