package com.gaterook.connectors.mcpserver;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.support.mcp.FixedHeaders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * The settings of one {@code mcp-server} connection as the runtime reads them ({@code config.schema.json}). The values
 * of variables and headers are never here: a binding names the secret field ({@code /env/0/secretRef}) the value is
 * resolved from when the process starts.
 *
 * @param auth            the HTTP server's sign-in settings as canonical JSON ({@code {"type":"oauth",…}}), or
 *                        {@code null}: the tokens are the core's
 *                        ({@link com.gaterook.connectors.support.mcp.McpServerRuntime.Authorization}), these are
 *                        only what the person chose
 * @param shareResources  whether agents may read the server's resources and resource templates
 *                        ({@code share.resources}): on unless the person turns it off
 * @param sharePrompts    whether the person lets agents get the server's prompts ({@code share.prompts})
 * @param offThisComputer whether the person keeps a stdio server off this computer: an AppContainer on Windows
 *                        ({@code offThisComputer})
 * @param fixedHeaders    an HTTP server's headers with a value the person wrote in its settings, not a secret: a
 *                        switch such as {@code X-MCP-Readonly: true} ({@code fixedHeaders})
 */
record ServerConfig(String name, String transport, List<String> command, String url, List<Binding> env,
    List<Binding> headers, List<String> inheritEnv, boolean startWithCore, Duration idleStop, Duration startTimeout,
    Duration callTimeout, int maxInFlight, String catalogEntry, String toolPrefix, String auth,
    boolean shareResources, boolean sharePrompts, boolean offThisComputer, List<FixedHeader> fixedHeaders) {

  static final String STDIO = "stdio";
  static final String HTTP = "http";

  /** A variable (stdio) or header (HTTP) whose value comes from a secret. */
  record Binding(String name, String secretField) {}

  /** A header sent as the person wrote it in the server's settings. */
  record FixedHeader(String name, String text) {}

  /**
   * Why the fixed headers can't go beside these headers from secrets, or {@code null} ({@link FixedHeaders#problem}).
   */
  static String fixedHeaderProblem(List<FixedHeader> fixed, List<String> secretHeaders) {
    return FixedHeaders.problem(fixed.stream().map(h -> new FixedHeaders.Header(h.name(), h.text())).toList(),
        secretHeaders);
  }

  /** {@code auth.type} of a server the person signs in to in the browser (OAuth 2.1, ARCHITECTURE.md §7.5). */
  static final String OAUTH = "oauth";
  /** {@code auth.type} of a server that takes a Google access token from the server's Google Cloud credentials (§7.6). */
  static final String GOOGLE = "google";
  /** {@code auth.type} of a server on AWS whose requests are signed with SigV4 from the server's AWS credentials. */
  static final String AWS = "aws";

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
    JsonNode auth = c.path("auth");
    return new ServerConfig(cfg.name(), transport, List.copyOf(command), Texts.str(c.path("url"), null),
        bindings(cfg, "env"), bindings(cfg, "headers"), List.copyOf(inherit),
        "with-core".equals(Texts.str(c.path("start"), null)), Duration.ofMinutes(Math.max(0, idle)),
        Duration.ofSeconds(Math.clamp(start, 5, 300)), Duration.ofSeconds(Math.clamp(call, 1, 300)),
        Math.clamp(c.path("maxInFlight").asInt(4), 1, 16), Texts.str(c.path("catalogEntry"), null),
        prefix != null && PREFIX.matcher(prefix).matches() ? prefix : defaultPrefix(cfg.name()),
        HTTP.equals(transport) && auth.isObject() ? Texts.JSON.writeValueAsString(Texts.canonical(auth)) : null,
        c.path("share").path("resources").asBoolean(true), c.path("share").path("prompts").asBoolean(false),
        !HTTP.equals(transport) && c.path("offThisComputer").asBoolean(false),
        HTTP.equals(transport) ? fixedHeaders(c) : List.of());
  }

  /** The fixed headers of an HTTP server's settings, in their order; an entry without a name is left out. */
  private static List<FixedHeader> fixedHeaders(JsonNode c) {
    List<FixedHeader> out = new ArrayList<>();
    for (JsonNode h : c.path("fixedHeaders")) {
      String name = Texts.str(h.path("name"), null);
      if (name != null) {
        out.add(new FixedHeader(name, Texts.str(h.path("text"), "")));
      }
    }
    return List.copyOf(out);
  }

  boolean http() {
    return HTTP.equals(transport);
  }

  /** Whether the person signs in to this HTTP server in the browser, so each request carries the core's token. */
  boolean oauth() {
    return OAUTH.equals(authType());
  }

  /**
   * Whether this OAuth server also answers without a sign-in ({@code auth.optional}): it connects without one until the
   * person signs in, and its calls that need one say so.
   */
  boolean signInOptional() {
    return oauth() && authSettings().path("optional").asBoolean(false);
  }

  /** Whether each request carries a Google access token from the server's Google Cloud credentials. */
  boolean google() {
    return GOOGLE.equals(authType());
  }

  /** Whether each request is signed with SigV4 from the server's AWS credentials. */
  boolean aws() {
    return AWS.equals(authType());
  }

  /** The sign-in settings as the person chose them; a missing node for a server without any. */
  JsonNode authSettings() {
    return auth == null ? MissingNode.getInstance() : Texts.JSON.readTree(auth);
  }

  private String authType() {
    return auth == null ? null : Texts.str(authSettings().path("type"), null);
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
