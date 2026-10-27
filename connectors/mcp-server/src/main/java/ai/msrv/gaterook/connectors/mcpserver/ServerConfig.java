package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The settings of one {@code mcp-server} connection as the runtime reads them ({@code config.schema.json}). The values
 * of variables and headers are never here: a binding names the secret field ({@code /env/0/secretRef}) the value is
 * resolved from when the process starts.
 *
 * @param secretField the JSON pointer of the binding's secret, or {@code null} when the binding has none stored
 */
record ServerConfig(String name, String transport, List<String> command, String url, List<Binding> env,
    List<Binding> headers, List<String> inheritEnv, boolean startWithCore, Duration idleStop, Duration startTimeout,
    Duration callTimeout, int maxInFlight, String catalogEntry, String toolPrefix) {

  static final String STDIO = "stdio";
  static final String HTTP = "http";

  /** A variable (stdio) or header (HTTP) whose value comes from a secret. */
  record Binding(String name, String secretField) {}

  private static final Pattern PREFIX = Pattern.compile("^[a-z][a-z0-9]{0,14}_$");

  static ServerConfig of(ConnectionConfig cfg) {
    JsonNode c = cfg.config();
    String transport = Texts.str(c.path("transport"), STDIO);
    List<String> command = new ArrayList<>();
    for (JsonNode a : c.path("command")) {
      if (a.isString()) {
        command.add(a.asString());
      }
    }
    boolean prod = cfg.env() == Env.PROD;
    int idle = c.path("idleStopMinutes").asInt(prod ? 5 : 15);
    int start = c.path("startTimeoutSeconds").asInt(30);
    int call = c.path("callTimeoutSeconds").asInt(prod ? 30 : 60);
    List<String> inherit = new ArrayList<>();
    for (JsonNode v : c.path("inheritEnv")) {
      if (v.isString()) {
        inherit.add(v.asString());
      }
    }
    String prefix = Texts.str(c.path("toolPrefix"), null);
    return new ServerConfig(cfg.name(), transport, List.copyOf(command), Texts.str(c.path("url"), null),
        bindings(cfg, "env"), bindings(cfg, "headers"), List.copyOf(inherit),
        "with-core".equals(Texts.str(c.path("start"), null)), Duration.ofMinutes(Math.max(0, idle)),
        Duration.ofSeconds(Math.clamp(start, 5, 300)), Duration.ofSeconds(Math.clamp(call, 1, 300)),
        Math.clamp(c.path("maxInFlight").asInt(4), 1, 16), Texts.str(c.path("catalogEntry"), null),
        prefix != null && PREFIX.matcher(prefix).matches() ? prefix : defaultPrefix(cfg.name()));
  }

  boolean http() {
    return HTTP.equals(transport);
  }

  /**
   * The prefix a server gets unless the person picks one: its name up to the first {@code -}, letters and digits
   * ({@code github-mcp} → {@code github_}), as the Add MCP server dialog shows it.
   */
  static String defaultPrefix(String name) {
    String base = name == null ? "" : name.split("-", 2)[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
      base = "mcp" + base;
    }
    return (base.length() > 15 ? base.substring(0, 15) : base) + "_";
  }

  static boolean validPrefix(String prefix) {
    return prefix != null && PREFIX.matcher(prefix).matches();
  }

  private static List<Binding> bindings(ConnectionConfig cfg, String key) {
    List<Binding> out = new ArrayList<>();
    JsonNode list = cfg.config().path(key);
    for (int i = 0; i < list.size(); i++) {
      String name = Texts.str(list.get(i).path("name"), null);
      if (name == null) {
        continue;
      }
      String field = "/" + key + "/" + i + "/secretRef";
      out.add(new Binding(name, cfg.secretFields().contains(field) ? field : null));
    }
    return List.copyOf(out);
  }
}
