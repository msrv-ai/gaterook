package ai.msrv.gaterook.pluginhost;

import ai.msrv.gaterook.plugin.protocol.Gpp;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;

/**
 * The plugin host process (ARCHITECTURE.md §7.5, report 16): one plugin generation, GPP/1 over stdio, gone when the
 * core's end of stdin is.
 *
 * <p>The first thing it does is take stdout for the protocol and point {@code System.out} at stderr: a plugin's
 * {@code println} can't break the channel (a plugin writing to the raw descriptor still can, which the core survives:
 * it skips lines that aren't JSON and restarts a host that keeps writing them). Then no core dumps or same-user
 * ptrace ({@link Hardening}).
 *
 * <p>{@code --protocol 1} is required and must match: the core and the host ship together, a mismatch is a build bug
 * ("Plugin runtime mismatch", exit 64). {@code --train} runs the built-in AOT training workload ({@link Train}). On
 * Linux the core starts this class through {@link SandboxExec}, which confines the process before it starts.
 */
public final class Main {

  static final int EXIT_USAGE = 64;

  private Main() {}

  public static void main(String[] args) {
    OutputStream protocol = new FileOutputStream(FileDescriptor.out);
    System.setOut(System.err);
    Instant started = ProcessHandle.current().info().startInstant().orElse(Instant.now());
    boolean noDumps = Hardening.noDumps();
    List<String> argv = List.of(args);
    if (argv.contains("--version")) {
      System.err.println("plugin-host " + version() + " (GPP/" + Gpp.PROTOCOL + ")");
      System.exit(0);
    }
    if (argv.contains("--train")) {
      exit(Train.run());
      return;
    }
    int i = argv.indexOf("--protocol");
    String wanted = i >= 0 && i + 1 < argv.size() ? argv.get(i + 1) : null;
    if (!String.valueOf(Gpp.PROTOCOL).equals(wanted)) {
      System.err.println("Plugin runtime mismatch: the core asked for GPP/" + wanted + ", this host speaks GPP/"
          + Gpp.PROTOCOL);
      exit(EXIT_USAGE);
      return;
    }
    if (!noDumps && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux")) {
      System.err.println("plugin-host: couldn't turn core dumps off (prctl)");
    }
    Host host = new Host(new FileInputStream(FileDescriptor.in), protocol, started);
    exit(host.start().join());
  }

  /** {@code Implementation-Version} of the jar; {@code dev} when run from classes. */
  static String version() {
    String v = Main.class.getPackage().getImplementationVersion();
    return v == null ? "dev" : v;
  }

  /**
   * {@code System.exit}, and {@code halt} if shutdown hooks (a plugin may have registered one) don't let the JVM end
   * within a second: the core counts on a host gone at EOF.
   */
  private static void exit(int code) {
    Thread.ofPlatform().daemon().name("halt").start(() -> {
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        return;
      }
      Runtime.getRuntime().halt(code);
    });
    System.exit(code);
  }
}
