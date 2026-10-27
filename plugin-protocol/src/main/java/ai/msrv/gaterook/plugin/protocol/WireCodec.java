package ai.msrv.gaterook.plugin.protocol;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connector.api.TestResult;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * connector-api types to and from JSON trees, by hand (ARCHITECTURE.md §3, report 16 §7): no reflection and no data
 * binding, so the native core needs no metadata and a new field of a record is a decision made here, not a surprise
 * on the wire. Each message has a golden file ({@code src/test/resources/gpp/}).
 *
 * <p>Absent members read as the SPI's defaults; {@code null} values are written as JSON {@code null} only where the
 * SPI distinguishes them, otherwise left out.
 */
public final class WireCodec {

  private WireCodec() {}

  // ── Tools ───────────────────────────────────────────────────────────────────────────────────────────────────

  public static ObjectNode tool(ToolDefinition t) {
    ObjectNode o = GppJson.object();
    o.put("name", t.name());
    o.put("title", t.title());
    o.put("description", t.description());
    set(o, "inputSchema", t.inputSchema());
    set(o, "outputSchema", t.outputSchema());
    ObjectNode a = o.putObject("annotations");
    ToolDefinition.Annotations an = t.annotations() == null ? new ToolDefinition.Annotations(null, null, null, null)
        : t.annotations();
    a.put("readOnlyHint", an.readOnlyHint());
    a.put("destructiveHint", an.destructiveHint());
    a.put("idempotentHint", an.idempotentHint());
    a.put("openWorldHint", an.openWorldHint());
    o.put("nature", t.nature().name());
    o.put("defaultMode", t.defaultMode().name());
    o.put("prodDefaultMode", t.prodDefaultMode().name());
    o.put("resultShape", t.resultShape().name());
    ObjectNode risk = o.putObject("risk");
    risk.put("privateData", t.risk().privateData());
    risk.put("untrustedContent", t.risk().untrustedContent());
    risk.put("egress", t.risk().egress().name());
    return o;
  }

  /**
   * A tool as declared (a plugin's manifest, the host's answer to {@code plugin/load}). Built with the canonical
   * constructor: the core checks the declaration itself, the builder's rules would hide what was declared.
   */
  public static ToolDefinition tool(JsonNode n) {
    JsonNode a = n.path("annotations");
    JsonNode risk = n.path("risk");
    String name = n.path("name").asString();
    return new ToolDefinition(name, n.path("title").asString(name), n.path("description").asString(""),
        tree(n.get("inputSchema")), tree(n.get("outputSchema")),
        new ToolDefinition.Annotations(bool(a.get("readOnlyHint")), bool(a.get("destructiveHint")),
            bool(a.get("idempotentHint")), bool(a.get("openWorldHint"))),
        ToolDefinition.Nature.valueOf(n.path("nature").asString("MIXED")),
        ToolDefinition.Mode.valueOf(n.path("defaultMode").asString("APPROVAL")),
        ToolDefinition.Mode.valueOf(n.path("prodDefaultMode").asString("APPROVAL")),
        ToolDefinition.ResultShape.valueOf(n.path("resultShape").asString("JSON")),
        new ToolDefinition.Risk(risk.path("privateData").asBoolean(true), risk.path("untrustedContent").asBoolean(true),
            ToolDefinition.Egress.valueOf(risk.path("egress").asString("CHANNEL"))));
  }

  public static ArrayNode tools(List<ToolDefinition> tools) {
    ArrayNode out = GppJson.NODES.arrayNode();
    tools.forEach(t -> out.add(tool(t)));
    return out;
  }

  public static List<ToolDefinition> tools(JsonNode array) {
    List<ToolDefinition> out = new ArrayList<>();
    for (JsonNode t : array) {
      out.add(tool(t));
    }
    return List.copyOf(out);
  }

