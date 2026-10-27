package ai.msrv.gaterook.pluginhost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The first stage of a plugin host on Linux (ARCHITECTURE.md §7.5, review 2026-10-27):
 * {@code java -cp plugin-host.jar ai.msrv.gaterook.pluginhost.SandboxExec [--ro <path>]… [--rw <path>]… -- <command…>}.
 *
 * <p>Landlock confines a thread and the threads it starts later, not a process ({@link Sandbox}), and a JVM has many
 * threads before {@code main}. So this small JVM confines its own main thread — no new privileges, a ruleset that
 * reads the system and the {@code --ro} paths and writes the {@code --rw} paths and devices — and then
 * {@code execve}s the command on that thread: the host's JVM starts inside the domain, every thread it ever makes is
 * confined, and nothing in it can lift the restriction. The pid and the pipes stay the same, so the core sees one
 * process. The command gets the environment it was given plus {@code MCP_GATEWAY_SANDBOX=landlock}, which the host
 * reports back in {@code plugin/load}.
 *
 * <p>{@code --probe} confines this process and exits 0 when that works: the core asks once whether this system can
 * sandbox a plugin. Without Landlock (another OS, a kernel before 5.13, the LSM off) it exits 4 and never runs the
 * command unconfined: the core then starts the host without this stage and says "Not sandboxed on this system".
 * Exit codes: 0 probe passed, 3 wrong arguments, 4 no sandbox, 126 the command can't run. Nothing is written to
 * stdout, which is the protocol channel of the host that replaces this process.
 */
public final class SandboxExec {

  static final int USAGE = 3;
  static final int NO_SANDBOX = 4;
  static final int CANT_RUN = 126;

  /** The variable the host reads to report its sandbox (informational: the core knows how it started the host). */
  static final String SANDBOX_ENV = "MCP_GATEWAY_SANDBOX";

  private SandboxExec() {}

  /** The parsed command line: {@code command} is empty for {@code --probe}. */
  record Request(List<String> readOnly, List<String> readWrite, boolean probe, List<String> command) {}

  public static void main(String[] args) {
    System.exit(run(List.of(args)));
  }

  static int run(List<String> args) {
    Request request;
    try {
      request = parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println("plugin-host sandbox: " + e.getMessage());
      return USAGE;
    }
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux")) {
      System.err.println("plugin-host sandbox: Landlock is Linux only");
      return NO_SANDBOX;
    }
    int abi = Sandbox.landlockAbi();
    if (abi < 1) {
      System.err.println("plugin-host sandbox: Landlock isn't available on this system");
      return NO_SANDBOX;
    }
    List<String> readOnly = new ArrayList<>(Sandbox.SYSTEM_READ);
    readOnly.addAll(request.readOnly());
    List<String> readWrite = new ArrayList<>(Sandbox.SYSTEM_WRITE);
    readWrite.addAll(request.readWrite());
    try {
      Sandbox.restrict(abi, readOnly, readWrite);
    } catch (IllegalStateException e) {
      System.err.println("plugin-host sandbox: " + e.getMessage());
      return NO_SANDBOX;
    }
    if (request.probe()) {
      return 0;
    }
    Map<String, String> env = new LinkedHashMap<>(System.getenv());
    env.put(SANDBOX_ENV, "landlock");
    String why = Sandbox.exec(request.command(), env);
    System.err.println("plugin-host sandbox: can't run " + request.command().getFirst() + ": " + why);
    return CANT_RUN;
  }

  static Request parse(List<String> args) {
    List<String> ro = new ArrayList<>();
    List<String> rw = new ArrayList<>();
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i);
      switch (a) {
        case "--probe" -> {
          if (i != args.size() - 1) {
            throw new IllegalArgumentException("--probe takes nothing after it");
          }
          return new Request(List.copyOf(ro), List.copyOf(rw), true, List.of());
        }
        case "--" -> {
          List<String> command = List.copyOf(args.subList(i + 1, args.size()));
          if (command.isEmpty() || !command.getFirst().startsWith("/")) {
            throw new IllegalArgumentException("the command after -- is an absolute path");
          }
          return new Request(List.copyOf(ro), List.copyOf(rw), false, command);
        }
        case "--ro", "--rw" -> {
          if (i + 1 >= args.size() || !args.get(i + 1).startsWith("/")) {
            throw new IllegalArgumentException(a + " needs an absolute path");
          }
          (a.equals("--ro") ? ro : rw).add(args.get(++i));
        }
        default -> throw new IllegalArgumentException("unexpected " + a);
      }
    }
    throw new IllegalArgumentException("usage: SandboxExec [--ro <path>]… [--rw <path>]… -- <command…> | --probe");
  }
}
