# Connector SPI design reference

This interface sketch preserves the architectural contract used to develop the connectors. The current compilable definitions in [connector-api](../connector-api/src/main/java/ai/msrv/gaterook/connector/) are authoritative if signatures differ. See [architecture](ARCHITECTURE.md#3-connector-spi) for execution and security boundaries.

```java
// ── Connector.java ─────────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.util.*; import tools.jackson.databind.JsonNode;
/** One resource type and its tools. Same jar in the native core (built-ins, direct calls) and in the plugin host
 *  (plugins, GPP/1 via the core's RemoteConnector). Blocking, virtual-thread friendly, reacts to interrupt;
 *  no own thread pools, no System.exit/setProperty, no JVM-wide defaults; files only under ctx.workDir().
 *  Knows nothing about tokens, clients, endpoints or people. */
public interface Connector extends AutoCloseable {
  ConnectorManifest manifest();
  /** Tools as published on one endpoint: enum of `connection`, effective limits in descriptions. */
  default List<ToolDefinition> tools(EndpointView endpoint) { return manifest().tools(); }
  /** No I/O: filters tool/connection pairs for built-in backends; classification still enforces applicability. */
  default boolean supportsTool(String tool, ConnectionConfig cfg) { return true; }
  /** No I/O, no secrets, a few ms; runs before the policy. MUTATION requires approval unless the pair explicitly uses Bypass. */
  Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy);
  /** Exact action, byte for byte what will run; no I/O; hashed into the approval digest. */
  ActionPreview describeAction(ToolCall call, ConnectionConfig cfg);
  /** Optional I/O before the request exists (dry-run, diff, impact), budget ctx.deadline() = 5 s. */
  default PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException { return PreparedAction.approval(describeAction(call, cfg), null); }
  /** Persists nothing; may stop with needsConfirmation (host key, certificate, exec plugin). */
  TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx);
  default HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    TestResult t = test(cfg, secrets, ctx);
    return t.ok() ? HealthResult.healthy(t.latencyMs()) : HealthResult.unreachable(t.error() == null ? "Unreachable" : t.error().message());
  }
  /** READ, or MUTATION only when ctx.approved(): refuse otherwise (defence in depth). */
  ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) throws ConnectorException;
  /** Contribution to describe_connection (≤ 2 s); `object` narrows it to a table, a collection, a log group. */
  default Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException { return Optional.empty(); }
  default List<ResourceDescriptor> resources(ConnectionConfig cfg) { return List.of(); }
  default ToolResult readResource(String uri, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException { throw ConnectorException.badInput("No resource " + uri); }
  /** No-code template kinds: "sql-query" (SQL), "ssh-runbook" (SSH), "openapi" (HTTP). */
  default List<TemplateKind> templateKinds() { return List.of(); }
  default int maxConcurrency() { return 4; }                       // semaphore bulkhead per connection
  /** The tool runs a command bounded by Limits.commandTimeout (ssh_run, ssh_exec): its deadline is
   *  Limits.commandCallTimeout, not the tool timeout (review C4 2026-10-26; in-process only, GPP/1 unchanged). */
  default boolean runsCommand(String tool) { return false; }
  default void onConnectionChanged(String connectionId) {}         // config, secret version or policy changed: drop pools
  default void onConnectionRemoved(String connectionId) {}  @Override default void close() {}
}
// ── ConnectorManifest.java ─────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.util.*; import tools.jackson.databind.JsonNode;
/** Catalog card + connection form + Policies section. Stored in the DB for plugins, so tools/list never starts a host.
 *  Plugins use builder(): the canonical constructor may grow. */
public record ConnectorManifest(String id /* "postgres" */, String version, Kind kind, String name, String icon /* Lucide */,
    String group /* Databases | Remote access | Logs | Kubernetes | Cloud | HTTP and MCP */, String description,
    JsonNode configSchema /* 2020-12 + x-…, writeOnly = secret */, JsonNode policySchema /* or null */,
    List<ToolDefinition> tools, RequiredAccess access /* "Needs access to" */, boolean dynamicTools /* MCP servers */,
    String connectorApi) {
  public enum Kind { BUILT_IN, PLUGIN, MCP_SERVER }
  public record RequiredAccess(List<String> hosts, List<String> commands, List<String> files) {
    public static RequiredAccess none() { return new RequiredAccess(List.of(), List.of(), List.of()); }
  }
  public static Builder builder(String id, String version, Kind kind) { return new Builder(id, version, kind); }
  public static final class Builder {
    private final String id, version; private final Kind kind;
    private String name, icon = "puzzle", group = "HTTP and MCP", description = "";
    private JsonNode configSchema, policySchema; private List<ToolDefinition> tools = List.of();
    private RequiredAccess access = RequiredAccess.none(); private boolean dynamicTools;
    private Builder(String id, String version, Kind kind) { this.id = id; this.version = version; this.kind = kind; this.name = id; }
    public Builder name(String v) { name = v; return this; }              public Builder icon(String v) { icon = v; return this; }
    public Builder group(String v) { group = v; return this; }            public Builder description(String v) { description = v; return this; }
    public Builder configSchema(JsonNode v) { configSchema = v; return this; }
    public Builder policySchema(JsonNode v) { policySchema = v; return this; }
    public Builder tools(List<ToolDefinition> v) { tools = List.copyOf(v); return this; }
    public Builder access(RequiredAccess v) { access = v; return this; } public Builder dynamicTools(boolean v) { dynamicTools = v; return this; }
    public ConnectorManifest build() {
      return new ConnectorManifest(id, version, kind, name, icon, group, description, configSchema, policySchema, tools, access, dynamicTools, ApiVersion.CURRENT);
    }
  }
}
// ── ToolDefinition.java ────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import tools.jackson.databind.JsonNode;
/** `connection` in inputSchema is a placeholder: the core fills its enum per endpoint. outputSchema only for pure READ. */
public record ToolDefinition(String name /* ^[a-z][a-z0-9_]{0,63}$ */, String title, String description,
    JsonNode inputSchema, JsonNode outputSchema, Annotations annotations /* always explicit */, Nature nature,
    Mode defaultMode /* dev/stage endpoints */, Mode prodDefaultMode, ResultShape resultShape, Risk risk) {
  public enum Nature { READ, MUTATION, MIXED }          // MIXED: classify() decides per call (ssh_run, http_request)
  public enum Mode { OFF, READ_ONLY, APPROVAL, BYPASS } // Bypass skips approval, not policy denials or limits
  public enum ResultShape { TABLE, JSON, TEXT, LOGS }
  public enum Egress { NONE, LIMITED, CHANNEL }         // risk callout; LIMITED = fixed runbooks, enum/int/bool params only
  public record Annotations(Boolean readOnlyHint, Boolean destructiveHint, Boolean idempotentHint, Boolean openWorldHint) {
    public static Annotations read() { return new Annotations(true, false, true, false); }
    public static Annotations write(boolean destructive) { return new Annotations(false, destructive, false, false); }
  }
  public record Risk(boolean privateData, boolean untrustedContent, Egress egress) {}
  public boolean mutating() { return nature != Nature.READ; }
  /** Read only is offered, else natively disabled with a reason. */
  public boolean readOnlyModeAllowed() { return nature != Nature.MUTATION && !Boolean.TRUE.equals(annotations.destructiveHint()); }
  public static Builder builder(String name) { return new Builder(name); }
  public static final class Builder {
    private final String name; private String title, description = ""; private JsonNode inputSchema, outputSchema;
    private Annotations annotations = Annotations.read(); private Nature nature = Nature.READ;
    private Mode defaultMode = Mode.READ_ONLY, prodDefaultMode = Mode.READ_ONLY; private ResultShape resultShape = ResultShape.JSON;
    private Risk risk = new Risk(true, true, Egress.NONE);
    private Builder(String name) { this.name = name; this.title = name; }
    public Builder title(String v) { title = v; return this; }            public Builder description(String v) { description = v; return this; }
    public Builder inputSchema(JsonNode v) { inputSchema = v; return this; } public Builder outputSchema(JsonNode v) { outputSchema = v; return this; }
    public Builder annotations(Annotations v) { annotations = v; return this; } public Builder nature(Nature v) { nature = v; return this; }
    public Builder defaultMode(Mode dev, Mode prod) { defaultMode = dev; prodDefaultMode = prod; return this; }
    public Builder resultShape(ResultShape v) { resultShape = v; return this; } public Builder risk(Risk v) { risk = v; return this; }
    public ToolDefinition build() {
      if (nature != Nature.READ && outputSchema != null) throw new IllegalStateException(name + ": outputSchema only for READ tools");
      if (nature == Nature.MUTATION && (defaultMode == Mode.READ_ONLY || prodDefaultMode == Mode.READ_ONLY))
        throw new IllegalStateException(name + ": a mutating tool can't default to Read only");
      return new ToolDefinition(name, title, description, inputSchema, outputSchema, annotations, nature, defaultMode, prodDefaultMode, resultShape, risk);
    }
  }
}
// ── ToolCall.java ──────────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import tools.jackson.databind.JsonNode; import tools.jackson.databind.node.ObjectNode;
/** One call after schema validation; the core removed `connection` from the arguments and resolved it. */
public record ToolCall(String callId, String tool, String connection, ObjectNode arguments, TemplateBinding template) {
  public record TemplateBinding(String templateId, int version, String opKey, JsonNode binding) {}  // version is in the digest
  public String string(String field) { JsonNode n = arguments.get(field); return n == null || n.isNull() ? null : n.asString(); }
  public int integer(String field, int dflt) { JsonNode n = arguments.get(field); return n == null || n.isNull() ? dflt : n.asInt(dflt); }
}
// ── Classification.java ────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import tools.jackson.databind.JsonNode;
/** DENY carries the rule id shown in Activity ("prod.sql.block_ddl") and a text for the agent; facts are non-secret. */
public record Classification(Kind kind, String ruleId, String reason, String hint, JsonNode facts) {
  public enum Kind { READ, MUTATION, DENY }
  public static Classification read() { return new Classification(Kind.READ, null, null, null, null); }
  public static Classification mutation(String reason) { return new Classification(Kind.MUTATION, null, reason, null, null); }
  public static Classification mutation(String reason, JsonNode facts) { return new Classification(Kind.MUTATION, null, reason, null, facts); }
  public static Classification deny(String ruleId, String reason, String hint) { return new Classification(Kind.DENY, ruleId, reason, hint, null); }
}
// ── PolicyView.java ────────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.util.*; import tools.jackson.databind.JsonNode; import tools.jackson.databind.node.JsonNodeFactory;
/** The connector's policy section merged by the core (environment → connection → endpoint → tool, only stricter wins). */
public record PolicyView(Env env, String rulePrefix /* "prod.sql" */, JsonNode section, Limits limits) {
  public PolicyView { if (section == null) section = JsonNodeFactory.instance.objectNode(); }
  public String rule(String id) { return rulePrefix + "." + id; }
  public boolean flag(String key, boolean dflt) { JsonNode n = section.get(key); return n == null || n.isNull() ? dflt : n.asBoolean(dflt); }
  public int integer(String key, int dflt) { JsonNode n = section.get(key); return n == null || n.isNull() ? dflt : n.asInt(dflt); }
  public List<String> strings(String key) {
    List<String> out = new ArrayList<>(); JsonNode n = section.get(key);
    if (n != null) for (JsonNode v : n) out.add(v.asString());
    return out;
  }
}
// ── Env.java ───────────────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api;
public enum Env {
  DEV, STAGE, PROD;
  public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
  public static Env of(String id) { return valueOf(id.toUpperCase(java.util.Locale.ROOT)); }
}
// ── Limits.java ────────────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.time.Duration;
/** Effective limits of one call = min(environment policy, connection, endpoint, tool override), computed by the core. */
public record Limits(int maxRows /* dev 1,000 · prod 100 */, int maxLines /* logs: 1,000 · 200 */, long maxResponseBytes /* 512 KB */,
    long maxRequestBytes, long maxOutputBytes /* ssh stdout 256 KB */, long maxStderrBytes /* 64 KB */, int maxCellChars /* 8 KB */,
    Duration statementTimeout /* 5 s · 3 s */, Duration commandTimeout /* 30 s */, Duration toolTimeout /* 10 s */,
    Duration maxRange /* logs/metrics: 168 h · 24 h */) {
  // + COMMAND_STOP_MARGIN 2.5 s, MAX_COMMAND_CALL 55 s, MAX_APPROVED_COMMAND_CALL 10 min and
  //   commandCallTimeout(boolean approved) = max(toolTimeout, min(commandTimeout + 2.5 s, approved ? 10 min : 55 s))
}
// ── ActionPreview.java ─────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.util.*; import tools.jackson.databind.JsonNode;
/** What the approver sees. `text` is exactly what runs (SQL as sent, wire command of a runbook, HTTP request with ‹masked›
 *  auth headers, unified diff): never a paraphrase, never normalized; the UI escapes invisible/confusable characters.
 *  `payload` = canonical request stored with the approval and executed after it (ExecContext.approvedPayload()). */
public record ActionPreview(Kind kind, String language /* sql|sh|json|yaml|http|diff|text */, String text,
    String target /* "dev-db · db.dev.internal:5432/app" */, String summary /* ≤ 120 chars, for channels */,
    List<Field> fields, List<Field> impact, List<String> warnings, Map<String, String> auditFacets, JsonNode payload, JsonNode extra) {
  public enum Kind { SQL, COMMAND, HTTP, DIFF, RUNBOOK, JSON, TEXT }
  public record Field(String label, String value) {}
  public static ActionPreview of(Kind kind, String language, String text, String target, String summary) {
    return new ActionPreview(kind, language, text, target, summary, List.of(), List.of(), List.of(), Map.of(), null, null);
  }
  public ActionPreview withFields(List<Field> v) { return new ActionPreview(kind, language, text, target, summary, List.copyOf(v), impact, warnings, auditFacets, payload, extra); }
  public ActionPreview withImpact(List<Field> v) { return new ActionPreview(kind, language, text, target, summary, fields, List.copyOf(v), warnings, auditFacets, payload, extra); }
  public ActionPreview withWarnings(List<String> v) { return new ActionPreview(kind, language, text, target, summary, fields, impact, List.copyOf(v), auditFacets, payload, extra); }
  public ActionPreview withFacets(Map<String, String> v) { return new ActionPreview(kind, language, text, target, summary, fields, impact, warnings, Map.copyOf(v), payload, extra); }
  public ActionPreview withPayload(JsonNode v) { return new ActionPreview(kind, language, text, target, summary, fields, impact, warnings, auditFacets, v, extra); }
}
// ── PreparedAction.java ────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import tools.jackson.databind.JsonNode;
/** APPROVAL: exact action + opaque state kept in the request and passed to execute() (k8s rv + sha256 of the dry-run);
 *  REJECT: error for the agent, no request (conflict, validation, paused); NO_CHANGE: "unchanged", no request. */
public record PreparedAction(Outcome outcome, ActionPreview preview, JsonNode preparedState, String message) {
  public enum Outcome { APPROVAL, REJECT, NO_CHANGE }
  public static PreparedAction approval(ActionPreview p, JsonNode state) { return new PreparedAction(Outcome.APPROVAL, p, state, null); }
  public static PreparedAction reject(String messageForAgent) { return new PreparedAction(Outcome.REJECT, null, null, messageForAgent); }
  public static PreparedAction noChange(String messageForAgent) { return new PreparedAction(Outcome.NO_CHANGE, null, null, messageForAgent); }
}
// ── TestResult.java ────────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.util.*; import tools.jackson.databind.JsonNode;
/** Latency, version, identity, what the account may really do, hints in the kit's tone. needsConfirmation stops the test
 *  before credentials leave; the core applies configPatch (Setup changes) after "Trust" and runs the test again. */
public record TestResult(boolean ok, long latencyMs, String serverVersion, String identity, Permissions permissions,
    List<Hint> hints, List<Check> checks, TestError error, Confirmation needsConfirmation,
    JsonNode metadataUpdate /* non-secret snapshot for classify(): k8s discovery, kubeconfig contexts */,
    JsonNode suggestedPolicyChange /* {"kind":"allow-private","cidr":"10.30.4.12/32"} */, JsonNode details) {
  public enum Severity { INFO, WARNING, DANGER, ERROR }   public enum Level { READ, WRITE, ADMIN, UNKNOWN }
  public record Permissions(Level level, String read, String write, List<String> admin, JsonNode details) {}
  public record Hint(Severity severity, String code, String field, String text) {}
  public record Check(String name, boolean ok, long ms, String detail) {}
  public record TestError(String code, String message /* what happened + what to do */, String technical) {}
  public record Confirmation(String kind /* host-key | tls-cert | exec-plugin */, String text, JsonNode data, JsonNode configPatch) {}
  public static TestResult success(long latencyMs, String serverVersion, String identity, Permissions p) {
    return new TestResult(true, latencyMs, serverVersion, identity, p, List.of(), List.of(), null, null, null, null, null);
  }
  public static TestResult failure(String code, String message, String technical) {
    return new TestResult(false, 0, null, null, null, List.of(), List.of(), new TestError(code, message, technical), null, null, null, null);
  }
  public static TestResult confirm(Confirmation c, List<Check> checksSoFar) {
    return new TestResult(false, 0, null, null, null, List.of(), List.copyOf(checksSoFar), null, c, null, null, null);
  }
  public TestResult withHint(Hint h) {
    List<Hint> l = new ArrayList<>(hints); l.add(h);
    return new TestResult(ok, latencyMs, serverVersion, identity, permissions, List.copyOf(l), checks, error, needsConfirmation, metadataUpdate, suggestedPolicyChange, details);
  }
  public TestResult withChecks(List<Check> v) { return new TestResult(ok, latencyMs, serverVersion, identity, permissions, hints, List.copyOf(v), error, needsConfirmation, metadataUpdate, suggestedPolicyChange, details); }
  public TestResult withMetadata(JsonNode v) { return new TestResult(ok, latencyMs, serverVersion, identity, permissions, hints, checks, error, needsConfirmation, v, suggestedPolicyChange, details); }
  public TestResult withSuggestedPolicyChange(JsonNode v) { return new TestResult(ok, latencyMs, serverVersion, identity, permissions, hints, checks, error, needsConfirmation, metadataUpdate, v, details); }
  public TestResult withDetails(JsonNode v) { return new TestResult(ok, latencyMs, serverVersion, identity, permissions, hints, checks, error, needsConfirmation, metadataUpdate, suggestedPolicyChange, v); }
}
// ── HealthResult.java ──────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api;
public record HealthResult(State state, long latencyMs, String message) {                    // "Healthy · 42 ms", "Slow · 2.4 s"
  public enum State { HEALTHY, DEGRADED, UNREACHABLE }
  public static HealthResult healthy(long ms) { return new HealthResult(ms > 1_000 ? State.DEGRADED : State.HEALTHY, ms, null); }
  public static HealthResult unreachable(String message) { return new HealthResult(State.UNREACHABLE, 0, message); }
}
// ── ConnectionConfig.java ──────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.util.*; import tools.jackson.databind.JsonNode;
/** Non-secret config. Secret fields (writeOnly) are only named here (JSON pointers); values live behind SecretResolver.
 *  `version` changes on every edit, secret rotation and policy change: (id, version) is a safe pool key. */
public record ConnectionConfig(String id, String name, String type, Env env, long version, JsonNode config,
    Set<String> secretFields, JsonNode metadata) {
  public String string(String ptr) { JsonNode n = config.at(ptr); return n.isMissingNode() || n.isNull() ? null : n.asString(); }
  public int integer(String ptr, int dflt) { JsonNode n = config.at(ptr); return n.isMissingNode() || n.isNull() ? dflt : n.asInt(dflt); }
  public boolean bool(String ptr, boolean dflt) { JsonNode n = config.at(ptr); return n.isMissingNode() || n.isNull() ? dflt : n.asBoolean(dflt); }
  public List<String> strings(String ptr) { List<String> out = new ArrayList<>(); for (JsonNode v : config.at(ptr)) out.add(v.asString()); return out; }
}
// ── SecretResolver.java ────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.security.PublicKey; import java.time.*; import java.util.*;
/** Scoped to one connection and one call, closed by the core. Only writeOnly fields of this connection; every value
 *  handed out is tainted (raw, Base64, URL-encoded, JSON-escaped echoes get masked). Plugins: same API over GPP/1. */
public interface SecretResolver {
  Secret secret(String field) throws SecretUnavailableException;          // value | pem | binary
  Login login(String field) throws SecretUnavailableException;            // static login, reference or Vault dynamic
  boolean isSet(String field);
  Meta meta(String field);                                                // no value: pool keys, UI hints
  SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request) throws SecretUnavailableException; // Vault SSH CA
  /** Live views zeroed on close(); toString() is "‹secret›"; Jackson refuses to serialize it. */
  interface Secret extends AutoCloseable {
    char[] chars(); byte[] bytes();
    String unsafeString();                    // only for APIs that demand a String (Hikari, MINA, AWS SDK); still tainted
    String version(); Optional<Instant> notAfter();
    @Override void close();
  }
  record Login(String username, Secret password, String version, Instant notAfter) implements AutoCloseable {
    @Override public void close() { password.close(); }
  }
  record Meta(String source /* local | reference | dynamic */, String version, Instant notAfter) {}
  record SshCertRequest(String principal, Duration ttl, String keyId, Map<String, String> extensions) {}
  record SshCertificate(String openSshText, String serial, Instant notAfter) {}
  /** Optional connector capability: Rotate at the source (ALTER ROLE … PASSWORD); a person's action, no approval. */
  interface CredentialRotator {
    boolean canRotate(ConnectionConfig cfg, String field);
    void rotate(ConnectionConfig cfg, SecretResolver current, String field, char[] next, ExecContext ctx) throws ConnectorException;
  }
}
// ── ExecContext.java ───────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.net.URI; import java.nio.file.Path; import java.time.*; import java.util.*; import tools.jackson.databind.JsonNode;
/** Everything a call may know. No identity, no SecretStore, no master key, no other connections.
 *  Cancellation = interrupt of the virtual thread + onCancel callbacks (Statement.cancel(), cursor.close(), signal TERM). */
public interface ExecContext {
  String callId();                     // tc_…: Mongo comment, SSH cert key_id, log correlation
  String traceId(); String traceparent();
  Env env(); Limits limits();
  Duration timeout();                  // tool timeout of the policy
  Instant deadline();                  // set native timeouts of the resource ≤ deadline
  default Duration remaining() { Duration d = Duration.between(clock().instant(), deadline()); return d.isNegative() ? Duration.ZERO : d; }
  boolean cancelled(); void onCancel(Runnable action);
  boolean approved();                  // core authorization: human approval or explicit Bypass
  Optional<String> approvalId();       // absent for Bypass
  Optional<JsonNode> preparedState();  // from prepare()
  Optional<JsonNode> approvedPayload();// core-authorized ActionPreview.payload: run it, not the arguments
  System.Logger log();                 // masked; core.log or logs/plugins/<id>.log
  Clock clock();
  HandleCodec handles();
  boolean acquire(String remoteQuotaKey, int permitsPerMinute);   // shared remote quotas: gcp:<project>, aws:<acct>:<region>:<api>
  Path workDir();                      // ~/.gaterook/connectors/<type>/ ; plugins: plugin-data/<id>/
  Egress egress();                     // guarded HTTP (allowlist ∩ policy, no metadata/private ranges, no redirects)
  /** Derived credentials (GCP/STS/Vault/exec/OAuth tokens) the connector obtained itself: masked like resolver values. */
  void taint(CharSequence value);
  /** A copy of tree as the core will mask the answer (keys, formats, this call's taint, the env's PII): a filter by values
   *  the agent chose (aws_describe where) matches this, or what it keeps would spell out a masked value (C8 review).
   *  Default: the tree as it is (the plugin host). */
  default JsonNode masked(JsonNode tree) { return tree; }
  /** HMAC-signed opaque handles (cursors of logs_tail, redis_scan): the agent can't forge them. */
  interface HandleCodec { String seal(String purpose, JsonNode payload, Duration ttl); Optional<JsonNode> open(String purpose, String handle); }
  /** Neutral types: the core implements it on EgressClients (OkHttp, connect-time address check), the host on EgressHttpClient. */
  interface Egress { EgressResponse send(EgressRequest request, long maxBytes) throws ConnectorException; }
  record EgressRequest(String method, URI uri, Map<String, List<String>> headers, byte[] body, Duration timeout) {}
  record EgressResponse(int status, Map<String, List<String>> headers, byte[] body, boolean truncated) {}
}
// ── ConnectorException.java ────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api;
/** The agent gets getMessage() as isError after masking. Only RESOURCE_UNAVAILABLE and TIMEOUT count for the circuit breaker.
 *  OUTCOME_UNKNOWN: the request may have been applied (dropped during COMMIT / after the POST); never retried. */
public class ConnectorException extends Exception {
  private static final long serialVersionUID = 1L;
  public enum Kind { RESOURCE_UNAVAILABLE, TIMEOUT, BAD_INPUT, RESOURCE_ERROR, DENIED, OUTCOME_UNKNOWN, INTERNAL }
  private final Kind kind;
  public ConnectorException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
  public Kind kind() { return kind; }
  public boolean countsForCircuitBreaker() { return kind == Kind.RESOURCE_UNAVAILABLE || kind == Kind.TIMEOUT; }
  public static ConnectorException unavailable(String m, Throwable c) { return new ConnectorException(Kind.RESOURCE_UNAVAILABLE, m, c); }
  public static ConnectorException timeout(String m) { return new ConnectorException(Kind.TIMEOUT, m, null); }
  public static ConnectorException badInput(String m) { return new ConnectorException(Kind.BAD_INPUT, m, null); }
  public static ConnectorException resourceError(String m, Throwable c) { return new ConnectorException(Kind.RESOURCE_ERROR, m, c); }
  public static ConnectorException denied(String m) { return new ConnectorException(Kind.DENIED, m, null); }
  public static ConnectorException outcomeUnknown(String m, Throwable c) { return new ConnectorException(Kind.OUTCOME_UNKNOWN, m, c); }
}
// ── SecretUnavailableException.java ────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api;
public final class SecretUnavailableException extends ConnectorException {  // "…can't be resolved: AWS Secrets Manager isn't connected."
  private static final long serialVersionUID = 1L;
  public SecretUnavailableException(String message, Throwable cause) { super(Kind.RESOURCE_UNAVAILABLE, message, cause); }
}
// ── ToolResult.java ────────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.util.List; import tools.jackson.databind.JsonNode; import tools.jackson.databind.node.ArrayNode;
/** Neutral result, no MCP types. The core masks it, cuts it (whole rows/entries), renders text for TABLE/LOGS, fills
 *  maskedCount/resultBytes, writes the audit, then serializes for the call's revision. isError=false for any answer of
 *  the resource (HTTP 4xx, exit code 3): that is data. */
public record ToolResult(List<Content> content, JsonNode structuredContent, boolean isError, Table table,
    boolean truncated, String truncatedReason /* row_limit | size_limit | output_limit | timeout | partial | head */,
    List<String> maskPaths /* known-sensitive JSON paths */, int maskedCount /* core */, long resultBytes /* core */, JsonNode meta) {
  public record Content(String type /* text | resource_link */, String text, String uri, String mimeType, String name) {
    public static Content text(String text) { return new Content("text", text, null, null, null); }
  }
  public record Column(String name, String type, String sourceSchema, String sourceTable, String sourceColumn) {}
  public record Table(List<Column> columns, ArrayNode rows, String nextCursor) {}
  public static ToolResult text(String t) { return new ToolResult(List.of(Content.text(t)), null, false, null, false, null, List.of(), 0, 0, null); }
  public static ToolResult json(JsonNode s, String t) { return new ToolResult(t == null ? List.of() : List.of(Content.text(t)), s, false, null, false, null, List.of(), 0, 0, null); }
  public static ToolResult table(Table t, JsonNode s) { return new ToolResult(List.of(), s, false, t, false, null, List.of(), 0, 0, null); }
  public static ToolResult error(String m) { return new ToolResult(List.of(Content.text(m)), null, true, null, false, null, List.of(), 0, 0, null); }
  public ToolResult truncated(String reason) { return new ToolResult(content, structuredContent, isError, table, true, reason, maskPaths, maskedCount, resultBytes, meta); }
  public ToolResult maskPaths(List<String> p) { return new ToolResult(content, structuredContent, isError, table, truncated, truncatedReason, List.copyOf(p), maskedCount, resultBytes, meta); }
  public ToolResult withMeta(JsonNode m) { return new ToolResult(content, structuredContent, isError, table, truncated, truncatedReason, maskPaths, maskedCount, resultBytes, m); }
  public ToolResult accounted(List<Content> c, JsonNode s, Table t, int masked, long bytes) { // core only
    return new ToolResult(c, s, isError, t, truncated, truncatedReason, maskPaths, masked, bytes, meta);
  }
}
// ── TemplateKind.java ──────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.util.List; import tools.jackson.databind.JsonNode; import tools.jackson.databind.node.ObjectNode;
/** Storage, append-only versions and binding to a connection ("Runs on") are the core's; the kind validates and compiles. */
public interface TemplateKind {
  String kind();                                       // sql-query | ssh-runbook | openapi
  List<String> connectionTypes();
  JsonNode definitionSchema();                         // template editor form
  CompileResult compile(JsonNode definition, ConnectionConfig runsOn);   // SQL templates are classified here, on save
  ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn);  // Playground, editor; no I/O
  record CompiledTool(String opKey /* "q" | "POST /refunds" */, ToolDefinition definition, JsonNode binding) {}
  record CompileResult(List<CompiledTool> tools, List<JsonNode> contributions /* runbooks for ssh_run */,
      List<String> errors, List<String> warnings, String digest) { public boolean ok() { return errors.isEmpty(); } }
}
// ── EndpointView.java ──────────────────────────────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api; import java.util.*; import tools.jackson.databind.JsonNode;
/** What tools(…) may know about an endpoint: its connections of this type, their limits, template contributions. No tokens. */
public record EndpointView(String slug, Env env, List<ConnectionConfig> connections, Map<String, Limits> limitsByConnection,
    List<Contribution> contributions) {
  public record Contribution(String connectionId, String templateId, int version, JsonNode value) {}
}
// ── ResourceDescriptor.java / ApiVersion.java ──────────────────────────────────────────────────────────────────
package ai.msrv.gaterook.connector.api;
public record ResourceDescriptor(String uri /* mcpgw://connections/dev-db/schema */, String name, String mimeType, String description) {}
package ai.msrv.gaterook.connector.api;
public final class ApiVersion { public static final String CURRENT = "1.0.0"; private ApiVersion() {} }  // plugins: requires.connectorApi
```
