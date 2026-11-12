package ai.msrv.gaterook.connectors.s3;

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
import ai.msrv.gaterook.connector.api.PreparedAction;
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
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * S3-compatible object storage (docs/s3.md): {@code s3_list_buckets}, {@code s3_list_objects},
 * {@code s3_head_object} and {@code s3_read_object} read; {@code s3_put_object}, {@code s3_copy_object} and
 * {@code s3_delete_object} run one request a person approved.
 *
 * <p>The route of a call comes from {@link #classify} without I/O: buckets on the connection's list, keys and
 * metadata the connector can send as they are ({@link Names}), a text body within the endpoint's request size. A read
 * sends only {@code GET} and {@code HEAD}; a change is built from its arguments and the connection alone
 * ({@link Change}), shown exactly as it will be sent, and built again and compared with the approved payload before
 * {@code execute} sends it. On Amazon S3 with an IAM role, AWS enforces the same: each call assumes the role under a
 * session policy for its tool on the listed buckets, or for a change its one action on the one object
 * ({@link SessionPolicies}). No presigned URL is ever made.
 */
public final class S3Connector implements Connector {

  static final String TYPE = "s3";
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final CloudCredentials cloud;
  private final S3Api api;
  private final Reads reads;
  private final ConnectorManifest manifest;
  /** What the setup helpers read of this computer: AWS profiles and regions. */
  private final CloudLocal local;

  public S3Connector(CloudCredentials cloud) {
    this(cloud, cloud == null ? null : new CloudLocal(cloud.endpoints()));
  }

  /** {@code local}: the profiles and regions the setup helpers read (tests: their own files and environment). */
  S3Connector(CloudCredentials cloud, CloudLocal local) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.local = Objects.requireNonNull(local, "local");
    this.api = new S3Api(cloud);
    this.reads = new Reads(api);
    SchemaResources schemas = SchemaResources.of(S3Connector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("S3 object storage")
        .icon("archive")
        .group("Cloud")
        .description("Buckets, objects and text reads on Amazon S3 and S3-compatible storage. Writes wait for "
            + "approval.")
        .configSchema(schemas.load("config"))
        .tools(S3Tools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("s3.*.amazonaws.com", "*.s3.*.amazonaws.com",
            "sts.*.amazonaws.com"), List.of(), List.of("~/.aws/config", "~/.aws/credentials")))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    S3Settings s;
    try {
      s = S3Settings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    JsonNode a = call.arguments();
    try {
      switch (call.tool()) {
        case S3Tools.LIST_BUCKETS -> {
          Reads.bucketPrefix(a);
          return Classification.read();
        }
        case S3Tools.LIST_OBJECTS -> {
          Args.bucket(a, "bucket", s);
          for (String field : List.of("prefix", "delimiter", "startAfter")) {
            String problem = Names.listTextProblem(Args.text(a, field), field);
            if (problem != null) {
              throw Refusal.badArgument(problem);
            }
          }
          return Classification.read();
        }
        case S3Tools.HEAD_OBJECT -> {
          Args.bucket(a, "bucket", s);
          Args.key(a, "key", false);
          Args.version(a, "versionId");
          return Classification.read();
        }
        case S3Tools.READ_OBJECT -> {
          Args.bucket(a, "bucket", s);
          Reads.readableKey(a);
          Args.version(a, "versionId");
          return Classification.read();
        }
        case S3Tools.PUT_OBJECT, S3Tools.COPY_OBJECT, S3Tools.DELETE_OBJECT -> {
          Change ch = Change.of(call.tool(), a, s, policy.limits() == null ? 0 : policy.limits().maxRequestBytes());
          return Classification.mutation(ch.reason(), ch.facts());
        }
        default -> {
          return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool()
              + ".", null);
        }
      }
    } catch (Refusal r) {
      return Classification.deny(policy.rule(r.rule), r.getMessage(), r.hint);
    }
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    S3Settings s;
    try {
      s = S3Settings.of(cfg);
    } catch (ConnectorException e) {
      return notRunnable(call, cfg, e.getMessage());
    }
    if (S3Tools.CHANGES.contains(call.tool())) {
      try {
        Change ch = Change.of(call.tool(), call.arguments(), s, 0);
        return ch.preview(cfg, s, api.uri(s, ch.request()), api.knownOwner(cfg, s), null);
      } catch (Refusal r) {
        return notRunnable(call, cfg, r.getMessage());
      }
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    ObjectNode payload = F.objectNode().put("tool", call.tool());
    payload.set("arguments", args);
    String bucket = call.string("bucket");
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), cfg.name() + " · " + s.target()
            + (bucket == null ? "" : " · " + bucket), shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(payload)
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", S3Tools.READ_OBJECT.equals(call.tool())
            ? "Up to maxBytes of one object's text, no change" : "Names and metadata, no change")));
  }

  private static ActionPreview notRunnable(ToolCall call, ConnectionConfig cfg, String why) {
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "Not runnable: " + why, cfg.name(),
        shorten(call.tool() + " on " + cfg.name() + ": not runnable", 120)).withPayload(F.objectNode());
  }

  /**
   * A change: the exact request of {@link #describeAction}, and what its target holds now ({@code HeadObject}, as far
   * as the credentials may read it), so the approver sees whether it creates, replaces or deletes. A call
   * {@code classify} would refuse is rejected without a request.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    if (!S3Tools.CHANGES.contains(call.tool())) {
      return PreparedAction.approval(describeAction(call, cfg), null);
    }
    S3Settings s;
    Change ch;
    try {
      s = S3Settings.of(cfg);
      ch = Change.of(call.tool(), call.arguments(), s, ctx.limits().maxRequestBytes());
    } catch (ConnectorException e) {
      return PreparedAction.reject("The settings of " + cfg.name() + " can't be used: " + e.getMessage());
    } catch (Refusal r) {
      return PreparedAction.reject(r.forAgent());
    }
    S3Api.Call c = new S3Api.Call(cfg, s, secrets, ctx);
    Change.Impact impact = look(c, ch.bucket(), ch.key(), ch.versionId());
    if (ch.sourceBucket() != null) {
      Change.Impact source = look(c, ch.sourceBucket(), ch.sourceKey(), ch.sourceVersionId());
      impact = impact.withSource(source.known() ? source.exists() ? source.describe() : "Nothing: there is no "
          + "such object now" : null);
    }
    return PreparedAction.approval(ch.preview(cfg, s, api.uri(s, ch.request()), api.knownOwner(cfg, s), impact),
        null);
  }

  /** What is at {@code bucket/key} now, or {@link Change.Impact#UNKNOWN} when the look fails. */
  private Change.Impact look(S3Api.Call c, String bucket, String key, String versionId) {
    try {
      Reads.Head h = reads.head(c, bucket, key, versionId, SessionPolicies.read(c.settings(), S3Tools.HEAD_OBJECT));
      return new Change.Impact(true, true, h.size() < 0 ? null : String.format(Locale.US, "%,d bytes", h.size()),
          h.contentType(), h.lastModified(), null);
    } catch (ConnectorException e) {
      boolean missing = e.kind() == ConnectorException.Kind.BAD_INPUT && e.getMessage().startsWith("There is no "
          + "object");
      return missing ? new Change.Impact(true, false, null, null, null, null) : Change.Impact.UNKNOWN;
    }
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    S3Settings s = S3Settings.of(cfg);
    S3Api.Call c = new S3Api.Call(cfg, s, secrets, ctx);
    try {
      return switch (call.tool()) {
        case S3Tools.LIST_BUCKETS -> reads.listBuckets(c, arguments(call, ctx));
        case S3Tools.LIST_OBJECTS -> reads.listObjects(c, arguments(call, ctx));
        case S3Tools.HEAD_OBJECT -> reads.headObject(c, arguments(call, ctx));
        case S3Tools.READ_OBJECT -> reads.readObject(c, arguments(call, ctx));
        case S3Tools.PUT_OBJECT, S3Tools.COPY_OBJECT, S3Tools.DELETE_OBJECT -> change(c, call);
        default -> throw ConnectorException.badInput(manifest.name() + " has no tool " + call.tool() + ".");
      };
    } catch (Refusal r) {
      throw r.toException();
    }
  }

  /** The arguments of a read that runs: the approved request's (a read an endpoint put in Approval), or the call's. */
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

  /**
   * An approved change: built again from the call and the connection, compared with the payload the person approved,
   * then sent once under the session policy of its one action. A lost answer is {@code OUTCOME_UNKNOWN}.
   */
  private ToolResult change(S3Api.Call c, ToolCall call) throws ConnectorException, Refusal {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: " + call.tool() + " changes data, so a person approves it "
          + "first.");
    }
    S3Settings s = c.settings();
    Change ch = Change.of(call.tool(), call.arguments(), s, ctx.limits().maxRequestBytes());
    URI uri = api.uri(s, ch.request());
    JsonNode approved = ctx.approvedPayload().orElse(null);
    if (approved == null || !ch.payload(uri).equals(approved)) {
      throw ConnectorException.denied("The call doesn't match the request that was approved. Not executed.");
    }
    String policy = switch (ch.tool()) {
      case S3Tools.PUT_OBJECT -> SessionPolicies.put(s, ch.bucket(), ch.key());
      case S3Tools.COPY_OBJECT -> SessionPolicies.copy(s, ch.sourceBucket(), ch.sourceKey(),
          ch.sourceVersionId() != null, ch.bucket(), ch.key());
      default -> SessionPolicies.delete(s, ch.bucket(), ch.key(), ch.versionId() != null);
    };
    S3Api.Answer a = api.send(c, ch.request(), policy, S3Api.MAX_ERROR);
    if (a.status() / 100 != 2) {
      throw S3Api.error(a, ch.request(), c, true);
    }
    ObjectNode out = F.objectNode().put("changed", true).put("operation", ch.operation())
        .put("bucket", ch.bucket()).put("key", ch.key());
    String etag = a.header("etag");
    if (ch.tool().equals(S3Tools.COPY_OBJECT) && a.body() != null && a.body().length > 0) {
      // S3 may fail a copy after its 200 status: the error is then the body.
      Element root;
      try {
        root = S3Xml.parse(a.body());
      } catch (IOException e) {
        throw ConnectorException.outcomeUnknown("The answer to the copy isn't S3's XML, so whether it ran is not "
            + "known. Check the object with s3_head_object before retrying.", e);
      }
      if (S3Xml.name(root).equals("Error")) {
        throw S3Api.error(new S3Api.Answer(500, a.headers(), a.body(), false), ch.request(), c, true);
      }
      etag = S3Xml.text(root, "ETag");
      String modified = S3Xml.text(root, "LastModified");
      if (modified != null) {
        out.put("lastModified", modified);
      }
    }
    if (etag != null) {
      out.put("etag", etag);
    }
    String version = a.header("x-amz-version-id");
    if (version != null) {
      out.put("versionId", version);
    }
    boolean marker = "true".equalsIgnoreCase(a.header("x-amz-delete-marker"));
    if (marker) {
      out.put("deleteMarker", true);
    }
    String where = ch.bucket() + "/" + Names.shown(ch.key());
    String text = switch (ch.tool()) {
      case S3Tools.PUT_OBJECT -> "Wrote " + String.format(Locale.US, "%,d", ch.request().body().length) + " bytes to "
          + where + ".";
      case S3Tools.COPY_OBJECT -> "Copied " + ch.sourceBucket() + "/" + Names.shown(ch.sourceKey()) + " to " + where
          + ".";
      default -> (ch.versionId() != null ? "Deleted version " + Names.shown(ch.versionId()) + " of " : marker
          ? "Added a delete marker to " : "Deleted ") + where + ".";
    };
    return ToolResult.json(out, text);
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      return new ConnectionTest(api, cfg, secrets, ctx).run();
    } finally {
      // The core tests an unsaved form as draft-<uuid>: nobody asks for it again, its credentials go at once.
      if (cfg.id().startsWith("draft-")) {
        onConnectionRemoved(cfg.id());
      }
    }
  }

  /**
   * Profiles and regions of this computer, or what a provider's endpoint names; signed in, the buckets
   * ({@link S3Suggestions}).
   */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return S3Suggestions.suggest(cfg, secrets, ctx, scope, cloud, local, api);
  }

  static List<Hint> hints(Env env, S3Settings s, int visible, boolean listed) {
    List<Hint> out = new ArrayList<>();
    if (s.aws() && s.credentials().roleArn() == null) {
      out.add(new Hint(env == Env.DEV ? Severity.INFO : Severity.WARNING, "s3.no-role", "/credentials/roleArn",
          "Without a role, each call has every permission of the credentials. With a role, GateRook limits each call "
              + "to what it needs, enforced by AWS."));
    } else if (s.aws() && !s.generatedPolicy()) {
      out.add(new Hint(Severity.WARNING, "s3.no-session-policy", "/credentials/sessionPolicy",
          "Calls use the role's permissions as they are: AWS doesn't keep a read from writing."));
    }
    if (!s.aws()) {
      out.add(new Hint(Severity.INFO, "s3.key-permissions", "/credentials/accessKeyId",
          s.target() + " has no session policies: give the access key only the buckets and permissions agents "
              + "need."));
    }
    if (s.plainHttp()) {
      out.add(new Hint(env == Env.DEV ? Severity.INFO : Severity.WARNING, "s3.plain-http", "/endpoint",
          "The endpoint uses http: objects and signed requests travel unencrypted."));
    }
    if (listed && visible == 0) {
      out.add(new Hint(Severity.WARNING, "s3.no-buckets", "/buckets", s.buckets().isEmpty()
          ? "The credentials list no bucket" + (s.aws() ? " in " + s.region() : "") + "."
          : "No bucket the credentials list matches the connection's list."));
    }
    return out;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      return new ConnectionTest(api, cfg, secrets, ctx).health();
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    S3Settings s = S3Settings.of(cfg);
    ObjectNode out = F.objectNode();
    out.put("storage", s.aws() ? "Amazon S3" : s.endpoint().toString());
    out.put("region", s.region());
    out.put("addressing", s.pathStyle() ? "path style" : "virtual-hosted style");
    if (s.buckets().isEmpty()) {
      out.put("buckets", s.aws() ? "Every bucket of the credentials' own account" : "Every bucket the credentials "
          + "may use");
    } else {
      ArrayNode a = out.putArray("buckets");
      s.buckets().forEach(a::add);
    }
    out.put("reads", "s3_list_buckets, s3_list_objects (prefix and delimiter), s3_head_object, and s3_read_object "
        + "for up to 256 KB of a text object per call");
    out.put("changes", "One s3_put_object (text), s3_copy_object or s3_delete_object per call, after approval");
    if (object != null && !object.isBlank()) {
      out.put("bucket", "Call s3_list_objects with bucket " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    cloud.evict(connectionId);
    api.forget(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    cloud.evict(connectionId);
    api.forget(connectionId);
  }
}
