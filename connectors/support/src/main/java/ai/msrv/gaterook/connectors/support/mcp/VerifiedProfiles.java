package ai.msrv.gaterook.connectors.support.mcp;

import ai.msrv.gaterook.connectors.support.Glob;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * GateRook's verified profiles of vendors' own MCP servers (ARCHITECTURE.md §7.5), bundled with the build in
 * {@code verified-profiles.json} beside this class: for a server at a vendor's address, the tools that must not
 * start as their annotations say, and the vendor's read-only switches its setup URL sets.
 *
 * <p>A profile matches a server by its settings, however it was added: an HTTPS host (exact, or one DNS label under a
 * suffix) and path, or for a command the npm package and the subcommand it runs. It only makes the server's defaults
 * stricter. A tool it names is a mutation for GateRook whatever the server declares, and starts in Approval (a tool
 * that runs code or arbitrary API calls, or a write the server declares read-only) or Off (a tool that hands the
 * request to another agent). Tools it doesn't name keep the rules of their annotations. A profile never makes a tool
 * Read only: GateRook doesn't infer read-only behavior from a tool's name (ARCHITECTURE.md §5.1).
 *
 * <p>Pins are the vendor's switches the setup sets: query parameters of its URL, with a fixed value
 * ({@code read_only=true}) or the person's own ({@code project_ref}), and headers with a fixed value
 * ({@code X-MCP-Readonly: true}, {@link FixedHeaders}). The review says how each stands in the settings the person gave
 * and warns when one is missing; GateRook never adds one behind the person's back.
 */
public final class VerifiedProfiles {

  /** The bundled profiles' resource, beside this class (registered for the native image). */
  public static final String RESOURCE = "ai/msrv/gaterook/connectors/support/mcp/verified-profiles.json";

  /** Where a tool a profile names starts on an endpoint. */
  public enum Start { APPROVAL, OFF }

  /**
   * Why a profile names a tool: it runs code ({@code code}) or arbitrary API calls ({@code passthrough}), changes
   * data, or may while its name or annotations say otherwise ({@code write}), returns credentials or secret values
   * such as a kubeconfig, a decrypted environment variable or a link that grants access ({@code credentials}), or
   * hands the request to another agent ({@code delegating}).
   */
  public enum Kind { CODE, PASSTHROUGH, WRITE, CREDENTIALS, DELEGATING }

  /**
   * One tool a profile names, by the name the server lists it under.
   *
   * @param reason one sentence for the review and the approval card ("It runs JavaScript against the Cloudflare API,
   *               so GateRook can't tell a read from a change.")
   */
  public record ToolRule(String tool, Start start, Kind kind, String reason) {}

  /** Where a pin goes: a query parameter of the URL, or a header with a fixed value. */
  public enum In { QUERY, HEADER }

  /**
   * A switch the setup sets.
   *
   * @param name    the query parameter ({@code read_only}) or header ({@code X-MCP-Readonly})
   * @param setTo   the value the profile sets ({@code true}), or {@code null} when it is the person's own
   *                ({@code project_ref}; query parameters only)
   * @param purpose what it does, for the review
   * @param missing what the review warns when the settings lack it
   */
  public record Pin(In in, String name, String setTo, String purpose, String missing) {}

  /** A part of the setup URL or command the person fills in ({@code {project_ref}}), with what to put there. */
  public record Placeholder(String name, String description) {}

  /**
   * How a profile recognizes a server.
   *
   * @param hosts        exact HTTPS hosts ({@code mcp.supabase.com})
   * @param hostSuffix   the parent of a host one DNS label below it ({@code mcp.digitalocean.com} matches
   *                     {@code apps.mcp.digitalocean.com}), or {@code null}
   * @param paths        path patterns ({@link Glob#path}); a trailing slash doesn't count
   * @param excludePaths path patterns of the vendor's other servers on the same host ({@code /docs/mcp})
   * @param npm          the npm package a command runs ({@code firebase-tools}), or {@code null}
   * @param bin          the package's executable when it is run directly ({@code firebase}), or {@code null}
   * @param args         the arguments that follow the package or executable ({@code mcp})
   */
  public record Match(List<String> hosts, String hostSuffix, List<String> paths, List<String> excludePaths,
      String npm, String bin, List<String> args) {

    public Match {
      hosts = hosts == null ? List.of() : List.copyOf(hosts);
      paths = paths == null ? List.of() : List.copyOf(paths);
      excludePaths = excludePaths == null ? List.of() : List.copyOf(excludePaths);
      args = args == null ? List.of() : List.copyOf(args);
    }

    boolean http() {
      return !hosts.isEmpty() || hostSuffix != null;
    }
  }