  /**
   * What a Read only confirmation and the "tools ⊆ manifest" check bind to (report 11 §3.1): SHA-256 of the canonical
   * JSON of what the plugin declares — name, title, description, schemas, annotations, nature and result shape. The
   * modes are the core's, not part of the declaration.
   */
  public static String definitionHash(ToolDefinition t) {
    ObjectNode o = tool(t);
    o.remove("defaultMode");
    o.remove("prodDefaultMode");
    o.remove("risk");
    return Canonical.sha256Hex(o);
  }

  // ── Results ─────────────────────────────────────────────────────────────────────────────────────────────────

  public static ObjectNode result(ToolResult r) {
    ObjectNode o = GppJson.object();
    ArrayNode content = o.putArray("content");
    for (ToolResult.Content c : r.content()) {
      ObjectNode item = content.addObject();
      item.put("type", c.type());
      putIfNotNull(item, "text", c.text());
      putIfNotNull(item, "uri", c.uri());
      putIfNotNull(item, "mimeType", c.mimeType());
      putIfNotNull(item, "name", c.name());
    }
    set(o, "structuredContent", r.structuredContent());
    o.put("isError", r.isError());
    if (r.table() != null) {
      ObjectNode table = o.putObject("table");
      ArrayNode columns = table.putArray("columns");
      for (ToolResult.Column col : r.table().columns()) {
        ObjectNode c = columns.addObject();
        c.put("name", col.name());
        putIfNotNull(c, "type", col.type());
        putIfNotNull(c, "sourceSchema", col.sourceSchema());
        putIfNotNull(c, "sourceTable", col.sourceTable());
        putIfNotNull(c, "sourceColumn", col.sourceColumn());
      }
      table.set("rows", r.table().rows() == null ? GppJson.NODES.arrayNode() : r.table().rows().deepCopy());
      putIfNotNull(table, "nextCursor", r.table().nextCursor());
    }
    o.put("truncated", r.truncated());
    putIfNotNull(o, "truncatedReason", r.truncatedReason());
    strings(o.putArray("maskPaths"), r.maskPaths());
    set(o, "meta", r.meta());
    return o;
  }

  public static ToolResult result(JsonNode n) {
    List<ToolResult.Content> content = new ArrayList<>();
    for (JsonNode c : n.path("content")) {
      content.add(new ToolResult.Content(c.path("type").asString("text"), text(c.get("text")), text(c.get("uri")),
          text(c.get("mimeType")), text(c.get("name"))));
    }
    ToolResult.Table table = null;
    JsonNode t = n.get("table");
    if (t != null && t.isObject()) {
      List<ToolResult.Column> columns = new ArrayList<>();
      for (JsonNode c : t.path("columns")) {
        columns.add(new ToolResult.Column(c.path("name").asString(), text(c.get("type")), text(c.get("sourceSchema")),
            text(c.get("sourceTable")), text(c.get("sourceColumn"))));
      }
      ArrayNode rows = t.path("rows").isArray() ? (ArrayNode) t.get("rows").deepCopy() : GppJson.NODES.arrayNode();
      table = new ToolResult.Table(List.copyOf(columns), rows, text(t.get("nextCursor")));
    }
    return new ToolResult(List.copyOf(content), tree(n.get("structuredContent")), n.path("isError").asBoolean(false),
        table, n.path("truncated").asBoolean(false), text(n.get("truncatedReason")), strings(n.path("maskPaths")), 0,
        0, tree(n.get("meta")));
  }

  // ── Classification and actions ──────────────────────────────────────────────────────────────────────────────

  public static ObjectNode classification(Classification c) {
    ObjectNode o = GppJson.object();
    o.put("kind", c.kind().name());
    putIfNotNull(o, "ruleId", c.ruleId());
    putIfNotNull(o, "reason", c.reason());
    putIfNotNull(o, "hint", c.hint());
    set(o, "facts", c.facts());
    return o;
  }

  public static Classification classification(JsonNode n) {
    return new Classification(Classification.Kind.valueOf(n.path("kind").asString()), text(n.get("ruleId")),
        text(n.get("reason")), text(n.get("hint")), tree(n.get("facts")));
  }

