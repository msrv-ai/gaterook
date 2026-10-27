package ai.msrv.gaterook.core.clients.internal;

import ai.msrv.gaterook.core.clients.internal.ClientEnvironment.Os;
import ai.msrv.gaterook.core.clients.internal.persistence.ClientKind;
import ai.msrv.gaterook.core.clients.internal.persistence.LinkMethod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What GateRook knows about one client (report 03 §1a): how to find it on this computer, which config file it
 * reads, how it can sign in, and the entry that connects it to an endpoint. Paths are per system; the project configs
 * ({@code .cursor/mcp.json} of a repository, {@code .agents/mcp_config.json}) are never touched.
 */
public abstract sealed class ClientAdapter {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** Whether the client is on this computer, the file it reads, and what showed it. */
  public record Detection(boolean installed, Path configPath, List<String> evidence, String version) {}

  /**
   * What one Connect writes into a config file.
   *
   * @param json      the entry under {@code rootKey} (JSON files), or {@code null}
   * @param tomlBlock our marked block (Codex), or {@code null}
   */
  public record Entry(String rootKey, String name, ObjectNode json, String tomlBlock) {}

  /**
   * Everything an entry is made of.
   *
   * @param url   {@code http://127.0.0.1:<port>/mcp/<slug>}
   * @param token the value of a named token, or the placeholder {@code ‹new token›} of a preview; {@code null}
   *              unless {@code method} is {@code token}
   */
  public record EntryValues(String slug, String url, String grantId, String token, String approvalMode,
      int toolTimeoutSec) {}

  public static final String TOKEN_PLACEHOLDER = "‹new token›";

  public abstract ClientKind kind();

  public abstract String displayName();

  /** Ways this client can be connected; the first is the default. */
  public abstract List<LinkMethod> methods();

  /** The user-level config file the client reads (it may not exist yet). */
  public abstract Path configPath(ClientEnvironment env);

  public abstract Detection detect(ClientEnvironment env);

  /** The entry for {@code method}. */
  public abstract Entry entry(ClientEnvironment env, LinkMethod method, String name, EntryValues v);

  /** Whether {@code method} writes the token into the file (Cursor and Antigravity, {@code token}). */
  public boolean tokenInFile(LinkMethod method) {
    return method == LinkMethod.TOKEN;
  }

  public static List<ClientAdapter> all() {
    return List.of(new ClaudeDesktop(), new Codex(), new Cursor(), new Antigravity());
  }

  public static ClientAdapter of(ClientKind kind) {
    return all().stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
  }

  /** The stdio entry of {@code gw-bridge stdio}: no token in the file. */
  static ObjectNode bridgeEntry(ClientEnvironment env, EntryValues v, String client, boolean typed) {
    ObjectNode e = JSON.createObjectNode();
    if (typed) {
      e.put("type", "stdio");
    }
    e.put("command", env.bridgeExecutable().toString());
    ArrayNode args = e.putArray("args");
    for (String a : List.of("stdio", "--endpoint", v.slug(), "--grant", v.grantId(), "--client", client)) {
      args.add(a);
    }
    return e;
  }

  static ObjectNode urlEntry(String urlKey, EntryValues v, boolean withToken) {
    ObjectNode e = JSON.createObjectNode();
    e.put(urlKey, v.url());
    if (withToken) {
      e.putObject("headers").put("Authorization", "Bearer " + v.token());
    }
    return e;
  }

  static boolean exists(Path p) {
    return p != null && Files.exists(p);
  }

  static void addIfExists(List<String> evidence, Path p, ClientEnvironment env) {
    if (exists(p)) {
      evidence.add(env.display(p));
    }
  }

  // ── Claude Desktop ──────────────────────────────────────────────────────────────────────────────────────────

  /** A {@code .mcpb} extension per endpoint; the fallback is a stdio entry in {@code claude_desktop_config.json}. */
  public static final class ClaudeDesktop extends ClientAdapter {

    @Override
    public ClientKind kind() {
      return ClientKind.CLAUDE_DESKTOP;
    }

    @Override
    public String displayName() {
      return "Claude Desktop";
    }

    @Override
    public List<LinkMethod> methods() {
      return List.of(LinkMethod.MCPB, LinkMethod.CONFIG_BRIDGE);
    }

    @Override
    public Path configPath(ClientEnvironment env) {
      return switch (env.os()) {
        case MAC -> env.userHome().resolve("Library/Application Support/Claude/claude_desktop_config.json");
        case WINDOWS -> msix(env).map(p -> p.resolve("LocalCache/Roaming/Claude/claude_desktop_config.json"))
            .orElse(env.appData().resolve("Claude").resolve("claude_desktop_config.json"));
        case LINUX -> env.userHome().resolve(".config/Claude/claude_desktop_config.json");
      };
    }