  /**
   * One vendor's server.
   *
   * @param id           kebab-case, stable ({@code supabase})
   * @param registryName the vendor's own entry in the Official MCP Registry, or {@code null} when it has none
   * @param checked      the day GateRook last checked the server's tools, sign-in and switches
   * @param url          the setup URL over HTTP, with its pins and {@code {placeholders}}; {@code null} for a command
   * @param headers      the headers with a fixed value the setup sends over HTTP, its header pins among them
   * @param command      the setup command over stdio; empty over HTTP
   * @param signIn       how it signs in, in one sentence for the card and the review
   * @param notes        what else to know before connecting it
   */
  public record Profile(String id, String name, String publisher, String category, String description,
      String documentation, String registryName, LocalDate checked, String url, List<FixedHeaders.Header> headers,
      List<String> command, List<Placeholder> placeholders, List<Pin> pins, String signIn, List<String> notes,
      List<ToolRule> tools, Match match) {

    public Profile {
      headers = headers == null ? List.of() : List.copyOf(headers);
      command = command == null ? List.of() : List.copyOf(command);
      placeholders = placeholders == null ? List.of() : List.copyOf(placeholders);
      pins = pins == null ? List.of() : List.copyOf(pins);
      notes = notes == null ? List.of() : List.copyOf(notes);
      tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** {@code http} or {@code stdio}. */
    public String transport() {
      return url != null ? "http" : "stdio";
    }

    /** What the profile says of the tool the server lists as {@code original}, or empty. */
    public Optional<ToolRule> rule(String original) {
      for (ToolRule t : tools) {
        if (t.tool().equals(original)) {
          return Optional.of(t);
        }
      }
      return Optional.empty();
    }

    /** Whether the settings of a server ({@code transport} with {@code url} or {@code command}) are this vendor's. */
    public boolean matches(JsonNode config) {
      if (config == null || !config.isObject()) {
        return false;
      }
      String transport = config.path("transport").asString("stdio");
      if (match.http()) {
        return "http".equals(transport) && matchesUrl(config.path("url").asString(null));
      }
      if ("http".equals(transport)) {
        return false;
      }
      List<String> argv = new ArrayList<>();
      for (JsonNode a : config.path("command")) {
        if (a.isString()) {
          argv.add(a.asString());
        }
      }
      return matchesCommand(argv);
    }

    boolean matchesUrl(String text) {
      URI u = https(text);
      if (u == null) {
        return false;
      }
      String host = u.getHost().toLowerCase(Locale.ROOT);
      String suffix = match.hostSuffix();
      boolean hostMatches = match.hosts().contains(host) || suffix != null && host.endsWith("." + suffix)
          && host.substring(0, host.length() - suffix.length() - 1).matches(LABEL);
      if (!hostMatches) {
        return false;
      }
      String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
      if (path.length() > 1 && path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      String requested = path;
      return match.paths().stream().anyMatch(p -> Glob.path(p).matches(requested))
          && match.excludePaths().stream().noneMatch(p -> Glob.path(p).matches(requested));
    }

    boolean matchesCommand(List<String> argv) {
      if (argv.isEmpty() || match.npm() == null && match.bin() == null) {
        return false;
      }
      String exe = executable(argv.get(0));
      int next;
      if (match.bin() != null && exe.equals(match.bin())) {
        next = 1;
      } else if (match.npm() != null && RUNNERS.contains(exe)) {
        int i = 1;
        if ((exe.equals("pnpm") || exe.equals("yarn")) && (argv.size() < 2 || !argv.get(1).equals("dlx"))) {
          return false;
        }
        if (exe.equals("pnpm") || exe.equals("yarn")) {
          i = 2;
        }
        while (i < argv.size() && argv.get(i).startsWith("-")) {
          i++;
        }
        if (i >= argv.size() || !match.npm().equals(packageName(argv.get(i)))) {
          return false;
        }
        next = i + 1;
      } else {
        return false;
      }
      List<String> rest = argv.subList(next, argv.size());
      return rest.size() >= match.args().size() && rest.subList(0, match.args().size()).equals(match.args());
    }
  }

  /**
   * How a pin stands in a server's settings.
   *
   * @param actual the query parameter's or fixed header's value there, or {@code null} without it
   * @param set    whether the settings set it as the profile does (its value, or any value of the person's that isn't
   *               a {@code {placeholder}})
   */
  public record PinState(Pin pin, String actual, boolean set) {}

  private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
  private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
  private static final Pattern TOOL = Pattern.compile("^[A-Za-z0-9_.\\-/]{1,128}$");
  /** A fixed header's name and value as the server's settings take them ({@code config.schema.json}). */
  private static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
  private static final Pattern HEADER_TEXT = Pattern.compile("^[\\x20-\\x7E]{0,256}$");
  private static final Set<String> RUNNERS = Set.of("npx", "bunx", "pnpx", "pnpm", "yarn");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final List<Profile> ALL = load();

  private VerifiedProfiles() {}

  /** Every bundled profile, in the order the cards show them. */
  public static List<Profile> all() {
    return ALL;
  }

  /** The profile {@code id}, or empty. */
  public static Optional<Profile> byId(String id) {
    return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
  }

  /** The profile whose server these settings ({@code transport}, {@code url}, {@code command}) name, or empty. */
  public static Optional<Profile> of(JsonNode config) {
    for (Profile p : ALL) {
      if (p.matches(config)) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }

  /** What the matching profile says of the tool the server lists as {@code original}, or empty. */
  public static Optional<ToolRule> rule(JsonNode config, String original) {
    return of(config).flatMap(p -> p.rule(original));
  }

  /**
   * How each of the profile's pins stands in a server's settings ({@code url}, {@code fixedHeaders}), in the profile's
   * order. Header names don't depend on case; values do.
   */
  public static List<PinState> pins(Profile profile, JsonNode config) {
    Map<String, String> query = query(config == null ? null : config.path("url").asString(null));
    Map<String, String> headers = new LinkedHashMap<>();
    if (config != null) {
      for (JsonNode h : config.path("fixedHeaders")) {
        headers.putIfAbsent(h.path("name").asString("").toLowerCase(Locale.ROOT), h.path("text").asString(""));
      }
    }
    List<PinState> out = new ArrayList<>();
    for (Pin pin : profile.pins()) {
      String actual = pin.in() == In.QUERY ? query.get(pin.name()) : headers.get(pin.name().toLowerCase(Locale.ROOT));
      boolean set = actual != null && (pin.setTo() != null ? pin.setTo().equals(actual)
          : !actual.isBlank() && !PLACEHOLDER.matcher(actual).find());
      out.add(new PinState(pin, actual, set));
    }
    return out;
  }

  // ── Loading ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static List<Profile> load() {
    try (InputStream in = VerifiedProfiles.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the classpath");
      }
      return parse(MAPPER.readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException("Can't read " + RESOURCE, e);
    } catch (JacksonException e) {
      throw new IllegalStateException(RESOURCE + " is not valid JSON: " + e.getOriginalMessage(), e);
    }
  }

  /**
   * The profiles of a {@code verified-profiles.json} tree, checked: a bad profile is a packaging bug that must stop
   * the build's tests, not a server whose defaults quietly stay loose.
   *
   * @throws IllegalArgumentException naming the profile and what is wrong with it
   */
  static List<Profile> parse(JsonNode root) {
    if (root == null || root.path("schema").asInt(0) != 1 || !root.path("profiles").isArray()) {
      throw new IllegalArgumentException("verified profiles: expected {\"schema\": 1, \"profiles\": [...]}");
    }
    List<Profile> out = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (JsonNode p : root.path("profiles")) {
      Profile profile = profile(p);
      if (!ids.add(profile.id())) {
        throw new IllegalArgumentException("verified profiles: " + profile.id() + " is listed twice");
      }
      out.add(profile);
    }
    for (Profile p : out) {
      for (Profile q : out) {
        if (p != q && q.matches(sampleConfig(p))) {
          throw new IllegalArgumentException("verified profiles: " + q.id() + " also matches the setup of " + p.id());
        }
      }
    }
    return List.copyOf(out);
  }

  /**
   * The settings a profile's setup gives once the person filled in its placeholders, with {@code sample} in each: what
   * the profile must recognize, and no other profile may.
   */
  static JsonNode sampleConfig(Profile p) {
    ObjectNode node = (ObjectNode) setupConfig(p);
    if (p.url() != null) {
      node.put("url", filled(p.url()));
    }
    return node;
  }

  /** The settings a profile's setup gives, as the Add MCP server form would send them. */
  public static JsonNode setupConfig(Profile p) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("transport", p.transport());
    if (p.url() != null) {
      node.put("url", p.url());
      if (!p.headers().isEmpty()) {
        var fixed = node.putArray("fixedHeaders");
        p.headers().forEach(h -> fixed.addObject().put("name", h.name()).put("text", h.text()));
      }
    } else {
      var command = node.putArray("command");
      p.command().forEach(command::add);
    }
    return node;
  }

  private static Profile profile(JsonNode p) {
    String id = text(p, "id", "?");
    if (!ID.matcher(id).matches()) {
      throw new IllegalArgumentException("verified profiles: bad id " + id);
    }
    String name = text(p, "name", id);
    String publisher = text(p, "publisher", id);
    String category = text(p, "category", id);
    String description = text(p, "description", id);
    String documentation = text(p, "documentation", id);
    if (!documentation.startsWith("https://")) {
      throw new IllegalArgumentException("verified profiles: " + id + " needs an https:// documentation link");
    }
    String registryName = optional(p, "registryName");
    LocalDate checked;
    try {
      checked = LocalDate.parse(text(p, "checked", id));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("verified profiles: " + id + " has a bad checked date", e);
    }
    String url = optional(p, "url");
    List<String> command = strings(p.path("command"));
    if ((url == null) == command.isEmpty()) {
      throw new IllegalArgumentException("verified profiles: " + id + " needs either a url or a command");
    }
    List<Placeholder> placeholders = new ArrayList<>();
    for (JsonNode h : p.path("placeholders")) {
      placeholders.add(new Placeholder(text(h, "name", id), text(h, "description", id)));
    }
    Set<String> declared = new HashSet<>();
    placeholders.forEach(h -> declared.add(h.name()));
    Set<String> used = new HashSet<>(placeholders(url == null ? String.join(" ", command) : url));
    if (!declared.equals(used)) {
      throw new IllegalArgumentException("verified profiles: " + id + " describes placeholders " + declared
          + " but its setup uses " + used);
    }
    List<FixedHeaders.Header> headers = new ArrayList<>();
    for (JsonNode h : p.path("headers")) {
      String headerName = text(h, "name", id);
      String headerText = h.path("text").isString() ? h.path("text").asString() : "";
      if (!HEADER.matcher(headerName).matches() || !HEADER_TEXT.matcher(headerText).matches()) {
        throw new IllegalArgumentException("verified profiles: " + id + " has a bad header " + headerName);
      }
      headers.add(new FixedHeaders.Header(headerName, headerText));
    }
    String headerProblem = FixedHeaders.problem(headers, List.of());
    if (url == null && !headers.isEmpty() || headerProblem != null) {
      throw new IllegalArgumentException("verified profiles: " + id + "'s headers: "
          + (headerProblem == null ? "a command has no headers" : headerProblem));
    }
    List<Pin> pins = new ArrayList<>();
    Map<String, String> setupQuery = url == null ? Map.of() : query(url);
    for (JsonNode n : p.path("pins")) {
      boolean header = n.path("header").isString();
      Pin pin = new Pin(header ? In.HEADER : In.QUERY, text(n, header ? "header" : "parameter", id),
          optional(n, "setTo"), text(n, "purpose", id), text(n, "missing", id));
      String inSetup = header ? headers.stream().filter(h -> h.name().equalsIgnoreCase(pin.name())).findFirst()
          .map(FixedHeaders.Header::text).orElse(null) : setupQuery.get(pin.name());
      if (url == null || inSetup == null || pin.setTo() != null && !pin.setTo().equals(inSetup)
          || pin.setTo() == null && (header || !PLACEHOLDER.matcher(inSetup).matches())) {
        throw new IllegalArgumentException("verified profiles: " + id + " pins " + pin.name()
            + " but its setup doesn't set it that way");
      }
      pins.add(pin);
    }
    List<ToolRule> tools = new ArrayList<>();
    Set<String> toolNames = new HashSet<>();
    for (JsonNode t : p.path("tools")) {
      String tool = text(t, "tool", id);
      if (!TOOL.matcher(tool).matches() || !toolNames.add(tool)) {
        throw new IllegalArgumentException("verified profiles: " + id + " names the tool " + tool + " badly or twice");
      }
      Start start;
      Kind kind;
      try {
        start = Start.valueOf(text(t, "start", id).toUpperCase(Locale.ROOT));
        kind = Kind.valueOf(text(t, "kind", id).toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("verified profiles: " + id + " gives " + tool + " a bad start or kind", e);
      }
      if (kind == Kind.DELEGATING && start != Start.OFF) {
        throw new IllegalArgumentException("verified profiles: " + id + " must start the delegating " + tool + " Off");
      }
      tools.add(new ToolRule(tool, start, kind, text(t, "reason", id)));
    }
    JsonNode m = p.path("match");
    Match match = new Match(strings(m.path("hosts")).stream().map(VerifiedProfiles::lower).toList(),
        lower(optional(m, "hostSuffix")), strings(m.path("paths")), strings(m.path("excludePaths")),
        optional(m, "npm"), optional(m, "bin"), strings(m.path("args")));
    if (url != null && (!match.http() || match.paths().isEmpty())
        || url == null && (match.http() || match.npm() == null && match.bin() == null)) {
      throw new IllegalArgumentException("verified profiles: " + id + " has a match that doesn't fit its setup");
    }
    Profile profile = new Profile(id, name, publisher, category, description, documentation, registryName, checked,
        url, headers, command, placeholders, pins, text(p, "signIn", id), strings(p.path("notes")), tools, match);
    if (!profile.matches(sampleConfig(profile))) {
      throw new IllegalArgumentException("verified profiles: " + id + "'s match doesn't recognize its own setup");
    }
    return profile;
  }

  /** The setup URL with sample values in its placeholders, for checking that the match recognizes it. */
  private static String filled(String url) {
    Matcher m = PLACEHOLDER.matcher(url);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, "sample");
    }
    m.appendTail(out);
    return out.toString();
  }

