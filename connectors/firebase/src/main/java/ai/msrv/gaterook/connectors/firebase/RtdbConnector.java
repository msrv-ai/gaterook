package ai.msrv.gaterook.connectors.firebase;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.google.auth.oauth2.GoogleCredentials;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Firebase Realtime Database (docs/firebase-rtdb.md): {@code rtdb_get} reads one path; {@code rtdb_write} runs one
 * set, update or delete that a person approved.
 *
 * <p>{@link #classify} reads paths, keys and query values strictly and keeps every path to the connection's
 * top-level paths, without I/O; {@link #execute} checks the same again and runs only the approved write. The database's
 * URL is one of the hosts Google runs for it, and the connection's Google token goes nowhere else
 * ({@code CloudCredentials.sendsGoogleTokensToRealtimeDatabase}). With an OAuth token the database applies the
 * identity's IAM roles, not its security rules: those roles are the boundary behind the gateway's checks. An answer is
 * read within the call's response size and refused, with advice to narrow it, when it is larger.
 */
public final class RtdbConnector implements Connector {

  static final String TYPE = "firebase-rtdb";
  /** Bytes of the root's keys Test connection and health read at most. */
  static final long PROBE_BYTES = 64 * 1024;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final CloudCredentials cloud;
  private final CloudLocal local;
  private final GoogleHttp http;
  private final RtdbApi api;
  private final ConnectorManifest manifest;

  public RtdbConnector(CloudCredentials cloud) {
    this(cloud, new CloudLocal(Objects.requireNonNull(cloud, "cloud").endpoints()));
  }

  /** @param local what this computer's gcloud knows, for the form's suggestions (tests: their own files) */
  RtdbConnector(CloudCredentials cloud, CloudLocal local) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.local = Objects.requireNonNull(local, "local");
    this.http = new GoogleHttp(cloud);
    this.api = new RtdbApi(http);
    SchemaResources schemas = SchemaResources.of(RtdbConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Firebase Realtime Database")
        .icon("database")
        .group("Databases")
        .description("Paths, shallow reads and queries on a Firebase Realtime Database. Changes wait for approval.")
        .configSchema(schemas.load("config"))
        .tools(RtdbTools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("*.firebaseio.com", "*.firebasedatabase.app",
            "oauth2.googleapis.com", "iamcredentials.googleapis.com"), List.of(), List.of(
            "~/.config/gcloud/application_default_credentials.json")))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    RtdbSettings s;
    try {
      s = RtdbSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    try {
      return switch (call.tool()) {
        case RtdbTools.GET -> {
          RtdbRead.of(call.arguments(), s, Integer.MAX_VALUE);
          yield Classification.read();
        }
        case RtdbTools.WRITE -> {
          RtdbWrite w = RtdbWrite.of(call.arguments(), s, policy.limits() == null ? 0
              : policy.limits().maxRequestBytes());
          ObjectNode facts = F.objectNode().put("operation", w.operation()).put("path", w.path().text());
          yield Classification.mutation(w.operation() + " at " + w.path() + " changes data", facts);
        }
        default -> Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool()
            + ".", null);
      };
    } catch (Refusal r) {
      return Classification.deny(policy.rule(r.rule), r.getMessage(), r.hint);
    }
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    if (RtdbTools.WRITE.equals(call.tool())) {
      try {
        RtdbSettings s = RtdbSettings.of(cfg);
        return writePreview(cfg, s, RtdbWrite.of(call.arguments(), s, 0));
      } catch (ConnectorException e) {
        return notRunnable(cfg, e.getMessage());
      } catch (Refusal r) {
        return notRunnable(cfg, r.getMessage());
      }
    }
    // A read an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    ObjectNode payload = F.objectNode().put("tool", call.tool());
    payload.set("arguments", args);
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), cfg.name() + " · "
            + cfg.string("/databaseUrl"), FirestoreConnector.shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(payload)
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Data at one path, no change")));
  }

  private static ActionPreview notRunnable(ConnectionConfig cfg, String why) {
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "Not runnable: " + why, cfg.name(),
        FirestoreConnector.shorten(RtdbTools.WRITE + " on " + cfg.name() + ": not runnable", 120))
        .withPayload(F.objectNode());
  }

  /** The request exactly as it goes out: method, URL, headers (the token masked) and body. */
  static String requestText(RtdbSettings s, RtdbWrite w) {
    StringBuilder text = new StringBuilder();
    text.append(w.method()).append(' ').append(s.database()).append(w.path().url()).append("?print=silent\n")
        .append("Authorization: Bearer ‹masked›\n");
    byte[] body = w.body();
    if (body != null) {
      text.append("Content-Type: application/json; charset=utf-8\n\n")
          .append(new String(body, StandardCharsets.UTF_8));
    }
    return text.toString();
  }

  private static ActionPreview writePreview(ConnectionConfig cfg, RtdbSettings s, RtdbWrite w) {
    String path = w.path().text();
    List<Field> fields = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String data;
    switch (w.operation()) {
      case "set" -> {
        fields.add(new Field("Operation", "Set: replace the data at " + path + " with the value"));
        data = "Everything at " + path + ", replaced";
        warnings.add("Whatever is at " + path + " now, everything under it included, is replaced.");
      }
      case "update" -> {
        List<String> children = new ArrayList<>(w.value().propertyNames());
        fields.add(new Field("Operation", "Update " + children.size() + (children.size() == 1 ? " child" : " children")
            + " of " + path + "; the others stay"));
        fields.add(new Field("Children", FirestoreConnector.shorten(String.join(", ", children), 2_000)));
        data = "The children named, each replaced; null removes one";
      }
      default -> {
        fields.add(new Field("Operation", "Delete the data at " + path));
        data = "Everything at " + path + ", removed";
        warnings.add("Everything under " + path + " is deleted too.");
      }
    }
    fields.add(new Field("Path", path));
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", requestText(s, w), cfg.name() + " · " + s.host()
            + " · " + path, FirestoreConnector.shorten(RtdbTools.WRITE + " on " + cfg.name() + ": " + w.operation()
            + " " + path, 120))
        .withFields(fields)
        .withImpact(List.of(new Field("Data", data),
            new Field("Retried", "No: a request whose answer is lost is reported as unknown")))
        .withWarnings(warnings)
        .withFacets(Map.of("operation", w.operation(), "path", path))
        .withPayload(w.payload());
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    RtdbSettings s = RtdbSettings.of(cfg);
    GoogleHttp.Caller c = new GoogleHttp.Caller(cfg, s.credentials(), RtdbApi.SCOPES, secrets, ctx);
    try {
      return switch (call.tool()) {
        case RtdbTools.GET -> get(c, s, arguments(call, ctx));
        case RtdbTools.WRITE -> write(c, s, ctx);
        default -> throw ConnectorException.badInput(manifest.name() + " has no tool " + call.tool() + ".");
      };
    } catch (Refusal r) {
      throw r.toException();
    }
  }

  /** The arguments that run: the approved request's, or the call's. */
  private static JsonNode arguments(ToolCall call, ExecContext ctx) throws ConnectorException {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      JsonNode payload = ctx.approvedPayload().get();
      if (!call.tool().equals(payload.path("tool").asString(""))) {
        throw ConnectorException.badInput("The approved request is for another tool. Not executed.");
      }
      return payload.get("arguments");
    }
    return call.arguments();
  }

  private boolean personal(GoogleHttp.Caller c) {
    try {
      return GoogleIdentity.personal(http.credentials(c));
    } catch (ConnectorException e) {
      return false;
    }
  }

  private ToolResult get(GoogleHttp.Caller c, RtdbSettings s, JsonNode args) throws ConnectorException, Refusal {
    ExecContext ctx = c.ctx();
    RtdbRead r = RtdbRead.of(args, s, ctx.limits().maxRows());
    // The value is in the answer twice: its structured content and its text.
    long max = FirestoreConnector.budget(ctx) / 2;
    JsonNode value;
    try {
      value = api.get(c, api.uri(s, r.path(), r.query()), max, ctx.limits().maxCellChars(), personal(c));
    } catch (BoundedJson.TooLarge e) {
      throw ConnectorException.resourceError("The data at " + r.path() + " is larger than "
          + GoogleHttp.size(e.limit) + ", the most one answer holds on this endpoint. Narrow it: shallow=true lists "
          + "its keys only; orderBy with limitToFirst or limitToLast reads some children; or read a path further "
          + "down.", null);
    }
    if (r.path().root() && s.restricted()) {
      // Only the keys on the connection's list; a root that holds a single value has none.
      ObjectNode kept = F.objectNode();
      for (Map.Entry<String, JsonNode> e : value.properties()) {
        if (s.allows(e.getKey())) {
          kept.set(e.getKey(), e.getValue());
        }
      }
      value = kept;
    }
    ObjectNode out = F.objectNode().put("path", r.path().text()).put("shallow", r.shallow());
    out.set("value", value);
    if (value.isObject()) {
      out.put("children", value.size());
    }
    String text;
    if (value.isNull()) {
      text = "No data at " + r.path() + ".";
    } else {
      text = r.path() + (r.shallow() && value.isObject() ? " (keys only, " + value.size()
          + (value.size() == 1 ? " child)" : " children)") : "") + ": " + value;
    }
    if (r.orderBy() != null) {
      text += "\nThe database doesn't sort a REST answer: order the children yourself.";
    }
    return ToolResult.json(out, text);
  }

  private ToolResult write(GoogleHttp.Caller c, RtdbSettings s, ExecContext ctx) throws ConnectorException {
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: rtdb_write changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no write."));
    RtdbWrite w;
    try {
      w = RtdbWrite.approved(payload, s);
    } catch (Refusal r) {
      throw ConnectorException.denied(r.getMessage());
    }
    byte[] body = w.body();
    long max = ctx.limits().maxRequestBytes();
    if (max > 0 && body != null && body.length > max) {
      throw ConnectorException.denied("The value is larger than the " + GoogleHttp.size(max) + " a request may be on "
          + "this endpoint.");
    }
    api.write(c, w.method(), api.uri(s, w.path(), "print=silent"), body, personal(c));
    String path = w.path().text();
    ObjectNode out = F.objectNode().put("written", true).put("operation", w.operation()).put("path", path);
    String text = switch (w.operation()) {
      case "set" -> "Set " + path + ".";
      case "update" -> {
        int n = w.value().size();
        yield "Updated " + n + (n == 1 ? " child of " : " children of ") + path + ".";
      }
      default -> "Deleted " + path + ".";
    };
    return ToolResult.json(out, text);
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    RtdbSettings s;
    try {
      s = RtdbSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("rtdb.settings", e.getMessage(), null);
    }
    GoogleHttp.Caller c = new GoogleHttp.Caller(cfg, s.credentials(), RtdbApi.SCOPES, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    URI probe = api.uri(s, RtdbPath.ROOT, "shallow=true");
    long started = System.nanoTime();
    GoogleCredentials credentials;
    Map<String, List<String>> headers;
    try {
      credentials = http.credentials(c);
      headers = api.headers(c, probe);
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("identity", false, FirestoreConnector.ms(started), e.getMessage()));
      return TestResult.failure("rtdb.credentials", e.getMessage(), null).withChecks(checks);
    }
    String identity = GoogleIdentity.of(http, c, credentials, headers);
    boolean personal = GoogleIdentity.personal(credentials);
    checks.add(new TestResult.Check("identity", true, FirestoreConnector.ms(started), s.credentials().impersonate()
        != null ? "Impersonates " + identity + " for " + s.credentials().lifetimeSeconds() / 60 + " min"
        : (personal ? "Signed in as " : "Service account ") + identity));
    long read = System.nanoTime();
    JsonNode root;
    boolean many = false;
    try {
      root = api.get(c, probe, PROBE_BYTES, 256, personal);
    } catch (BoundedJson.TooLarge e) {
      root = null;
      many = true;
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("data", false, FirestoreConnector.ms(read), e.getMessage()));
      String code = e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE
          || e.kind() == ConnectorException.Kind.TIMEOUT ? "rtdb.unreachable" : "rtdb.refused";
      return TestResult.failure(code, e.getMessage(), null).withChecks(checks);
    }
    long latency = FirestoreConnector.ms(read);
    List<String> visible = new ArrayList<>();
    if (root != null && root.isObject()) {
      for (String key : root.propertyNames()) {
        if (s.allows(key)) {
          visible.add(key);
        }
      }
    }
    List<String> first = new ArrayList<>(visible.subList(0, Math.min(5, visible.size())));
    if (visible.size() > 5) {
      first.add("…");
    }
    String detail = many ? "More top-level paths than Test connection reads"
        : root == null || root.isNull() ? "The database is empty"
        : !root.isObject() ? "The root holds a single value"
        : (visible.isEmpty() ? "No top-level path" : visible.size() + (visible.size() == 1 ? " top-level path"
            : " top-level paths")) + " agents may use" + (first.isEmpty() ? "" : ": " + String.join(", ", first));
    checks.add(new TestResult.Check("data", true, latency, detail));
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        s.restricted() ? "Data under the connection's paths" : "The whole database", "Only writes a person approved",
        List.of(), null);
    TestResult result = TestResult.success(latency, "Firebase Realtime Database · " + s.host(), identity, permissions)
        .withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", "Firebase Realtime Database · " + s.host())
            .put("database", s.host()));
    for (Hint h : hints(cfg.env(), s, personal, !many && root != null && root.isObject() && visible.isEmpty())) {
      result = result.withHint(h);
    }
    return result;
  }

  /** A project's default database URL from this computer; signed in, the project's databases and top-level paths. */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return RtdbSuggestions.suggest(cfg, secrets, ctx, scope, http, api, cloud, local);
  }

  static List<Hint> hints(Env env, RtdbSettings s, boolean personal, boolean nothingVisible) {
    List<Hint> out = new ArrayList<>();
    if (s.credentials().impersonate() == null) {
      if (env == Env.PROD) {
        out.add(GoogleIdentity.longLived("rtdb.long-lived-credentials", s.credentials(), personal,
            "roles/firebasedatabase.viewer, or roles/firebasedatabase.admin for approved changes"));
      } else if (env == Env.STAGE && personal) {
        out.add(new Hint(Severity.INFO, "rtdb.personal-sign-in", "/credentials/impersonate",
            "Agents read with your own Google sign-in. Impersonate a service account to give them less."));
      }
    }
    if (nothingVisible && s.restricted()) {
      out.add(new Hint(Severity.WARNING, "rtdb.no-paths", "/paths", "No top-level path of the database is on the "
          + "connection's list."));
    }
    return out;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      RtdbSettings s = RtdbSettings.of(cfg);
      GoogleHttp.Caller c = new GoogleHttp.Caller(cfg, s.credentials(), RtdbApi.SCOPES, secrets, ctx);
      long started = System.nanoTime();
      try {
        api.get(c, api.uri(s, RtdbPath.ROOT, "shallow=true"), PROBE_BYTES, 256, false);
      } catch (BoundedJson.TooLarge e) {
        // The database answered: its keys are more than health reads.
      }
      return HealthResult.healthy(FirestoreConnector.ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode();
    out.put("database", cfg.string("/databaseUrl"));
    List<String> paths = cfg.strings("/paths");
    if (paths.isEmpty()) {
      out.put("paths", "The whole database");
    } else {
      paths.forEach(out.putArray("paths")::add);
    }
    out.put("reads", "rtdb_get: one path; shallow=true for its keys (the root's default); orderBy $key, $value, "
        + "$priority or a child, with limitToFirst, limitToLast, startAt, endAt or equalTo");
    out.put("changes", "One set (PUT), update (PATCH) or delete (DELETE) at one path per rtdb_write, after approval");
    if (object != null && !object.isBlank()) {
      out.put("path", "Call rtdb_get with path " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    cloud.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    cloud.evict(connectionId);
  }

  @Override
  public void close() {
    http.close();
  }
}
