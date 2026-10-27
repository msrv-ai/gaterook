package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolDefinition.Risk;
import ai.msrv.gaterook.connectors.support.TemplateImporter;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code openapi} templates (report 10 §3.6, §6.5, §7.4; kit Connectors › Templates "billing-api v2 · 9 read only,
 * 5 approval · 14 operations"): one tool per selected operation of an OpenAPI 3.0/3.1 file, at most 200, each running
 * on its connection ("Runs on") without a {@code connection} argument.
 *
 * <p>Two steps. {@link #importDocument} reads the file (no I/O) and proposes a definition: every operation with what
 * it becomes, the operations selected (all of a small document, none of a large one until the person picks), the
 * server under the connection's base URLs. {@link #compile} turns a stored definition into tools: GET and HEAD are
 * Read only by default (Off on prod, like {@code http_get}), everything else waits for a person. A GET a person marks
 * as changing state becomes a mutation; a POST marked read only (a search) is allowed on dev and stage only, after
 * the person confirmed it doesn't change state. A tool's request is built from the binding and checked like
 * {@code http_request}: base URLs, the environment's allowlist, allowed paths, approval of the exact request.
 */
final class OpenApiTemplateKind implements TemplateKind, TemplateImporter {

  static final String KIND = "openapi";
  static final int MAX_OPERATIONS = 200;
  /** At most this many operations are selected without the person picking. */
  static final int SELECT_ALL_UP_TO = 50;

  private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
  private static final Pattern PREFIX = Pattern.compile("^([a-z][a-z0-9]{0,15})?$");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}/]+)}");
  private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]{1,128})}");
  private static final int MAX_SERVERS = 100;
  private static final int MAX_SERVER_URL = 2_048;

  private final JsonNode definitionSchema;

  OpenApiTemplateKind(JsonNode definitionSchema) {
    this.definitionSchema = definitionSchema;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public List<String> connectionTypes() {
    return List.of(HttpConnector.TYPE);
  }

  @Override
  public JsonNode definitionSchema() {
    return definitionSchema;
  }

  // ── Import ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public TemplateImporter.Result importDocument(byte[] document, ConnectionConfig runsOn, JsonNode options) {
    JsonNode opts = options == null ? JsonNodeFactory.instance.objectNode() : options;
    List<String> warnings = new ArrayList<>();
    // GitHub's 13.6 MB take a while to hash: alongside the parse, not after it.
    CompletableFuture<String> digest = CompletableFuture.supplyAsync(() -> sha256(document));
    HttpSettings s;
    try {
      s = HttpSettings.of(runsOn);
    } catch (ConnectorException e) {
      return failed(e.getMessage());
    }
    JsonNode root;
    try {
      root = OpenApiDocument.read(document);
    } catch (OpenApiDocument.Rejected e) {
      return failed(e.getMessage());
    }
    ObjectNode info = JsonNodeFactory.instance.objectNode();
    info.put("title", ToolText.clean(root.path("info").path("title").asString(""), ToolText.TITLE));
    info.put("version", ToolText.clean(root.path("info").path("version").asString(""), 64));
    info.put("openapi", root.path("openapi").asString(""));
    info.put("sha256", digest.join());
    info.put("bytes", document.length);
    ArrayNode servers = info.putArray("servers");
    List<HttpUrl> serverUrls = servers(root, s, warnings);
    serverUrls.forEach(u -> servers.add(u.toString()));

    OpenApiImport.Reserved reserved = OpenApiImport.Reserved.of(opts.path("reserved"));
    String prefix;
    if (opts.path("prefix").isString()) {
      prefix = opts.get("prefix").asString();
      if (reserved.family(prefix)) {
        return new TemplateImporter.Result(null, List.of(), info, List.of("The prefix " + prefix + " is the family "
            + "of built-in tools (" + prefix + "_…). Pick another."), warnings);
      }
    } else {
      prefix = defaultPrefix(runsOn.name());
      if (reserved.family(prefix)) {
        prefix = prefix.length() > 13 ? "api" : prefix + "api";
      }
    }
    if (!PREFIX.matcher(prefix).matches()) {
      return new TemplateImporter.Result(null, List.of(), info, List.of("The prefix is up to 16 lowercase letters "
          + "and digits, starting with a letter."), warnings);
    }
    HttpUrl server = chooseServer(opts.path("server").asString(null), serverUrls, s, warnings);
    if (server == null) {
      return new TemplateImporter.Result(null, List.of(), info, List.of("None of the document's servers is under a "
          + "base URL of " + runsOn.name() + " (" + String.join(", ", baseTexts(s)) + "). Add the server to its Base "
          + "URLs, or pick a server under them."), warnings);
    }

    Set<String> gatewayHeaders = new HashSet<>(s.gatewayHeaders());
    String authQuery = s.auth().type() == HttpSettings.AuthType.API_KEY_QUERY ? s.auth().name() : null;
    List<OpenApiImport.Entry> entries;
    try {
      entries = new OpenApiImport(root, gatewayHeaders, authQuery, reserved).entries(prefix);
    } catch (OpenApiDocument.Rejected e) {
      return new TemplateImporter.Result(null, List.of(), info, List.of(e.getMessage()), warnings);
    }
    Set<String> select = new LinkedHashSet<>();
    boolean picked = opts.path("select").isArray();
    for (JsonNode n : opts.path("select")) {
      select.add(n.asString(""));
    }
    long usable = entries.stream().filter(e -> e.skipped() == null).count();
    if (!picked && usable > SELECT_ALL_UP_TO) {
      warnings.add("The file has " + usable + " operations: pick up to " + MAX_OPERATIONS + " (by tag or path) to "
          + "make tools of.");
    }
    List<TemplateImporter.Operation> operations = new ArrayList<>();
    ArrayNode chosen = JsonNodeFactory.instance.arrayNode();
    Set<String> known = new HashSet<>();
    for (OpenApiImport.Entry e : entries) {
      known.add(e.opKey());
      boolean selected = e.skipped() == null && (picked ? select.contains(e.opKey()) : usable <= SELECT_ALL_UP_TO);
      if (selected) {
        chosen.add(e.definition());
      }
      operations.add(new TemplateImporter.Operation(e.opKey(), e.skipped() == null
          ? e.definition().path("name").asString() : null, e.method(), e.path(), e.summary(), e.tags(), e.nature(),
          selected, e.skipped()));
    }
    for (String key : select) {
      if (!known.contains(key)) {
        warnings.add(key + " isn't an operation of the file.");
      }
    }
    if (chosen.size() > MAX_OPERATIONS) {
      return new TemplateImporter.Result(null, operations, info, List.of(chosen.size() + " operations are selected; "
          + "a template has at most " + MAX_OPERATIONS + "."), warnings);
    }
    long skipped = entries.stream().filter(e -> e.skipped() != null).count();
    if (skipped > 0) {
      warnings.add(skipped + (skipped == 1 ? " operation can't" : " operations can't") + " become tools (see why).");
    }
    ObjectNode definition = JsonNodeFactory.instance.objectNode();
    String name = opts.path("name").isString() && !opts.get("name").asString().isBlank()
        ? opts.get("name").asString() : defaultName(runsOn.name(), info.path("version").asString(""),
        info.path("title").asString(""));
    definition.put("name", ToolText.clean(name, 64));
    definition.put("prefix", prefix);
    definition.put("server", server.toString());
    ObjectNode source = definition.putObject("source");
    source.put("title", info.path("title").asString(""));
    source.put("version", info.path("version").asString(""));
    source.put("openapi", info.path("openapi").asString(""));
    source.put("sha256", info.path("sha256").asString(""));
    source.put("bytes", document.length);
    definition.set("operations", chosen);
    info.put("operations", entries.size());
    info.put("usable", usable);
    return new TemplateImporter.Result(definition, operations, info, List.of(), warnings);
  }

  private static TemplateImporter.Result failed(String error) {
    return new TemplateImporter.Result(null, List.of(), JsonNodeFactory.instance.objectNode(), List.of(error),
        List.of());
  }

  /**
   * The document's servers with {@code variables} at their defaults; a relative server ({@code /v1}) is resolved
   * against the origin of the connection's first base URL. No servers: the first base URL itself. Each placeholder of
   * the URL as written is filled in once — a default naming another variable stays as it is (replaced one variable
   * after another, such defaults doubled the URL each time) — and a server longer than 2,048 characters, or past the
   * first 100, is left out.
   */
  private static List<HttpUrl> servers(JsonNode root, HttpSettings s, List<String> warnings) {
    Set<HttpUrl> out = new LinkedHashSet<>();
    int seen = 0;
    for (JsonNode server : root.path("servers")) {
      if (++seen > MAX_SERVERS) {
        warnings.add("Only the first " + MAX_SERVERS + " servers of the document are read.");
        break;
      }
      String url = fill(server.path("url").asString(""), server.path("variables"));
      if (url.length() > MAX_SERVER_URL) {
        warnings.add("Server " + ToolText.clean(url, 200) + " is longer than 2,048 characters.");
        continue;
      }
      HttpUrl parsed = url.startsWith("/") ? s.baseUrls().getFirst().resolve(url) : HttpUrl.parse(url);
      if (parsed == null) {
        warnings.add("Server " + ToolText.clean(url, 200) + " isn't a URL the gateway can use.");
        continue;
      }
      String text = parsed.toString();
      if (text.endsWith("/") && parsed.encodedPath().length() > 1) {
        parsed = HttpUrl.parse(text.substring(0, text.length() - 1));
      }
      out.add(parsed);
    }
    if (out.isEmpty()) {
      out.add(s.baseUrls().getFirst());
    }
    return List.copyOf(out);
  }

  /** {@code url} with each {@code {name}} of a declared variable replaced by its default, in one pass. */
  private static String fill(String url, JsonNode variables) {
    if (url.length() > MAX_SERVER_URL || !variables.isObject() || variables.isEmpty()) {
      return url;
    }
    Matcher m = VARIABLE.matcher(url);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      JsonNode variable = variables.get(m.group(1));
      String value = variable == null ? m.group() : variable.path("default").asString("");
      m.appendReplacement(out, Matcher.quoteReplacement(value));
      if (out.length() > MAX_SERVER_URL) {
        return out.toString();
      }
    }
    m.appendTail(out);
    return out.toString();
  }

  private static HttpUrl chooseServer(String wanted, List<HttpUrl> servers, HttpSettings s, List<String> warnings) {
    if (wanted != null && !wanted.isBlank()) {
      HttpUrl url = HttpUrl.parse(wanted);
      return url != null && s.baseOf(url).isPresent() ? url : null;
    }
    for (HttpUrl u : servers) {
      if (s.baseOf(u).isPresent()) {
        if (servers.size() > 1) {
          warnings.add("The tools call " + u + ", the first server under the connection's base URLs.");
        }
        return u;
      }
    }
    return null;
  }

  /** {@code billing-api} → {@code billing}: the first word of the connection's name. */
  static String defaultPrefix(String connectionName) {
    String word = connectionName.toLowerCase(Locale.ROOT).split("[^a-z0-9]+", -1)[0];
    word = word.replaceAll("^[0-9]+", "");
    if (word.isEmpty()) {
      return "api";
    }
    return word.length() > 16 ? word.substring(0, 16) : word;
  }

  /** {@code billing-api v2} as in the kit: the connection and the API's major version. */
  static String defaultName(String connectionName, String version, String title) {
    Matcher m = Pattern.compile("^v?(\\d+)").matcher(version);
    if (m.find()) {
      return connectionName + " v" + m.group(1);
    }
    return title.isBlank() ? connectionName + " API" : title;
  }

  // ── Compile ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    HttpSettings s;
    try {
      s = HttpSettings.of(runsOn);
    } catch (ConnectorException e) {
      return new CompileResult(List.of(), List.of(), List.of(e.getMessage()), List.of(), null);
    }
    String templateName = definition.path("name").asString("").strip();
    String prefix = definition.path("prefix").asString("");
    if (!PREFIX.matcher(prefix).matches()) {
      errors.add("Prefix: up to 16 lowercase letters and digits, starting with a letter.");
    }
    HttpUrl server = HttpUrl.parse(definition.path("server").asString(""));
    if (server == null) {
      errors.add("Server: the URL the tools call is missing.");
    } else {
      try {
        UrlRules.parse(server.toString());
        if (server.encodedQuery() != null || s.baseOf(server).isEmpty()) {
          errors.add("Server " + server + " isn't under a base URL of " + runsOn.name() + ".");
        }
      } catch (UrlRules.UrlRejectedException e) {
        errors.add("Server: " + e.getMessage() + ".");
      }
    }
    JsonNode ops = definition.path("operations");
    if (!ops.isArray() || ops.isEmpty()) {
      errors.add("Select at least one operation.");
    } else if (ops.size() > MAX_OPERATIONS) {
      errors.add(ops.size() + " operations; a template has at most " + MAX_OPERATIONS + ".");
    }
    if (!errors.isEmpty()) {
      return new CompileResult(List.of(), List.of(), List.copyOf(errors), List.copyOf(warnings), null);
    }
    String serverPath = server.encodedPath().endsWith("/") ? server.encodedPath().substring(0,
        server.encodedPath().length() - 1) : server.encodedPath();
    Set<String> keys = new HashSet<>();
    Set<String> names = new HashSet<>();
    List<CompiledTool> tools = new ArrayList<>();
    for (JsonNode op : ops) {
      String opKey = op.path("opKey").asString("");
      String method = op.path("method").asString("").toUpperCase(Locale.ROOT);
      String path = op.path("path").asString("");
      String name = op.path("name").asString("");
      String where = opKey.isEmpty() ? "An operation" : opKey;
      if (!RequestPlanner.METHODS.contains(method) || !opKey.equals(method + " " + path)) {
        errors.add(where + ": the key is the method and the path (" + method + " " + path + ").");
        continue;
      }
      if (!keys.add(opKey)) {
        errors.add(opKey + " is selected twice.");
        continue;
      }
      if (!path.startsWith("/") || path.contains("?") || path.contains("#")
          || path.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
        errors.add(opKey + ": the path isn't a plain path.");
        continue;
      }
      if (!TOOL_NAME.matcher(name).matches()) {
        errors.add(opKey + ": the tool name " + name + " isn't lowercase letters, digits and _.");
        continue;
      }
      if (!prefix.isEmpty() && !name.startsWith(prefix + "_")) {
        errors.add(opKey + ": the tool name " + name + " doesn't start with the prefix " + prefix + "_.");
        continue;
      }
      if (!names.add(name)) {
        errors.add(name + " is the name of two operations.");
        continue;
      }
      JsonNode input = op.path("inputSchema");
      String problem = inputProblem(opKey, path, input, op);
      if (problem != null) {
        errors.add(problem);
        continue;
      }
      String nature = op.path("nature").asString(method.equals("GET") || method.equals("HEAD") ? "read"
          : "mutation");
      boolean readMethod = method.equals("GET") || method.equals("HEAD");
      if (!nature.equals("read") && !nature.equals("mutation")) {
        errors.add(opKey + ": nature is read or mutation.");
        continue;
      }
      if (nature.equals("read") && !readMethod) {
        if (runsOn.env() == Env.PROD) {
          errors.add(opKey + " can't be marked read only on prod: every " + method + " there waits for a person.");
          continue;
        }
        if (!op.path("readOnlyConfirmed").asBoolean(false)) {
          errors.add(opKey + " is marked read only: confirm that it doesn't change state.");
          continue;
        }
        warnings.add(opKey + " runs without approval as a read: you confirmed it doesn't change state.");
      }
      boolean read = nature.equals("read");
      String fullPath = serverPath + path;
      String title = ToolText.clean(op.path("title").asString(opKey), ToolText.TITLE);
      String description = ToolText.clean(op.path("description").asString(""), 900);
      StringBuilder text = new StringBuilder(description == null ? "" : description);
      if (!text.isEmpty() && ".!?…".indexOf(text.charAt(text.length() - 1)) < 0) {
        text.append('.');
      }
      text.append(text.isEmpty() ? "" : " ").append(method).append(' ').append(fullPath).append('.');
      if (!read) {
        text.append(" Needs approval: a person approves the exact request.");
      }
      ToolDefinition.Builder tool = ToolDefinition.builder(name)
          .title(title.isEmpty() ? opKey : title)
          .description(ToolText.clean(text.toString(), ToolText.TOOL_DESCRIPTION))
          .inputSchema(cleanTexts(input))
          .resultShape(ResultShape.JSON)
          .risk(new Risk(true, true, Egress.CHANNEL));
      if (read) {
        tool.nature(Nature.READ).annotations(new Annotations(true, false, readMethod, true))
            .defaultMode(Mode.READ_ONLY, Mode.OFF);
      } else {
        // Report 10 §3: POST/PATCH destructive, PUT/DELETE destructive and idempotent; a GET marked as changing
        // state has unknown side effects.
        boolean idempotent = method.equals("PUT") || method.equals("DELETE");
        tool.nature(Nature.MUTATION).annotations(new Annotations(false, true, idempotent, true))
            .defaultMode(Mode.APPROVAL, Mode.APPROVAL);
      }
      ObjectNode binding = JsonNodeFactory.instance.objectNode();
      binding.put("template", templateName.isEmpty() ? "openapi" : templateName);
      if (op.path("operationId").isString()) {
        binding.put("operationId", ToolText.clean(op.get("operationId").asString(), 120));
      }
      binding.put("opKey", opKey);
      binding.put("method", method);
      binding.put("server", server.toString());
      binding.put("path", path);
      binding.put("nature", nature);
      binding.set("params", op.path("params").isArray() ? op.get("params").deepCopy()
          : JsonNodeFactory.instance.arrayNode());
      if (op.path("body").isObject()) {
        binding.set("body", op.get("body").deepCopy());
      }
      try {
        tools.add(new CompiledTool(opKey, tool.build(), binding));
      } catch (IllegalStateException e) {
        errors.add(opKey + ": " + e.getMessage());
      }
    }
    if (tools.size() > 40) {
      warnings.add(tools.size() + " tools: some clients show only the first 40 tools of a server. Add only what an "
          + "endpoint needs.");
    }
    if (!errors.isEmpty()) {
      return new CompileResult(List.of(), List.of(), List.copyOf(errors), List.copyOf(warnings), null);
    }
    return new CompileResult(List.copyOf(tools), List.of(), List.of(), List.copyOf(warnings), null);
  }

  /** Why an operation's input schema can't be a tool's: shape, size, and every binding has its property. */
  private static String inputProblem(String opKey, String path, JsonNode input, JsonNode op) {
    if (!input.isObject() || !"object".equals(input.path("type").asString(""))) {
      return opKey + ": the input schema is an object schema.";
    }
    if (input.path("properties").has("connection")) {
      return opKey + ": a template tool has no connection argument; rename the parameter.";
    }
    int bytes = JsonText.compact(input).getBytes(StandardCharsets.UTF_8).length;
    if (bytes > OpenApiImport.MAX_INPUT_SCHEMA_BYTES) {
      return opKey + ": the input schema is " + Sizes.text(bytes) + ", more than 32 KB.";
    }
    Set<String> placeholders = new HashSet<>();
    Matcher m = PLACEHOLDER.matcher(path);
    while (m.find()) {
      placeholders.add(m.group(1));
    }
    Set<String> args = new HashSet<>();
    for (JsonNode p : op.path("params")) {
      String arg = p.path("arg").asString("");
      String in = p.path("in").asString("");
      if (!Set.of("path", "query", "header").contains(in) || arg.isEmpty() || !input.path("properties").has(arg)
          || !args.add(arg)) {
        return opKey + ": parameter " + p.path("name").asString(arg) + " has no property of its own in the input.";
      }
      if (in.equals("header") && (Headers.isForbidden(p.path("name").asString("")) || !Headers.validName(
          p.path("name").asString("")))) {
        return opKey + ": the header " + p.path("name").asString("") + " can't be sent by an agent.";
      }
      placeholders.remove(in.equals("path") ? p.path("name").asString("") : "");
    }
    if (!placeholders.isEmpty()) {
      return opKey + ": path parameter {" + placeholders.iterator().next() + "} has no binding.";
    }
    JsonNode body = op.path("body");
    if (body.isObject()) {
      String arg = body.path("arg").asString("");
      String media = body.path("mediaType").asString("");
      if (arg.isEmpty() || !input.path("properties").has(arg) || args.contains(arg)) {
        return opKey + ": the body has no property of its own in the input.";
      }
      if (!media.equals("application/json") && !media.endsWith("+json")
          && !media.equals("application/x-www-form-urlencoded")) {
        return opKey + ": a " + media + " body can't be sent.";
      }
    }
    return null;
  }

  /** The author's texts inside a schema, cleaned again: a stored definition is edited by hand too. */
  private static JsonNode cleanTexts(JsonNode schema) {
    JsonNode copy = schema.deepCopy();
    clean(copy, 0);
    return copy;
  }

  private static void clean(JsonNode n, int depth) {
    if (depth > 64) {
      return;
    }
    if (n instanceof ObjectNode o) {
      for (String key : new ArrayList<>(o.propertyNames())) {
        JsonNode v = o.get(key);
        if ((key.equals("description") || key.equals("title")) && v.isString()) {
          o.put(key, ToolText.clean(v.asString(), key.equals("title") ? ToolText.TITLE
              : ToolText.PROPERTY_DESCRIPTION));
        } else {
          clean(v, depth + 1);
        }
      }
    } else if (n instanceof ArrayNode a) {
      for (JsonNode c : a) {
        clean(c, depth + 1);
      }
    }
  }

  // ── Preview ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The exact request of one operation, no I/O: {@code arguments} names it ({@code operation}: its key or tool name)
   * and holds its {@code arguments}; a template of one operation needs no name.
   */
  @Override
  public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
    CompileResult compiled = compile(definition, runsOn);
    String target = runsOn.name();
    if (!compiled.ok()) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", String.join("\n", compiled.errors()), target,
          "The template doesn't compile").withWarnings(compiled.errors());
    }
    String wanted = arguments.path("operation").asString(null);
    CompiledTool tool = null;
    for (CompiledTool t : compiled.tools()) {
      if (t.opKey().equals(wanted) || t.definition().name().equals(wanted)) {
        tool = t;
      }
    }
    if (tool == null && compiled.tools().size() == 1 && wanted == null) {
      tool = compiled.tools().getFirst();
    }
    if (tool == null) {
      List<String> keys = compiled.tools().stream().map(CompiledTool::opKey).toList();
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "Name the operation to preview: {\"operation\": \""
          + keys.getFirst() + "\", \"arguments\": {…}}. Operations: " + String.join(", ", keys) + ".", target,
          "Pick an operation");
    }
    JsonNode args = arguments.path("arguments").isObject() ? arguments.get("arguments")
        : wanted == null ? arguments : JsonNodeFactory.instance.objectNode();
    try {
      HttpSettings s = HttpSettings.of(runsOn);
      String label = tool.binding().path("operationId").asString(tool.opKey()) + " · "
          + tool.binding().path("template").asString("template");
      PlannedRequest r = OpenApiCall.plan(tool.binding(), args, s, new PlannedRequest.Operation("preview", 0,
          tool.opKey(), label, label));
      RequestPlanner.checkConnection(r, s, runsOn);
      return ActionText.of(r, s, runsOn, null);
    } catch (ConnectorException e) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", e.getMessage(), target, "Can't build the request")
          .withWarnings(List.of(e.getMessage()));
    } catch (Refusal e) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", e.getMessage(), target, "Can't build the request")
          .withWarnings(List.of(e.getMessage()));
    }
  }

  private static List<String> baseTexts(HttpSettings s) {
    List<String> out = new ArrayList<>();
    s.baseUrls().forEach(b -> out.add(b.toString()));
    return out;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