    /** {@code %LOCALAPPDATA%\Packages\Claude_*}: the MSIX package, whose config isn't where "Edit Config" says. */
    static Optional<Path> msix(ClientEnvironment env) {
      Path packages = env.localAppData().resolve("Packages");
      if (!Files.isDirectory(packages)) {
        return Optional.empty();
      }
      try (DirectoryStream<Path> s = Files.newDirectoryStream(packages, "Claude_*")) {
        for (Path p : s) {
          return Optional.of(p);
        }
      } catch (IOException ignored) {
        // Unreadable: not found.
      }
      return Optional.empty();
    }

    @Override
    public Detection detect(ClientEnvironment env) {
      List<String> evidence = new ArrayList<>();
      switch (env.os()) {
        case MAC -> {
          addIfExists(evidence, Path.of("/Applications/Claude.app"), env);
          addIfExists(evidence, env.userHome().resolve("Applications/Claude.app"), env);
          addIfExists(evidence, env.userHome().resolve("Library/Application Support/Claude"), env);
        }
        case WINDOWS -> {
          msix(env).ifPresent(p -> evidence.add(p.toString()));
          addIfExists(evidence, env.localAppData().resolve("AnthropicClaude"), env);
          addIfExists(evidence, env.appData().resolve("Claude"), env);
        }
        case LINUX -> addIfExists(evidence, env.userHome().resolve(".config/Claude"), env);
      }
      if (env.sandboxed()) {
        evidence.removeIf(e -> e.startsWith("/Applications"));
      }
      return new Detection(!evidence.isEmpty(), configPath(env), List.copyOf(evidence), null);
    }

    @Override
    public Entry entry(ClientEnvironment env, LinkMethod method, String name, EntryValues v) {
      if (method != LinkMethod.CONFIG_BRIDGE) {
        throw new IllegalArgumentException("Claude Desktop's " + method + " isn't a config entry");
      }
      return new Entry("mcpServers", name, bridgeEntry(env, v, "claude-desktop", false), null);
    }
  }

  // ── Codex ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code [mcp_servers.<name>]} in {@code ~/.codex/config.toml} with {@code http_headers_helper}. */
  public static final class Codex extends ClientAdapter {

    /** The first Codex that runs {@code http_headers_helper} (report 03 §1). */
    public static final String HELPER_SINCE = "0.148.0";
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    @Override
    public ClientKind kind() {
      return ClientKind.CODEX;
    }

    @Override
    public String displayName() {
      return "Codex";
    }

    @Override
    public List<LinkMethod> methods() {
      return List.of(LinkMethod.HELPER);
    }

    @Override
    public Path configPath(ClientEnvironment env) {
      return env.codexHome().resolve("config.toml");
    }

    @Override
    public Detection detect(ClientEnvironment env) {
      List<String> evidence = new ArrayList<>();
      addIfExists(evidence, env.codexHome(), env);
      String version = null;
      if (!env.sandboxed()) {
        for (Path bin : binaries(env)) {
          if (Files.isExecutable(bin)) {
            evidence.add(env.display(bin));
            version = version(bin).orElse(null);
            break;
          }
        }
      }
      return new Detection(!evidence.isEmpty(), configPath(env), List.copyOf(evidence), version);
    }

    private static List<Path> binaries(ClientEnvironment env) {
      List<Path> out = new ArrayList<>();
      if (env.os() == Os.WINDOWS) {
        env.variable("APPDATA").ifPresent(a -> out.add(Path.of(a, "npm", "codex.cmd")));
        return out;
      }
      out.add(Path.of("/opt/homebrew/bin/codex"));
      out.add(Path.of("/usr/local/bin/codex"));
      out.add(env.userHome().resolve(".local/bin/codex"));
      out.add(env.userHome().resolve(".npm-global/bin/codex"));
      out.add(Path.of("/usr/bin/codex"));
      return out;
    }

    /** {@code codex --version} without a shell, 3 s at most; the binary is one of the fixed places above. */
    static Optional<String> version(Path bin) {
      try {
        Process p = new ProcessBuilder(bin.toString(), "--version").redirectErrorStream(true).start();
        p.getOutputStream().close();
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
          p.destroyForcibly();
          return Optional.empty();
        }
        String out = new String(p.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
        Matcher m = VERSION.matcher(out);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
      } catch (IOException e) {
        return Optional.empty();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    }

    /** Whether {@code version} ignores the helper (and so would connect without a token). */
    public static boolean tooOld(String version) {
      if (version == null) {
        return false;
      }
      Matcher a = VERSION.matcher(version);
      Matcher b = VERSION.matcher(HELPER_SINCE);
      if (!a.find() || !b.find()) {
        return false;
      }
      for (int i = 1; i <= 3; i++) {
        int x = Integer.parseInt(a.group(i));
        int y = Integer.parseInt(b.group(i));
        if (x != y) {
          return x < y;
        }
      }
      return false;
    }

    @Override
    public Entry entry(ClientEnvironment env, LinkMethod method, String name, EntryValues v) {
      String helper = env.helperCommand("headers", "--endpoint", v.slug(), "--grant", v.grantId());
      String helperValue = env.os() == Os.WINDOWS ? TomlBlockEditor.literalOrBasic(helper)
          : TomlBlockEditor.basic(helper);
      String block = TomlBlockEditor.startMarker(name) + "\n"
          + "[mcp_servers." + name + "]\n"
          + "url = " + TomlBlockEditor.basic(v.url()) + "\n"
          + "http_headers_helper = " + helperValue + "\n"
          + "default_tools_approval_mode = " + TomlBlockEditor.basic(v.approvalMode()) + "\n"
          + "startup_timeout_sec = 20\n"
          + "tool_timeout_sec = " + v.toolTimeoutSec() + "\n"
          + TomlBlockEditor.endMarker(name) + "\n";
      return new Entry("mcp_servers", name, null, block);
    }

    /** How long Codex waits for the helper; our bridge answers well before ({@link Duration}). */
    public static final Duration HELPER_TIMEOUT = Duration.ofSeconds(10);
  }

