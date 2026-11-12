package ai.msrv.gaterook.connectors.snowflake;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.snowflake.SnowflakeApi.Answer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Help for the Snowflake form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}).
 *
 * <p>On this computer: the connections of the Snowflake CLI and drivers ({@code connections.toml}, and
 * {@code [connections.*]} of {@code config.toml}), the default connection's account filled in, and the user, warehouse,
 * role, database and schema of the connection whose account the form names. Names only: a password, token or key in
 * those files is never read into a suggestion, and a key file is only named.
 *
 * <p>Signed in as Test connection signs in, in a session without the connection's warehouse, database and schema: the
 * user's defaults filled in where the form leaves them empty ({@code CURRENT_WAREHOUSE()}, {@code CURRENT_ROLE()},
 * {@code CURRENT_DATABASE()}, {@code CURRENT_SCHEMA()}), the roles the user may use, the warehouses and databases the
 * role sees, the schemas of the database, and {@code DATABASE.SCHEMA.*} for the table list. SHOW and context functions
 * only: nothing needs a running warehouse.
 */
final class SnowflakeSuggestions {

  /** What the session's defaults are: no warehouse is needed for context functions. */
  static final String DEFAULTS_SQL = "SELECT CURRENT_USER(), CURRENT_ROLE(), CURRENT_WAREHOUSE(), CURRENT_DATABASE(), "
      + "CURRENT_SCHEMA(), CURRENT_AVAILABLE_ROLES()";
  /** Snowflake's codes for a role the user may not use in the session it asked for. */
  private static final Set<String> ROLE_REFUSED = Set.of("390189", "390186");
  private static final Pattern ACCOUNT = Pattern.compile(
      "[A-Za-z][A-Za-z0-9]{0,254}-[A-Za-z0-9_]{1,255}|[A-Za-z0-9]{1,64}(\\.[A-Za-z0-9-]{1,64}){0,2}");
  private static final Pattern USER = Pattern.compile("[A-Za-z0-9_.@$+-]{1,255}");
  /** A part of a table pattern: what {@code tables} accepts without quotes. */
  private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_$]{1,255}");
  private static final long MAX_FILE = 1 << 20;
  private static final TomlMapper TOML = TomlMapper.builder().build();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SnowflakeSuggestions() {}

  /** Where this computer's files are and what its environment says: the tests' own, or this process's. */
  record Machine(Function<String, String> env, Path home) {

    static Machine system() {
      return new Machine(System::getenv, Path.of(System.getProperty("user.home")));
    }
  }

  /** One connection of the Snowflake CLI's files, as far as a form can use it. */
  record Profile(String name, String file, String account, boolean privateLink, String user, String warehouse,
      String role, String database, String schema, String authenticator, String keyFile) {}

  static Suggestions suggest(SnowflakeApi api, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Scope scope, Machine machine) {
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.LOCAL) {
      local(cfg, machine, out);
    } else {
      service(api, cfg, secrets, ctx, out);
    }
    return out.build();
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  static void local(ConnectionConfig cfg, Machine machine, Suggestions.Builder out) {
    Path dir = configDir(machine);
    Map<String, Profile> profiles = new LinkedHashMap<>();
    String defaultName = present(machine.env().apply("SNOWFLAKE_DEFAULT_CONNECTION_NAME"));
    JsonNode config = read(dir.resolve("config.toml"));
    if (defaultName == null && config != null) {
      defaultName = present(config.path("default_connection_name").asString(null));
    }
    JsonNode connections = read(dir.resolve("connections.toml"));
    if (connections != null) {
      add(connections, shown(machine, dir.resolve("connections.toml")), profiles);
    }
    if (config != null) {
      add(config.path("connections"), shown(machine, dir.resolve("config.toml")), profiles);
    }
    if (profiles.isEmpty()) {
      return;
    }
    String def = defaultName == null ? "default" : defaultName;
    Profile preferred = profiles.get(def);
    if (preferred == null && profiles.size() == 1) {
      preferred = profiles.values().iterator().next();
    }
    String typed = present(cfg.string("/account"));
    Profile chosen = typed == null ? preferred : profiles.values().stream()
        .filter(p -> p.account() != null && same(p.account(), typed)).findFirst().orElse(null);

    List<Option> accounts = new ArrayList<>();
    List<Option> users = new ArrayList<>();
    for (Profile p : profiles.values()) {
      String detail = "connection " + p.name() + (p.name().equals(def) ? " (default)" : "");
      if (p.account() != null && accounts.stream().noneMatch(o -> same(o.value(), p.account()))) {
        accounts.add(new Option(p.account(), null, detail));
      }
      if (p.user() != null && users.stream().noneMatch(o -> o.value().equalsIgnoreCase(p.user()))) {
        users.add(new Option(p.user(), null, detail));
      }
    }
    String source = profiles.values().iterator().next().file();
    out.value("/account", source, preferred == null ? null : preferred.account(), accounts);
    if (chosen == null) {
      out.options("/user", source, users);
      out.note("Found " + count(profiles.size(), "Snowflake connection") + " in " + source + ".");
      return;
    }
    out.value("/user", chosen.file(), chosen.user(), users);
    one(out, "/warehouse", chosen.file(), chosen.warehouse());
    one(out, "/role", chosen.file(), chosen.role());
    one(out, "/database", chosen.file(), chosen.database());
    one(out, "/schema", chosen.database() == null ? null : chosen.file(), chosen.schema());
    if (chosen.privateLink()) {
      out.value("/privateLink", chosen.file(), "true", List.of(Option.of("true")));
    }
    String auth = chosen.authenticator() == null ? "" : chosen.authenticator().toUpperCase(Locale.ROOT);
    switch (auth) {
      case "SNOWFLAKE_JWT" -> {
        out.value("/authentication/method", chosen.file(), "keyPair", List.of(Option.of("keyPair")));
        if (chosen.keyFile() != null) {
          out.note("Connection " + chosen.name() + " signs in with the key in " + chosen.keyFile() + ": paste that "
              + "private key under Private key.");
        }
      }
      case "PROGRAMMATIC_ACCESS_TOKEN" ->
          out.value("/authentication/method", chosen.file(), "token", List.of(Option.of("token")));
      case "" -> {
        // The drivers' default is a password, which GateRook doesn't take: the form keeps its key pair.
      }
      default -> out.note("Connection " + chosen.name() + " signs in with " + chosen.authenticator().toLowerCase(
          Locale.ROOT) + ": GateRook signs in with a key pair or a programmatic access token of the same user.");
    }
    out.note("Found " + count(profiles.size(), "Snowflake connection") + " in " + source + ": the form uses "
        + chosen.name() + ".");
  }

  private static void one(Suggestions.Builder out, String pointer, String source, String value) {
    if (source != null && value != null) {
      out.value(pointer, source, value, List.of(Option.of(value)));
    }
  }

  /**
   * Where the Snowflake CLI and drivers keep their files: {@code SNOWFLAKE_HOME}, else {@code ~/.snowflake} when it
   * exists, else the platform's configuration folder.
   */
  static Path configDir(Machine machine) {
    String home = present(machine.env().apply("SNOWFLAKE_HOME"));
    if (home != null) {
      return Path.of(home.startsWith("~") ? machine.home() + home.substring(1) : home);
    }
    Path dot = machine.home().resolve(".snowflake");
    if (Files.isDirectory(dot)) {
      return dot;
    }
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.startsWith("mac")) {
      return machine.home().resolve("Library").resolve("Application Support").resolve("snowflake");
    }
    if (os.startsWith("windows")) {
      String local = present(machine.env().apply("LOCALAPPDATA"));
      return local != null ? Path.of(local, "snowflake") : machine.home().resolve("AppData").resolve("Local")
          .resolve("snowflake");
    }
    String xdg = present(machine.env().apply("XDG_CONFIG_HOME"));
    return (xdg != null ? Path.of(xdg) : machine.home().resolve(".config")).resolve("snowflake");
  }

  /** The connections of one file: each table with an account (or a host to take it from). */
  private static void add(JsonNode tables, String file, Map<String, Profile> out) {
    if (tables == null || !tables.isObject()) {
      return;
    }
    for (Map.Entry<String, JsonNode> e : tables.properties()) {
      JsonNode t = e.getValue();
      if (!t.isObject() || out.containsKey(e.getKey())) {
        continue;
      }
      String raw = text(t, "account");
      if (raw == null) {
        raw = text(t, "host");
      }
      String account = null;
      boolean privateLink = false;
      if (raw != null) {
        String a = raw.strip();
        a = a.replaceFirst("(?i)^https?://", "").replaceFirst("/+$", "");
        a = a.replaceFirst("(?i)\\.snowflakecomputing\\.(com|cn)$", "");
        if (a.toLowerCase(Locale.ROOT).endsWith(".privatelink")) {
          a = a.substring(0, a.length() - ".privatelink".length());
          privateLink = true;
        }
        account = ACCOUNT.matcher(a).matches() ? a : null;
      }
      String user = text(t, "user");
      if (user == null) {
        user = text(t, "username");
      }
      String keyFile = text(t, "private_key_file");
      if (keyFile == null) {
        keyFile = text(t, "private_key_path");
      }
      out.put(e.getKey(), new Profile(e.getKey(), file, account, privateLink,
          user != null && USER.matcher(user).matches() ? user : null, identifier(t, "warehouse"),
          identifier(t, "role"), identifier(t, "database"), identifier(t, "schema"), text(t, "authenticator"),
          keyFile));
    }
  }

  /** A warehouse, role, database or schema as the form takes it: as typed when it is a Snowflake name. */
  private static String identifier(JsonNode t, String key) {
    String v = text(t, key);
    return v == null || ObjectName.identifier(v) == null ? null : v.strip();
  }

  private static String text(JsonNode t, String key) {
    JsonNode v = t.get(key);
    return v != null && v.isString() ? present(v.asString()) : null;
  }

  /** A TOML file of at most a MiB as a tree, or {@code null}: missing, too large or not TOML. */
  private static JsonNode read(Path file) {
    try {
      if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE) {
        return null;
      }
      return TOML.readTree(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
      // Not TOML (JacksonException), unreadable or gone: no suggestion from it.
      return null;
    }
  }

  /** Whether two account identifiers name the same account: case, and {@code _} or {@code -}, don't matter. */
  static boolean same(String a, String b) {
    return a.strip().toLowerCase(Locale.ROOT).replace('_', '-').equals(b.strip().toLowerCase(Locale.ROOT)
        .replace('_', '-'));
  }

  /** {@code ~/.snowflake/connections.toml} rather than the home folder's full path, as people write it. */
  private static String shown(Machine machine, Path file) {
    Path home = machine.home().toAbsolutePath().normalize();
    Path p = file.toAbsolutePath().normalize();
    return p.startsWith(home) ? "~/" + home.relativize(p).toString().replace('\\', '/') : p.toString();
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  static void service(SnowflakeApi api, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Builder out) {
    SnowflakeSettings s;
    try {
      s = SnowflakeSettings.of(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    // The user's own session: a warehouse or database typed wrong doesn't keep the lists from being read.
    SnowflakeSettings session = s.withSession(null, s.role(), null, null);
    SnowflakeApi.Call c;
    Answer defaults;
    String refusedRole = null;
    try {
      c = call(cfg, session, secrets, ctx);
      try {
        defaults = api.run(c, SnowflakeApi.Statement.read(DEFAULTS_SQL, 2));
      } catch (SnowflakeApi.Failure f) {
        if (s.role() == null || !ROLE_REFUSED.contains(f.code)) {
          throw f;
        }
        refusedRole = s.role();
        session = s.withSession(null, null, null, null);
        c = call(cfg, session, secrets, ctx);
        defaults = api.run(c, SnowflakeApi.Statement.read(DEFAULTS_SQL, 2));
      }
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    JsonNode row = defaults.data().path(0);
    String user = text(row.path(0));
    String role = text(row.path(1));
    String warehouse = text(row.path(2));
    String database = text(row.path(3));
    String schema = text(row.path(4));
    List<String> roles = roles(text(row.path(5)));

    if (!roles.isEmpty()) {
      List<Option> options = roles.stream().map(r -> new Option(ObjectName.quoteIfNeeded(r), null,
          r.equals(role) && s.role() == null ? "the user's default role" : null)).toList();
      out.value("/role", "Snowflake", s.role() == null && role != null ? ObjectName.quoteIfNeeded(role) : null,
          options);
    }
    if (refusedRole != null) {
      out.note("The user " + display(user) + " may not use the role " + refusedRole + ": choose one of its "
          + count(roles.size(), "role") + ".");
    }

    List<String> lists = new ArrayList<>();
    List<Named> warehouses = show(api, c, "SHOW WAREHOUSES", out, "warehouses");
    if (warehouses != null) {
      String value = warehouse != null ? warehouse : warehouses.size() == 1 ? warehouses.getFirst().name() : null;
      offer(out, "/warehouse", warehouses, value);
      lists.add(count(warehouses.size(), "warehouse"));
      missing(s.warehouse(), warehouses, "warehouse", out);
      if (warehouses.isEmpty()) {
        out.note("The role may use no warehouse: queries over tables need one.");
      }
    }
    List<Named> databases = show(api, c, "SHOW TERSE DATABASES", out, "databases");
    if (databases != null) {
      offer(out, "/database", databases, database);
      lists.add(count(databases.size(), "database"));
      missing(s.database(), databases, "database", out);
    }
    String in = s.database() != null ? s.database() : database;
    if (in != null && (databases == null || databases.stream().anyMatch(d -> d.name().equals(in)))) {
      List<Named> schemas = show(api, c, "SHOW TERSE SCHEMAS IN DATABASE " + ObjectName.quote(in), out,
          "schemas of " + ObjectName.quoteIfNeeded(in));
      if (schemas != null) {
        schemas.removeIf(n -> n.name().equals("INFORMATION_SCHEMA"));
        // The user's default schema only where it belongs: in the database of the user's default namespace.
        boolean ownSchema = in.equals(database) && schema != null && schemas.stream()
            .anyMatch(n -> n.name().equals(schema));
        offer(out, "/schema", schemas.stream().map(n -> new Named(n.name(), null)).toList(), ownSchema ? schema
            : null);
        List<Option> tables = new ArrayList<>();
        for (Named n : schemas) {
          if (PLAIN.matcher(in).matches() && PLAIN.matcher(n.name()).matches()) {
            tables.add(new Option(in + "." + n.name() + ".*", null, "every table of schema " + n.name()));
          }
        }
        out.options("/tables", "Snowflake", tables);
      }
    }
    out.note("Signed in as " + display(user) + " with role " + display(role) + (lists.isEmpty() ? "."
        : ": " + String.join(", ", lists) + " it may use."));
  }

  /** A name SHOW listed, with one short fact of it. */
  record Named(String name, String detail) {}

  /** The names as options for {@code pointer}, as a person types them, {@code value} filled in; nothing without any. */
  private static void offer(Suggestions.Builder out, String pointer, List<Named> names, String value) {
    if (names.isEmpty() && value == null) {
      return;
    }
    out.field(pointer, new Suggestions.Field(names.stream().map(n -> new Option(ObjectName.quoteIfNeeded(n.name()),
        null, n.detail())).toList(), value == null ? null : ObjectName.quoteIfNeeded(value), "Snowflake",
        names.size() <= Suggestions.MAX_OPTIONS));
  }

  /**
   * The names a SHOW statement lists, with a fact each ({@code size}, {@code state}, {@code kind}); {@code null} and
   * a note when Snowflake refused it.
   */
  private static List<Named> show(SnowflakeApi api, SnowflakeApi.Call c, String sql, Suggestions.Builder out,
      String what) {
    Answer a;
    try {
      a = api.run(c, SnowflakeApi.Statement.read(sql, Suggestions.MAX_OPTIONS + 1));
    } catch (ConnectorException e) {
      out.note("The " + what + " couldn't be listed: " + e.getMessage());
      return null;
    }
    Map<String, Integer> at = new LinkedHashMap<>();
    JsonNode rowType = a.rowType();
    for (int i = 0; i < rowType.size(); i++) {
      at.put(rowType.get(i).path("name").asString("").toLowerCase(Locale.ROOT), i);
    }
    Integer name = at.get("name");
    if (name == null) {
      return null;
    }
    List<Named> found = new ArrayList<>();
    for (JsonNode r : a.data()) {
      String n = text(r.path(name));
      if (n == null || found.size() > Suggestions.MAX_OPTIONS) {
        continue;
      }
      List<String> facts = new ArrayList<>();
      for (String column : List.of("size", "state", "kind")) {
        Integer i = at.get(column);
        String v = i == null ? null : text(r.path(i));
        if (v != null && !(column.equals("kind") && v.equalsIgnoreCase("STANDARD"))) {
          facts.add(v.toLowerCase(Locale.ROOT).replace('_', ' '));
        }
      }
      found.add(new Named(n, facts.isEmpty() ? null : String.join(", ", facts)));
    }
    return found;
  }

  /** A note when the form names a warehouse or database the role doesn't see. */
  private static void missing(String typed, List<Named> listed, String what, Suggestions.Builder out) {
    if (typed != null && listed.size() <= Suggestions.MAX_OPTIONS
        && listed.stream().noneMatch(n -> n.name().equals(typed))) {
      out.note("The role may not use the " + what + " " + ObjectName.quoteIfNeeded(typed) + ", or it doesn't exist: "
          + "choose one of the " + what + "s it may use.");
    }
  }

  private static SnowflakeApi.Call call(ConnectionConfig cfg, SnowflakeSettings s, SecretResolver secrets,
      ExecContext ctx) throws ConnectorException {
    return new SnowflakeApi.Call(cfg, s, ctx, SnowflakeAuth.of(s, secrets, ctx), "suggest");
  }

  /** {@code CURRENT_AVAILABLE_ROLES()}: a JSON array of role names. */
  static List<String> roles(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<String> out = new ArrayList<>();
      for (JsonNode r : JSON.readTree(json)) {
        String name = r.asString("").strip();
        if (!name.isEmpty() && !out.contains(name)) {
          out.add(name);
        }
      }
      return out;
    } catch (JacksonException e) {
      return List.of();
    }
  }

  private static String text(JsonNode n) {
    return n != null && n.isString() && !n.asString().isBlank() ? n.asString() : null;
  }

  private static String display(String name) {
    return Objects.requireNonNullElse(name, "?");
  }

  private static String present(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static String count(int n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }
}
