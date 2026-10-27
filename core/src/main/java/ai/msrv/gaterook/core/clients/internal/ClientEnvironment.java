package ai.msrv.gaterook.core.clients.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the clients of this computer keep their configs, and where the gateway keeps what they run: the user's home,
 * the system, the variables a client honours ({@code CODEX_HOME}, {@code APPDATA}, {@code LOCALAPPDATA}), the gateway
 * home ({@code ~/.mcp-gateway}) with its shim and broker file, and the bundle of the desktop app.
 *
 * <p>A <em>sandboxed</em> environment (a test or demo home instead of the person's own) ignores the process's
 * variables: everything derives from its home, so nothing the developer's real clients read is ever touched.
 */
public final class ClientEnvironment {

  /** The three desktop systems; paths and quoting differ by system. */
  public enum Os {
    MAC, WINDOWS, LINUX;

    public static Os current() {
      String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      return name.startsWith("mac") || name.startsWith("darwin") ? MAC : name.startsWith("windows") ? WINDOWS : LINUX;
    }

    /** {@code darwin}, {@code win32}, {@code linux}: the platform names of MCPB manifests and Node. */
    public String nodePlatform() {
      return switch (this) {
        case MAC -> "darwin";
        case WINDOWS -> "win32";
        case LINUX -> "linux";
      };
    }
  }

  private final Os os;
  private final Path userHome;
  private final Path gatewayHome;
  private final Path resources;
  private final Map<String, String> variables;
  private final boolean sandboxed;
  private final String hostname;

  /**
   * @param resources the desktop app's bundle ({@code -Dgateway.resources}), or {@code null} in a development run
   * @param variables the process's environment; ignored when {@code sandboxed}
   */
  public ClientEnvironment(Os os, Path userHome, Path gatewayHome, Path resources, Map<String, String> variables,
      boolean sandboxed, String hostname) {
    this.os = Objects.requireNonNull(os, "os");
    this.userHome = userHome.toAbsolutePath().normalize();
    this.gatewayHome = gatewayHome.toAbsolutePath().normalize();
    this.resources = resources;
    this.variables = sandboxed ? Map.of() : Map.copyOf(variables);
    this.sandboxed = sandboxed;
    this.hostname = hostname;
  }

  public Os os() {
    return os;
  }

  public Path userHome() {
    return userHome;
  }

  public Path gatewayHome() {
    return gatewayHome;
  }

  public Optional<Path> resources() {
    return Optional.ofNullable(resources);
  }

  public boolean sandboxed() {
    return sandboxed;
  }

  /** A variable of the process; never in a sandboxed environment. */
  public Optional<String> variable(String name) {
    String v = variables.get(name);
    return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
  }

  /** {@code $CODEX_HOME}, else {@code ~/.codex}. */
  public Path codexHome() {
    return variable("CODEX_HOME").map(Path::of).orElse(userHome.resolve(".codex"));
  }

  /** {@code %APPDATA%} (Windows roaming profile). */
  public Path appData() {
    return variable("APPDATA").map(Path::of).orElse(userHome.resolve("AppData").resolve("Roaming"));
  }

  /** {@code %LOCALAPPDATA%}. */
  public Path localAppData() {
    return variable("LOCALAPPDATA").map(Path::of).orElse(userHome.resolve("AppData").resolve("Local"));
  }

  /** {@code ~/.mcp-gateway/bin/gw-bridge} ({@code gw-bridge.cmd} on Windows): what client configs run. */
  public Path shim() {
    return gatewayHome.resolve("bin").resolve(os == Os.WINDOWS ? "gw-bridge.cmd" : "gw-bridge");
  }

  /**
   * What a client that starts processes itself (Node: no {@code .cmd} without a shell, CVE-2024-27980) runs on
   * Windows: the bridge executable copied next to the shim. Elsewhere the shim itself.
   */
  public Path bridgeExecutable() {
    return os == Os.WINDOWS ? gatewayHome.resolve("bin").resolve("gw-bridge.exe") : shim();
  }

  /** {@code ~/.mcp-gateway/run/broker.json}: where Electron's broker says it listens. */
  public Path brokerInfo() {
    return gatewayHome.resolve("run").resolve("broker.json");
  }

  /** Where the Claude Desktop extensions are built. */
  public Path mcpbDirectory() {
    return gatewayHome.resolve("clients").resolve("claude-desktop");
  }

  public Path backups() {
    return gatewayHome.resolve("backups");
  }

  /**
   * The helper command for Codex's {@code http_headers_helper} (a string Codex runs with {@code sh -c}, or
   * {@code cmd /C} on Windows): the shim under {@code ~} when the gateway home is the default one (Codex expands
   * {@code ~}, report 03 §7.2), else its absolute path quoted for the shell.
   */
  public String helperCommand(String... args) {
    StringBuilder b = new StringBuilder();
    if (os == Os.WINDOWS) {
      Path defaultHome = userHome.resolve(".mcp-gateway");
      String shim = gatewayHome.equals(defaultHome) && !sandboxed ? "%USERPROFILE%\\.mcp-gateway\\bin\\gw-bridge.cmd"
          : shim().toString();
      b.append('"').append(shim).append('"');
      for (String a : args) {
        b.append(' ').append(windowsArg(a));
      }
      return b.toString();
    }
    if (gatewayHome.equals(userHome.resolve(".mcp-gateway"))) {
      b.append("~/.mcp-gateway/bin/gw-bridge");
    } else {
      b.append(shQuote(shim().toString()));
    }
    for (String a : args) {
      b.append(' ').append(shQuoteIfNeeded(a));
    }
    return b.toString();
  }

  /** POSIX single quotes: {@code '} becomes {@code '\''}. */
  public static String shQuote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  private static String shQuoteIfNeeded(String s) {
    return s.matches("[A-Za-z0-9_./=:@%+-]+") ? s : shQuote(s);
  }

  private static String windowsArg(String s) {
    return s.matches("[A-Za-z0-9_./=:@+-]+") ? s : "\"" + s.replace("\"", "\"\"") + "\"";
  }

  /** {@code laptop}: the first label of the host name, as an access name ({@code codex-laptop}) can use it. */
  public String shortHostname() {
    String h = hostname == null ? "" : hostname.toLowerCase(Locale.ROOT);
    int dot = h.indexOf('.');
    if (dot > 0) {
      h = h.substring(0, dot);
    }
    h = h.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (h.length() > 20) {
      h = h.substring(0, 20).replaceAll("-+$", "");
    }
    return h.isEmpty() ? "local" : h;
  }

  /** The host name without asking DNS: the variables the systems set, else {@code /etc/hostname}. */
  public static String readHostname(Map<String, String> variables) {
    for (String name : new String[] {"HOSTNAME", "COMPUTERNAME"}) {
      String v = variables.get(name);
      if (v != null && !v.isBlank()) {
        return v.strip();
      }
    }
    try {
      Path etc = Path.of("/etc/hostname");
      if (Files.isRegularFile(etc)) {
        return Files.readString(etc).strip();
      }
    } catch (Exception ignored) {
      // No host name: the labels say "local".
    }
    return "local";
  }

  /** A path under the user's home written with {@code ~}, as the kit shows config paths. */
  public String display(Path path) {
    Path abs = path.toAbsolutePath().normalize();
    if (abs.startsWith(userHome) && !abs.equals(userHome)) {
      String rest = userHome.relativize(abs).toString();
      return os == Os.WINDOWS ? "%USERPROFILE%\\" + rest : "~/" + rest.replace('\\', '/');
    }
    return abs.toString();
  }
}