  // ── Cursor ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code ~/.cursor/mcp.json}: a token in {@code headers}, OAuth through the browser, or the bridge. */
  public static final class Cursor extends ClientAdapter {

    @Override
    public ClientKind kind() {
      return ClientKind.CURSOR;
    }

    @Override
    public String displayName() {
      return "Cursor";
    }

    @Override
    public List<LinkMethod> methods() {
      return List.of(LinkMethod.TOKEN, LinkMethod.OAUTH, LinkMethod.CONFIG_BRIDGE);
    }

    @Override
    public Path configPath(ClientEnvironment env) {
      return env.userHome().resolve(".cursor").resolve("mcp.json");
    }

    @Override
    public Detection detect(ClientEnvironment env) {
      List<String> evidence = new ArrayList<>();
      addIfExists(evidence, env.userHome().resolve(".cursor"), env);
      if (!env.sandboxed()) {
        switch (env.os()) {
          case MAC -> addIfExists(evidence, Path.of("/Applications/Cursor.app"), env);
          case WINDOWS -> addIfExists(evidence, env.localAppData().resolve("Programs").resolve("cursor"), env);
          case LINUX -> addIfExists(evidence, env.userHome().resolve(".local/share/cursor"), env);
        }
      }
      return new Detection(!evidence.isEmpty(), configPath(env), List.copyOf(evidence), null);
    }

    @Override
    public Entry entry(ClientEnvironment env, LinkMethod method, String name, EntryValues v) {
      ObjectNode e = switch (method) {
        case TOKEN -> urlEntry("url", v, true);
        case OAUTH -> urlEntry("url", v, false);
        case CONFIG_BRIDGE -> bridgeEntry(env, v, "cursor", true);
        default -> throw new IllegalArgumentException("Cursor can't be connected with " + method);
      };
      return new Entry("mcpServers", name, e, null);
    }
  }

  // ── Antigravity ─────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code ~/.gemini/config/mcp_config.json} (or the older {@code ~/.gemini/antigravity/}, when only it exists). */
  public static final class Antigravity extends ClientAdapter {

    @Override
    public ClientKind kind() {
      return ClientKind.ANTIGRAVITY;
    }

    @Override
    public String displayName() {
      return "Antigravity";
    }

    @Override
    public List<LinkMethod> methods() {
      return List.of(LinkMethod.TOKEN, LinkMethod.OAUTH, LinkMethod.CONFIG_BRIDGE);
    }

    @Override
    public Path configPath(ClientEnvironment env) {
      Path gemini = env.userHome().resolve(".gemini");
      Path current = gemini.resolve("config").resolve("mcp_config.json");
      Path legacy = gemini.resolve("antigravity").resolve("mcp_config.json");
      return !Files.exists(current) && Files.exists(legacy) ? legacy : current;
    }

    @Override
    public Detection detect(ClientEnvironment env) {
      List<String> evidence = new ArrayList<>();
      Path gemini = env.userHome().resolve(".gemini");
      addIfExists(evidence, gemini.resolve("config"), env);
      addIfExists(evidence, gemini.resolve("antigravity"), env);
      if (!env.sandboxed()) {
        switch (env.os()) {
          case MAC -> addIfExists(evidence, Path.of("/Applications/Antigravity.app"), env);
          case WINDOWS -> addIfExists(evidence, env.localAppData().resolve("Programs").resolve("Antigravity"), env);
          case LINUX -> { }
        }
      }
      return new Detection(!evidence.isEmpty(), configPath(env), List.copyOf(evidence), null);
    }

    @Override
    public Entry entry(ClientEnvironment env, LinkMethod method, String name, EntryValues v) {
      ObjectNode e = switch (method) {
        case TOKEN -> urlEntry("serverUrl", v, true);
        case OAUTH -> urlEntry("serverUrl", v, false);
        case CONFIG_BRIDGE -> bridgeEntry(env, v, "antigravity", false);
        default -> throw new IllegalArgumentException("Antigravity can't be connected with " + method);
      };
      return new Entry("mcpServers", name, e, null);
    }
  }
}