  private static List<String> placeholders(String text) {
    List<String> out = new ArrayList<>();
    Matcher m = PLACEHOLDER.matcher(text);
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  private static String text(JsonNode node, String field, String profile) {
    JsonNode v = node.path(field);
    if (!v.isString() || v.asString().isBlank()) {
      throw new IllegalArgumentException("verified profiles: " + profile + " needs " + field);
    }
    return v.asString();
  }

  private static String optional(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isString() && !v.asString().isBlank() ? v.asString() : null;
  }

  private static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.ROOT);
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode a : array) {
      if (a.isString()) {
        out.add(a.asString());
      }
    }
    return out;
  }

  // ── URLs and commands ───────────────────────────────────────────────────────────────────────────────────────

  /** An HTTPS URL on the default port without user info, or {@code null}: tokens go to the vendor's host only. */
  private static URI https(String text) {
    if (text == null) {
      return null;
    }
    try {
      URI u = new URI(text);
      if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null
          || u.getPort() != -1 && u.getPort() != 443) {
        return null;
      }
      return u;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /** The query parameters of a URL, decoded; the first of a repeated name counts. Placeholders stay as they are. */
  static Map<String, String> query(String url) {
    Map<String, String> out = new LinkedHashMap<>();
    if (url == null) {
      return out;
    }
    int q = url.indexOf('?');
    if (q < 0) {
      return out;
    }
    int hash = url.indexOf('#', q);
    String query = url.substring(q + 1, hash < 0 ? url.length() : hash);
    for (String part : query.split("&")) {
      if (part.isEmpty()) {
        continue;
      }
      int eq = part.indexOf('=');
      String name = decode(eq < 0 ? part : part.substring(0, eq));
      String value = eq < 0 ? "" : decode(part.substring(eq + 1));
      out.putIfAbsent(name, value);
    }
    return out;
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return s;
    }
  }

  /** {@code npx}, from {@code /usr/local/bin/npx} or {@code C:\…\npx.cmd}. */
  private static String executable(String path) {
    String base = path.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    for (String ext : List.of(".cmd", ".exe", ".bat", ".ps1")) {
      if (base.endsWith(ext)) {
        return base.substring(0, base.length() - ext.length());
      }
    }
    return base;
  }

  /** {@code firebase-tools} from {@code firebase-tools@14.24.1}, {@code @scope/name} from {@code @scope/name@1}. */
  private static String packageName(String spec) {
    int at = spec.indexOf('@', spec.startsWith("@") ? 1 : 0);
    return at < 0 ? spec : spec.substring(0, at);
  }
}