  public static ObjectNode action(ActionPreview a) {
    ObjectNode o = GppJson.object();
    o.put("kind", a.kind().name());
    putIfNotNull(o, "language", a.language());
    putIfNotNull(o, "text", a.text());
    putIfNotNull(o, "target", a.target());
    putIfNotNull(o, "summary", a.summary());
    fields(o.putArray("fields"), a.fields());
    fields(o.putArray("impact"), a.impact());
    strings(o.putArray("warnings"), a.warnings());
    ObjectNode facets = o.putObject("auditFacets");
    a.auditFacets().forEach(facets::put);
    set(o, "payload", a.payload());
    set(o, "extra", a.extra());
    return o;
  }

  public static ActionPreview action(JsonNode n) {
    Map<String, String> facets = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : n.path("auditFacets").properties()) {
      facets.put(e.getKey(), e.getValue().asString());
    }
    return new ActionPreview(ActionPreview.Kind.valueOf(n.path("kind").asString("TEXT")), text(n.get("language")),
        text(n.get("text")), text(n.get("target")), text(n.get("summary")), fields(n.path("fields")),
        fields(n.path("impact")), strings(n.path("warnings")), Map.copyOf(facets), tree(n.get("payload")),
        tree(n.get("extra")));
  }

  // ── Tests and health ────────────────────────────────────────────────────────────────────────────────────────

  public static ObjectNode test(TestResult r) {
    ObjectNode o = GppJson.object();
    o.put("ok", r.ok());
    o.put("latencyMs", r.latencyMs());
    putIfNotNull(o, "serverVersion", r.serverVersion());
    putIfNotNull(o, "identity", r.identity());
    if (r.permissions() != null) {
      ObjectNode p = o.putObject("permissions");
      p.put("level", r.permissions().level() == null ? null : r.permissions().level().name());
      putIfNotNull(p, "read", r.permissions().read());
      putIfNotNull(p, "write", r.permissions().write());
      strings(p.putArray("admin"), r.permissions().admin() == null ? List.of() : r.permissions().admin());
      set(p, "details", r.permissions().details());
    }
    ArrayNode hints = o.putArray("hints");
    for (TestResult.Hint h : r.hints()) {
      ObjectNode item = hints.addObject();
      item.put("severity", h.severity().name());
      putIfNotNull(item, "code", h.code());
      putIfNotNull(item, "field", h.field());
      item.put("text", h.text());
    }
    ArrayNode checks = o.putArray("checks");
    for (TestResult.Check c : r.checks()) {
      ObjectNode item = checks.addObject();
      item.put("name", c.name());
      item.put("ok", c.ok());
      item.put("ms", c.ms());
      putIfNotNull(item, "detail", c.detail());
    }
    if (r.error() != null) {
      ObjectNode e = o.putObject("error");
      putIfNotNull(e, "code", r.error().code());
      putIfNotNull(e, "message", r.error().message());
      putIfNotNull(e, "technical", r.error().technical());
    }
    if (r.needsConfirmation() != null) {
      ObjectNode c = o.putObject("needsConfirmation");
      c.put("kind", r.needsConfirmation().kind());
      putIfNotNull(c, "text", r.needsConfirmation().text());
      set(c, "data", r.needsConfirmation().data());
      set(c, "configPatch", r.needsConfirmation().configPatch());
    }
    set(o, "metadataUpdate", r.metadataUpdate());
    set(o, "suggestedPolicyChange", r.suggestedPolicyChange());
    set(o, "details", r.details());
    return o;
  }

  public static TestResult test(JsonNode n) {
    TestResult.Permissions permissions = null;
    JsonNode p = n.get("permissions");
    if (p != null && p.isObject()) {
      String level = text(p.get("level"));
      permissions = new TestResult.Permissions(level == null ? null : TestResult.Level.valueOf(level),
          text(p.get("read")), text(p.get("write")), strings(p.path("admin")), tree(p.get("details")));
    }
    List<TestResult.Hint> hints = new ArrayList<>();
    for (JsonNode h : n.path("hints")) {
      hints.add(new TestResult.Hint(TestResult.Severity.valueOf(h.path("severity").asString("INFO")),
          text(h.get("code")), text(h.get("field")), h.path("text").asString("")));
    }
    List<TestResult.Check> checks = new ArrayList<>();
    for (JsonNode c : n.path("checks")) {
      checks.add(new TestResult.Check(c.path("name").asString(), c.path("ok").asBoolean(false),
          c.path("ms").asLong(0), text(c.get("detail"))));
    }
    TestResult.TestError error = null;
    JsonNode e = n.get("error");
    if (e != null && e.isObject()) {
      error = new TestResult.TestError(text(e.get("code")), text(e.get("message")), text(e.get("technical")));
    }
    TestResult.Confirmation confirmation = null;
    JsonNode c = n.get("needsConfirmation");
    if (c != null && c.isObject()) {
      confirmation = new TestResult.Confirmation(c.path("kind").asString(), text(c.get("text")), tree(c.get("data")),
          tree(c.get("configPatch")));
    }
    return new TestResult(n.path("ok").asBoolean(false), n.path("latencyMs").asLong(0), text(n.get("serverVersion")),
        text(n.get("identity")), permissions, List.copyOf(hints), List.copyOf(checks), error, confirmation,
        tree(n.get("metadataUpdate")), tree(n.get("suggestedPolicyChange")), tree(n.get("details")));
  }

  public static ObjectNode health(HealthResult h) {
    ObjectNode o = GppJson.object();
    o.put("state", h.state().name());
    o.put("latencyMs", h.latencyMs());
    putIfNotNull(o, "message", h.message());
    return o;
  }

  public static HealthResult health(JsonNode n) {
    return new HealthResult(HealthResult.State.valueOf(n.path("state").asString("UNREACHABLE")),
        n.path("latencyMs").asLong(0), text(n.get("message")));
  }

  // ── Calls, connections, limits, policies ────────────────────────────────────────────────────────────────────

  /**
   * A connection as a plugin sees it: the non-secret config, the names of its secret fields and a hash of the config
   * the plugin may key its pools on ({@code id + configHash}).
   */
  public static ObjectNode connection(ConnectionConfig c) {
    ObjectNode o = GppJson.object();
    o.put("id", c.id());
    o.put("name", c.name());
    o.put("type", c.type());
    o.put("env", c.env().id());
    o.put("version", c.version());
    o.put("configHash", "sha256:" + Canonical.sha256Hex(c.config()));
    set(o, "config", c.config());
    strings(o.putArray("secretFields"), c.secretFields().stream().sorted().toList());
    set(o, "metadata", c.metadata());
    return o;
  }

  public static ConnectionConfig connection(JsonNode n) {
    Set<String> secretFields = new LinkedHashSet<>(strings(n.path("secretFields")));
    JsonNode config = n.get("config");
    return new ConnectionConfig(n.path("id").asString(), n.path("name").asString(), n.path("type").asString(),
        Env.of(n.path("env").asString("dev")), n.path("version").asLong(0),
        config == null || config.isNull() ? GppJson.object() : config.deepCopy(), Set.copyOf(secretFields),
        tree(n.get("metadata")));
  }

  public static ObjectNode call(ToolCall c) {
    ObjectNode o = GppJson.object();
    o.put("callId", c.callId());
    o.put("tool", c.tool());
    putIfNotNull(o, "connection", c.connection());
    o.set("arguments", c.arguments() == null ? GppJson.object() : c.arguments().deepCopy());
    if (c.template() != null) {
      ObjectNode t = o.putObject("template");
      t.put("templateId", c.template().templateId());
      t.put("version", c.template().version());
      putIfNotNull(t, "opKey", c.template().opKey());
      set(t, "binding", c.template().binding());
    }
    return o;
  }

  public static ToolCall call(JsonNode n) {
    JsonNode args = n.get("arguments");
    ToolCall.TemplateBinding template = null;
    JsonNode t = n.get("template");
    if (t != null && t.isObject()) {
      template = new ToolCall.TemplateBinding(t.path("templateId").asString(), t.path("version").asInt(),
          text(t.get("opKey")), tree(t.get("binding")));
    }
    return new ToolCall(n.path("callId").asString(), n.path("tool").asString(), text(n.get("connection")),
        args instanceof ObjectNode obj ? obj.deepCopy() : GppJson.object(), template);
  }

  public static ObjectNode limits(Limits l) {
    ObjectNode o = GppJson.object();
    o.put("maxRows", l.maxRows());
    o.put("maxLines", l.maxLines());
    o.put("maxResponseBytes", l.maxResponseBytes());
    o.put("maxRequestBytes", l.maxRequestBytes());
    o.put("maxOutputBytes", l.maxOutputBytes());
    o.put("maxStderrBytes", l.maxStderrBytes());
    o.put("maxCellChars", l.maxCellChars());
    o.put("statementTimeoutMs", millis(l.statementTimeout()));
    o.put("commandTimeoutMs", millis(l.commandTimeout()));
    o.put("toolTimeoutMs", millis(l.toolTimeout()));
    o.put("maxRangeMs", millis(l.maxRange()));
    return o;
  }

  public static Limits limits(JsonNode n) {
    return new Limits(n.path("maxRows").asInt(1000), n.path("maxLines").asInt(1000),
        n.path("maxResponseBytes").asLong(512 * 1024), n.path("maxRequestBytes").asLong(1024 * 1024),
        n.path("maxOutputBytes").asLong(256 * 1024), n.path("maxStderrBytes").asLong(64 * 1024),
        n.path("maxCellChars").asInt(8192), duration(n.get("statementTimeoutMs"), 5_000),
        duration(n.get("commandTimeoutMs"), 30_000), duration(n.get("toolTimeoutMs"), 10_000),
        duration(n.get("maxRangeMs"), 168L * 3_600_000));
  }

  public static ObjectNode policy(PolicyView p) {
    ObjectNode o = GppJson.object();
    o.put("env", p.env().id());
    putIfNotNull(o, "rulePrefix", p.rulePrefix());
    o.set("section", p.section().deepCopy());
    if (p.limits() != null) {
      o.set("limits", limits(p.limits()));
    }
    return o;
  }

  public static PolicyView policy(JsonNode n) {
    JsonNode limits = n.get("limits");
    return new PolicyView(Env.of(n.path("env").asString("dev")), text(n.get("rulePrefix")), tree(n.get("section")),
        limits == null || limits.isNull() ? null : limits(limits));
  }

  public static ObjectNode endpoint(EndpointView v) {
    ObjectNode o = GppJson.object();
    o.put("slug", v.slug());
    o.put("env", v.env().id());
    ArrayNode connections = o.putArray("connections");
    v.connections().forEach(c -> connections.add(connection(c)));
    ObjectNode limits = o.putObject("limitsByConnection");
    v.limitsByConnection().forEach((id, l) -> limits.set(id, limits(l)));
    ArrayNode contributions = o.putArray("contributions");
    for (EndpointView.Contribution c : v.contributions()) {
      ObjectNode item = contributions.addObject();
      item.put("connectionId", c.connectionId());
      item.put("templateId", c.templateId());
      item.put("version", c.version());
      set(item, "value", c.value());
    }
    return o;
  }

  public static EndpointView endpoint(JsonNode n) {
    List<ConnectionConfig> connections = new ArrayList<>();
    for (JsonNode c : n.path("connections")) {
      connections.add(connection(c));
    }
    Map<String, Limits> limits = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : n.path("limitsByConnection").properties()) {
      limits.put(e.getKey(), limits(e.getValue()));
    }
    List<EndpointView.Contribution> contributions = new ArrayList<>();
    for (JsonNode c : n.path("contributions")) {
      contributions.add(new EndpointView.Contribution(c.path("connectionId").asString(),
          c.path("templateId").asString(), c.path("version").asInt(), tree(c.get("value"))));
    }
    return new EndpointView(n.path("slug").asString(), Env.of(n.path("env").asString("dev")),
        List.copyOf(connections), Map.copyOf(limits), List.copyOf(contributions));
  }

  // ── Egress ──────────────────────────────────────────────────────────────────────────────────────────────────

  public static ObjectNode egressRequest(ExecContext.EgressRequest r) {
    ObjectNode o = GppJson.object();
    o.put("method", r.method());
    o.put("uri", r.uri().toString());
    headers(o.putObject("headers"), r.headers());
    if (r.body() != null) {
      o.put("body", Base64.getEncoder().encodeToString(r.body()));
    }
    if (r.timeout() != null) {
      o.put("timeoutMs", r.timeout().toMillis());
    }
    return o;
  }

  public static ExecContext.EgressRequest egressRequest(JsonNode n) {
    JsonNode body = n.get("body");
    JsonNode timeout = n.get("timeoutMs");
    return new ExecContext.EgressRequest(n.path("method").asString("GET"), URI.create(n.path("uri").asString()),
        headers(n.path("headers")), body == null || body.isNull() ? null : Base64.getDecoder().decode(body.asString()),
        timeout == null || timeout.isNull() ? null : Duration.ofMillis(timeout.asLong()));
  }

  public static ObjectNode egressResponse(ExecContext.EgressResponse r) {
    ObjectNode o = GppJson.object();
    o.put("status", r.status());
    headers(o.putObject("headers"), r.headers());
    o.put("body", Base64.getEncoder().encodeToString(r.body() == null ? new byte[0] : r.body()));
    o.put("truncated", r.truncated());
    return o;
  }

  public static ExecContext.EgressResponse egressResponse(JsonNode n) {
    return new ExecContext.EgressResponse(n.path("status").asInt(), headers(n.path("headers")),
        Base64.getDecoder().decode(n.path("body").asString("")), n.path("truncated").asBoolean(false));
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static void headers(ObjectNode out, Map<String, List<String>> headers) {
    if (headers == null) {
      return;
    }
    headers.forEach((k, values) -> strings(out.putArray(k), values));
  }

  private static Map<String, List<String>> headers(JsonNode n) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : n.properties()) {
      out.put(e.getKey(), strings(e.getValue()));
    }
    return Map.copyOf(out);
  }

  private static void fields(ArrayNode out, List<ActionPreview.Field> fields) {
    for (ActionPreview.Field f : fields) {
      ObjectNode item = out.addObject();
      item.put("label", f.label());
      item.put("value", f.value());
    }
  }

  private static List<ActionPreview.Field> fields(JsonNode n) {
    List<ActionPreview.Field> out = new ArrayList<>();
    for (JsonNode f : n) {
      out.add(new ActionPreview.Field(f.path("label").asString(), text(f.get("value"))));
    }
    return List.copyOf(out);
  }

  static void strings(ArrayNode out, List<String> values) {
    if (values != null) {
      values.forEach(out::add);
    }
  }

  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array != null) {
      for (JsonNode v : array) {
        out.add(v.asString());
      }
    }
    return List.copyOf(out);
  }

  private static void set(ObjectNode o, String name, JsonNode value) {
    if (value != null && !value.isMissingNode()) {
      o.set(name, value.deepCopy());
    }
  }

  private static void putIfNotNull(ObjectNode o, String name, String value) {
    if (value != null) {
      o.put(name, value);
    }
  }

  static String text(JsonNode n) {
    return n == null || n.isNull() || n.isMissingNode() ? null : n.asString();
  }

  static JsonNode tree(JsonNode n) {
    return n == null || n instanceof NullNode || n.isMissingNode() ? null : n.deepCopy();
  }

  private static Boolean bool(JsonNode n) {
    return n == null || n.isNull() ? null : n.asBoolean();
  }

  private static long millis(Duration d) {
    return d == null ? 0 : d.toMillis();
  }

  private static Duration duration(JsonNode n, long dflt) {
    return Duration.ofMillis(n == null || n.isNull() ? dflt : n.asLong(dflt));
  }
}
