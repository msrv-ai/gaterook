package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Catalog servers on npm (report 11 §1, §6.10): never {@code npx}, whose dependencies float and which reaches the
 * network on every start. The package is installed once into the server's work folder with {@code npm ci} from the
 * lockfile the signed catalog entry carries ({@code --ignore-scripts} unless the entry allows them), and started as
 * {@code node <bin>}. The entry's integrity must be the one the lockfile pins for the package, before the install and
 * on every start: the lockfile in the folder and npm's record of what it installed
 * ({@code node_modules/.package-lock.json}) both have to name it, so a package swapped or reinstalled outside the
 * gateway doesn't start.
 */
final class NpmInstaller {

  private static final Pattern NODE_RANGE = Pattern.compile("^\\s*>=\\s*v?(\\d+)(?:\\.(\\d+))?");
  private static final Pattern NODE_VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)");

  private final Launcher launcher;

  NpmInstaller(Launcher launcher) {
    this.launcher = launcher;
  }

  McpServerRuntime.NpmInstall install(Path workDir, McpServerRuntime.NpmPackage pkg, Duration timeout,
      Map<String, String> env) throws ConnectorException {
    long started = System.nanoTime();
    Optional<Path> npm = launcher.resolve("npm", workDir);
    if (npm.isEmpty()) {
      throw ConnectorException.unavailable("npm isn't on the PATH GateRook gives servers (" + launcher.path()
          + "). Install Node.js LTS, then try again.", null);
    }
    String node = launcher.nodeVersion("node", workDir).orElse(null);
    checkNode(pkg, node);
    JsonNode lock = readLock(pkg.lockfile());
    JsonNode entry = lock.path("packages").path("node_modules/" + pkg.name());
    if (!pkg.version().equals(Texts.str(entry.path("version"), null))
        || !pkg.integrity().equals(Texts.str(entry.path("integrity"), null))) {
      throw ConnectorException.badInput("The catalog's lockfile doesn't pin " + pkg.name() + "@" + pkg.version()
          + " with the integrity of the catalog entry, so it isn't installed.");
    }
    try {
      Files.createDirectories(workDir);
      ObjectNode manifest = Texts.JSON.createObjectNode();
      manifest.put("name", "mcp-gateway-server");
      manifest.put("private", true);
      manifest.putObject("dependencies").put(pkg.name(), pkg.version());
      // npm ci refuses a lockfile alone: it wants the package.json the lockfile was made for.
      Files.writeString(workDir.resolve("package.json"), Texts.JSON.writeValueAsString(manifest) + "\n");
      Files.write(workDir.resolve("package-lock.json"), pkg.lockfile());
    } catch (IOException e) {
      throw ConnectorException.unavailable("Couldn't write the server's folder " + workDir + ": " + e.getMessage(), e);
    }
    List<String> argv = new ArrayList<>(List.of(npm.get().toString(), "ci", "--no-audit", "--no-fund",
        "--loglevel=error"));
    if (pkg.ignoreScripts()) {
      argv.add("--ignore-scripts");
    }
    run(argv, workDir, env, timeout, "npm ci");
    verify(workDir, pkg.integrity());
    Path bin = binOf(workDir, pkg);
    long millis = (System.nanoTime() - started) / 1_000_000;
    return new McpServerRuntime.NpmInstall(List.of("node", workDir.relativize(bin).toString()), pkg.integrity(),
        node, millis);
  }

  /**
   * Before every start of a catalog server: the lockfile in its folder and npm's record of the installed tree both
   * pin a top-level package with {@code integrity}, at the version installed.
   */
  static void verify(Path workDir, String integrity) throws ConnectorException {
    String changed = "The installed package of this server doesn't match its catalog entry (integrity " + integrity
        + "). Reinstall it from the catalog.";
    JsonNode lock = readFile(workDir.resolve("package-lock.json"), changed);
    JsonNode installed = readFile(workDir.resolve("node_modules/.package-lock.json"), changed);
    String name = topLevelWith(lock, integrity).orElseThrow(() -> ConnectorException.denied(changed));
    JsonNode a = lock.path("packages").path("node_modules/" + name);
    JsonNode b = installed.path("packages").path("node_modules/" + name);
    if (!integrity.equals(Texts.str(b.path("integrity"), null))
        || !Texts.str(a.path("version"), "").equals(Texts.str(b.path("version"), null))) {
      throw ConnectorException.denied(changed);
    }
    JsonNode pkg = readFile(workDir.resolve("node_modules").resolve(name).resolve("package.json"), changed);
    if (!Texts.str(a.path("version"), "").equals(Texts.str(pkg.path("version"), null))) {
      throw ConnectorException.denied(changed);
    }
  }

  private static Optional<String> topLevelWith(JsonNode lock, String integrity) {
    for (Map.Entry<String, JsonNode> e : lock.path("packages").properties()) {
      String key = e.getKey();
      if (key.startsWith("node_modules/") && key.indexOf("/node_modules/") < 0
          && integrity.equals(Texts.str(e.getValue().path("integrity"), null))) {
        return Optional.of(key.substring("node_modules/".length()));
      }
    }
    return Optional.empty();
  }

  /** The file of the package's executable named {@code pkg.bin()} ({@code bin} as a string or a map). */
  private static Path binOf(Path workDir, McpServerRuntime.NpmPackage pkg) throws ConnectorException {
    Path dir = workDir.resolve("node_modules").resolve(pkg.name());
    JsonNode manifest = readFile(dir.resolve("package.json"), pkg.name() + " has no package.json after npm ci.");
    JsonNode bin = manifest.path("bin");
    String rel = bin.isString() ? bin.asString() : Texts.str(bin.path(pkg.bin()), null);
    if (rel == null) {
      throw ConnectorException.badInput(pkg.name() + "@" + pkg.version() + " has no executable named " + pkg.bin()
          + ".");
    }
    Path file = dir.resolve(rel).normalize();
    if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
      throw ConnectorException.badInput(pkg.name() + "'s executable " + rel + " isn't a file of the package.");
    }
    return file;
  }

  private static void checkNode(McpServerRuntime.NpmPackage pkg, String node) throws ConnectorException {
    if (pkg.nodeRange() == null) {
      return;
    }
    Matcher need = NODE_RANGE.matcher(pkg.nodeRange());
    if (!need.find()) {
      return;
    }
    if (node == null) {
      throw ConnectorException.unavailable(pkg.name() + " needs Node " + pkg.nodeRange().strip() + ", and node isn't "
          + "on the PATH GateRook gives servers. Install Node.js LTS.", null);
    }
    Matcher have = NODE_VERSION.matcher(node);
    if (have.find()) {
      int major = Integer.parseInt(have.group(1));
      int minor = Integer.parseInt(have.group(2));
      int needMajor = Integer.parseInt(need.group(1));
      int needMinor = need.group(2) == null ? 0 : Integer.parseInt(need.group(2));
      if (major < needMajor || (major == needMajor && minor < needMinor)) {
        throw ConnectorException.unavailable(pkg.name() + " needs Node " + pkg.nodeRange().strip() + ", found "
            + node.replace("Node ", "") + ".", null);
      }
    }
  }

  private static void run(List<String> argv, Path dir, Map<String, String> env, Duration timeout, String what)
      throws ConnectorException {
    ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
    pb.environment().clear();
    pb.environment().putAll(env);
    Process p;
    try {
      p = pb.start();
      p.getOutputStream().close();
    } catch (IOException e) {
      throw ConnectorException.unavailable("Couldn't run " + what + ": " + e.getMessage(), e);
    }
    StringBuilder output = new StringBuilder();
    Thread reader = Thread.ofVirtual().start(() -> {
      try {
        output.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        // The process ended.
      }
    });
    try {
      if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        ProcessTree.stop(p.toHandle(), Duration.ofSeconds(2));
        throw ConnectorException.timeout(what + " didn't finish in " + timeout.toSeconds() + " s.");
      }
      reader.join(2_000);
    } catch (InterruptedException e) {
      ProcessTree.stop(p.toHandle(), Duration.ofSeconds(1));
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(what + " was cancelled.");
    }
    if (p.exitValue() != 0) {
      String[] lines = output.toString().strip().split("\n");
      String last = String.join("\n", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 8),
          lines.length));
      throw ConnectorException.resourceError(what + " failed with exit code " + p.exitValue() + ":\n"
          + Texts.clean(last, 1_500, true), null);
    }
  }

  private static JsonNode readLock(byte[] lockfile) throws ConnectorException {
    try {
      JsonNode lock = Texts.JSON.readTree(lockfile);
      if (!lock.path("packages").isObject()) {
        throw ConnectorException.badInput("The catalog's lockfile has no packages (lockfileVersion 2 or later).");
      }
      return lock;
    } catch (RuntimeException e) {
      throw ConnectorException.badInput("The catalog's lockfile isn't JSON.");
    }
  }

  private static JsonNode readFile(Path file, String problem) throws ConnectorException {
    try {
      return Texts.JSON.readTree(Files.readString(file));
    } catch (IOException | RuntimeException e) {
      throw ConnectorException.denied(problem);
    }
  }

}
