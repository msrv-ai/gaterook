package ai.msrv.gaterook.bridge;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code gw-bridge}: the JDK-only bridge between MCP clients and MCP Gateway (ARCHITECTURE.md A7, §7.2, §7.5). The
 * client configs run it through the shim {@code ~/.mcp-gateway/bin/gw-bridge}, which the core rewrites at every start
 * and which runs the native executable of the app's bundle (or the bundle's JRE with {@code gw-bridge.jar}).
 *
 * <ul>
 *   <li>{@code headers --endpoint <slug> --grant <gr_…>}: Codex's {@code http_headers_helper} ({@link Headers});</li>
 *   <li>{@code stdio --endpoint <slug> --grant <gr_…> [--client <client>]}: stdin/stdout ⇄ {@code POST /mcp/<slug>}
 *       ({@link StdioBridge});</li>
 *   <li>{@code sandbox-exec [--ro p]… [--rw p]… -- <command>}: runs an MCP server under Landlock ({@link Sandbox}).</li>
 * </ul>
 * Nothing it prints or logs carries a token; the grant id only as its prefix in {@code bridge.log}.
 */
public final class Main {

  static final String VERSION = BrokerClient.VERSION;
  private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
  private static final Pattern GRANT = Pattern.compile("^gr_[a-z0-9]{12}$");
  private static final Set<String> CLIENTS = Set.of("claude-desktop", "codex", "cursor", "antigravity");

  private Main() {}

  public static void main(String[] args) {
    // stdout is the protocol (the helper's headers, the bridge's JSON-RPC lines): nothing else may write to it.
    PrintStream protocol = System.out;
    System.setOut(System.err);
    int code = run(args, System.getenv(), System.in, protocol, System.err);
    protocol.flush();
    System.exit(code);
  }

  static int run(String[] args, Map<String, String> env, InputStream in, PrintStream out, PrintStream err) {
    if (args.length == 0) {
      err.println("usage: gw-bridge headers|stdio|sandbox-exec … (or --version)");
      return 3;
    }
    if (args[0].equals("--version")) {
      out.println(VERSION);
      return 0;
    }
    List<String> rest = Arrays.asList(args).subList(1, args.length);
    return switch (args[0]) {
      case "headers" -> options(rest, err).map(o -> Headers.run(o, env, out, err)).orElse(3);
      case "stdio" -> options(rest, err).map(o -> StdioBridge.run(o, env, in, out, err)).orElse(3);
      case "sandbox-exec" -> Sandbox.run(rest, env, err);
      default -> {
        err.println("gw-bridge: unknown command " + args[0]);
        yield 3;
      }
    };
  }

  /** {@code --name value} pairs. */
  static java.util.Optional<Map<String, String>> options(List<String> args, PrintStream err) {
    Map<String, String> out = new HashMap<>();
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i);
      if (!a.startsWith("--") || i + 1 >= args.size() || args.get(i + 1).startsWith("--")) {
        err.println("gw-bridge: " + a + (a.startsWith("--") ? " needs a value" : " is unexpected"));
        return java.util.Optional.empty();
      }
      out.put(a.substring(2), args.get(++i));
    }
    return java.util.Optional.of(out);
  }

  static boolean validEndpoint(String v) {
    return v != null && SLUG.matcher(v).matches();
  }

  static boolean validGrant(String v) {
    return v != null && GRANT.matcher(v).matches();
  }

  static boolean validClient(String v) {
    return v != null && CLIENTS.contains(v);
  }
}
