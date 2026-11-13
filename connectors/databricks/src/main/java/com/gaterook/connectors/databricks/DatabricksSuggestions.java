package com.gaterook.connectors.databricks;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.support.OutputSanitizer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Help for the Databricks form ({@link com.gaterook.connector.api.Connector#suggest}).
 *
 * <p>On this computer: the workspace profiles of {@code ~/.databrickscfg} (or {@code DATABRICKS_CONFIG_FILE}), the
 * default profile's host filled in, and the SQL warehouse and service principal of the profile whose host the form
 * names. Hosts, warehouse IDs and client IDs only: a token or client secret in the file is never read into a
 * suggestion.
 *
 * <p>Signed in as Test connection signs in: the SQL warehouses the identity may use, the only one (or the only one
 * running) filled in; the catalogs and the schemas of the default catalog for the defaults and the lists, the default
 * filled in where a list requires one. Reads of the workspace's REST APIs only: no warehouse is started.
 */
final class DatabricksSuggestions {

  /** {@code http_path} of a SQL warehouse: {@code /sql/1.0/warehouses/<id>}, or the older {@code endpoints}. */
  private static final Pattern WAREHOUSE_PATH = Pattern.compile(
      "/sql/1\\.0/(?:warehouses|endpoints)/([A-Za-z0-9-]{1,64})/?");
  private static final long MAX_FILE = 1 << 20;
  private static final int MAX_LISTED = Suggestions.MAX_OPTIONS;

  private DatabricksSuggestions() {}

  /** Where this computer's files are and what its environment says: the tests' own, or this process's. */
  record Machine(Function<String, String> env, Path home) {

    static Machine system() {
      return new Machine(System::getenv, Path.of(System.getProperty("user.home")));
    }
  }

  /** One workspace profile of {@code .databrickscfg}, as far as a form can use it. */
  record Profile(String name, String host, String warehouseId, String clientId, boolean token) {}

  static Suggestions suggest(DatabricksApi api, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
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
    String configured = present(machine.env().apply("DATABRICKS_CONFIG_FILE"));
    Path file = configured != null ? Path.of(configured.startsWith("~") ? machine.home() + configured.substring(1)
        : configured) : machine.home().resolve(".databrickscfg");
    List<Profile> profiles = profiles(file);
    if (profiles.isEmpty()) {
      return;
    }
    String source = shown(machine, file);
    String wanted = present(machine.env().apply("DATABRICKS_CONFIG_PROFILE"));
    String def = wanted == null ? "DEFAULT" : wanted;
    Profile preferred = profiles.stream().filter(p -> p.name().equals(def)).findFirst()
        .orElse(profiles.size() == 1 ? profiles.getFirst() : null);
    List<Option> hosts = new ArrayList<>();
    for (Profile p : profiles) {
      if (hosts.stream().noneMatch(o -> o.value().equalsIgnoreCase(p.host()))) {
        hosts.add(new Option(p.host(), null, "profile " + p.name()));
      }
    }
    out.value("/host", source, preferred == null ? null : preferred.host(), hosts);
    String typed = host(cfg.string("/host"));
    Profile chosen = typed == null ? preferred : profiles.stream().filter(p -> p.host().equalsIgnoreCase(typed))
        .findFirst().orElse(null);
    if (chosen == null) {
      out.note("Found " + count(profiles.size(), "workspace profile") + " in " + source + ".");
      return;
    }
    if (chosen.warehouseId() != null) {
      out.value("/warehouseId", "profile " + chosen.name(), chosen.warehouseId(),
          List.of(Option.of(chosen.warehouseId())));
    }
    if (chosen.clientId() != null) {
      out.value("/auth/clientId", "profile " + chosen.name(), chosen.clientId(), List.of(Option.of(chosen.clientId())));
    }
    out.note("Found " + count(profiles.size(), "workspace profile") + " in " + source + ": the form uses "
        + chosen.name() + (chosen.token() && chosen.clientId() == null ? ", which signs in with a personal access "
        + "token" : "") + ".");
  }

  /** The workspace profiles of a {@code .databrickscfg}: account consoles and profiles without a host left out. */
  static List<Profile> profiles(Path file) {
    String text;
    try {
      if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE) {
        return List.of();
      }
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      return List.of();
    }
    Map<String, Map<String, String>> sections = new LinkedHashMap<>();
    Map<String, String> current = null;
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
        continue;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        current = sections.computeIfAbsent(line.substring(1, line.length() - 1).strip(), k -> new LinkedHashMap<>());
        continue;
      }
      int eq = line.indexOf('=');
      if (current != null && eq > 0) {
        current.putIfAbsent(line.substring(0, eq).strip().toLowerCase(Locale.ROOT), line.substring(eq + 1).strip());
      }
    }
    List<Profile> out = new ArrayList<>();
    sections.forEach((name, keys) -> {
      String host = host(keys.get("host"));
      if (host == null || URI.create(host).getHost().startsWith("accounts.")) {
        return;
      }
      String warehouse = keys.get("warehouse_id");
      if (warehouse == null && keys.get("http_path") != null) {
        Matcher m = WAREHOUSE_PATH.matcher(keys.get("http_path"));
        warehouse = m.matches() ? m.group(1) : null;
      }
      String clientId = keys.get("client_id");
      out.add(new Profile(name, host, warehouse != null && DatabricksSettings.WAREHOUSE.matcher(warehouse).matches()
          ? warehouse : null, clientId != null && DatabricksSettings.CLIENT_ID.matcher(clientId).matches() ? clientId
          : null, present(keys.get("token")) != null));
    });
    return out;
  }

  /** A host as the form takes it: {@code https://<host>[:port]}, or {@code null} when it isn't a workspace URL. */
  static String host(String text) {
    String t = present(text);
    if (t == null) {
      return null;
    }
    if (!t.contains("://")) {
      t = "https://" + t;
    }
    try {
      URI base = DatabricksSettings.base(t);
      String b = base.toString();
      return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    } catch (ConnectorException | IllegalArgumentException e) {
      return null;
    }
  }

  /** {@code ~/.databrickscfg} rather than the home folder's full path, as people write it. */
  private static String shown(Machine machine, Path file) {
    Path home = machine.home().toAbsolutePath().normalize();
    Path p = file.toAbsolutePath().normalize();
    return p.startsWith(home) ? "~/" + home.relativize(p).toString().replace('\\', '/') : p.toString();
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  static void service(DatabricksApi api, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Builder out) {
    DatabricksSettings s;
    try {
      s = DatabricksSettings.forSuggestions(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    boolean cached = api.auth.has(cfg.id());
    DatabricksApi.Call c = new DatabricksApi.Call(cfg, s, secrets, ctx);
    try {
      String identity;
      try {
        JsonNode me = api.get(c, "api/2.0/preview/scim/v2/Me", "The workspace's sign-in");
        identity = OutputSanitizer.sanitize(me.path("userName").asString(me.path("displayName").asString(
            me.path("applicationId").asString(s.clientId() == null ? "?" : s.clientId()))));
      } catch (ConnectorException e) {
        out.note(e.getMessage());
        return;
      }
      List<String> found = new ArrayList<>();
      warehouses(api, c, s, out, found);
      catalogs(api, c, s, out, found);
      out.note("Signed in to " + s.host() + " as " + identity + (found.isEmpty() ? "." : ": " + String.join(", ",
          found) + "."));
    } finally {
      if (!cached) {
        // A token got for the helpers alone: the next request signs in again, as a draft's test does.
        api.auth.evict(cfg.id());
      }
    }
  }

  /** The SQL warehouses the identity may use; the only one, or the only one running, filled in. */
  private static void warehouses(DatabricksApi api, DatabricksApi.Call c, DatabricksSettings s,
      Suggestions.Builder out, List<String> found) {
    JsonNode list;
    try {
      list = api.get(c, "api/2.0/sql/warehouses", "The SQL warehouses");
    } catch (ConnectorException e) {
      out.note("The SQL warehouses couldn't be listed: " + e.getMessage());
      return;
    }
    List<Option> options = new ArrayList<>();
    List<String> running = new ArrayList<>();
    for (JsonNode w : list.path("warehouses")) {
      String id = w.path("id").asString("");
      if (!DatabricksSettings.WAREHOUSE.matcher(id).matches()) {
        continue;
      }
      String state = w.path("state").asString("");
      List<String> facts = new ArrayList<>();
      if (!state.isBlank()) {
        facts.add(state.toLowerCase(Locale.ROOT));
      }
      if (w.path("cluster_size").isString()) {
        facts.add(w.get("cluster_size").asString());
      }
      if (w.path("enable_serverless_compute").asBoolean(false)) {
        facts.add("serverless");
      }
      String name = OutputSanitizer.sanitize(w.path("name").asString(id));
      options.add(new Option(id, name + " · " + id, facts.isEmpty() ? null : String.join(", ", facts)));
      if (state.equals("RUNNING")) {
        running.add(id);
      }
    }
    if (options.isEmpty()) {
      out.note("The identity may use no SQL warehouse: give it CAN USE on one.");
      return;
    }
    String value = options.size() == 1 ? options.getFirst().value() : running.size() == 1 ? running.getFirst() : null;
    out.value("/warehouseId", "the workspace", value, options);
    found.add(count(options.size(), "SQL warehouse"));
    if (s.warehouse() != null && options.stream().noneMatch(o -> o.value().equals(s.warehouse()))) {
      out.note("The identity may not use the SQL warehouse " + s.warehouse() + ", or it doesn't exist: choose one of "
          + "the warehouses it may use.");
    }
  }

  /**
   * The catalogs, and the schemas of the default catalog: the connection's, else the one filled in, else the
   * workspace's. A default is filled in only where a list requires one: the workspace's default when the list allows
   * it, else the list's only one.
   */
  private static void catalogs(DatabricksApi api, DatabricksApi.Call c, DatabricksSettings s,
      Suggestions.Builder out, List<String> found) {
    JsonNode list;
    try {
      list = api.get(c, "api/2.1/unity-catalog/catalogs" + DatabricksApi.query("max_results",
          Integer.toString(MAX_LISTED)), "Unity Catalog");
    } catch (ConnectorException e) {
      out.note("The catalogs couldn't be listed: " + e.getMessage());
      return;
    }
    List<Option> catalogs = new ArrayList<>();
    for (JsonNode cat : list.path("catalogs")) {
      String name = cat.path("name").asString("");
      if (DatabricksSettings.OBJECT.matcher(name).matches()) {
        String kind = cat.path("catalog_type").asString("").toLowerCase(Locale.ROOT).replace('_', ' ');
        catalogs.add(new Option(name, null, kind.isBlank() ? null : kind));
      }
    }
    boolean complete = list.path("next_page_token").asString("").isBlank();
    String workspaceDefault = null;
    try {
      workspaceDefault = api.get(c, "api/2.1/unity-catalog/current-metastore-assignment", "Unity Catalog")
          .path("default_catalog_name").asString(null);
    } catch (ConnectorException e) {
      // The workspace's default stays unknown: nothing is filled in for it.
    }
    String fill = null;
    if (s.catalog() == null && !s.catalogs().isEmpty()) {
      List<String> allowed = catalogs.stream().map(Option::value).filter(s::allowsCatalog).toList();
      fill = workspaceDefault != null && allowed.contains(workspaceDefault) ? workspaceDefault
          : allowed.size() == 1 ? allowed.getFirst() : null;
    }
    found.add(count(catalogs.size(), "catalog") + (complete ? "" : "+"));
    if (catalogs.isEmpty()) {
      return;
    }
    out.field("/catalog", new Suggestions.Field(catalogs, fill, "Unity Catalog", complete));
    out.field("/catalogs", new Suggestions.Field(catalogs, null, "Unity Catalog", complete));
    String catalog = s.catalog() != null ? s.catalog() : fill != null ? fill : workspaceDefault;
    if (catalog == null || catalogs.stream().noneMatch(o -> o.value().equalsIgnoreCase(catalog))) {
      return;
    }
    JsonNode schemas;
    try {
      schemas = api.get(c, "api/2.1/unity-catalog/schemas" + DatabricksApi.query("catalog_name", catalog,
          "max_results", Integer.toString(MAX_LISTED)), "Unity Catalog");
    } catch (ConnectorException e) {
      out.note("The schemas of " + catalog + " couldn't be listed: " + e.getMessage());
      return;
    }
    List<Option> names = new ArrayList<>();
    List<Option> qualified = new ArrayList<>();
    for (JsonNode sch : schemas.path("schemas")) {
      String name = sch.path("name").asString("");
      if (DatabricksSettings.OBJECT.matcher(name).matches() && !name.equals("information_schema")) {
        names.add(Option.of(name));
        qualified.add(new Option(catalog + "." + name, null, "schema " + name + " of " + catalog));
      }
    }
    if (names.isEmpty()) {
      return;
    }
    boolean all = schemas.path("next_page_token").asString("").isBlank();
    String schemaFill = null;
    if (s.schema() == null && !s.schemas().isEmpty()) {
      List<String> allowed = names.stream().map(Option::value).filter(n -> s.allows(catalog, n)).toList();
      schemaFill = allowed.contains("default") ? "default" : allowed.size() == 1 ? allowed.getFirst() : null;
    }
    out.field("/schema", new Suggestions.Field(names, schemaFill, "schemas of " + catalog, all));
    out.field("/schemas", new Suggestions.Field(qualified, null, "Unity Catalog", all));
  }

  private static String present(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static String count(int n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }
}
