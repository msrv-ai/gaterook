package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import redis.clients.jedis.exceptions.JedisException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Redis connector (report 05): {@code redis_get} and {@code redis_scan} over Jedis 8.
 *
 * <p>Read only by construction: neither tool takes a command; {@code classify} answers READ or DENY, never MUTATION;
 * every command goes through {@link ReadOnlyCommandExecutor}, which refuses anything outside its read allowlist before
 * the network — {@code SET}, {@code FLUSHALL}, {@code KEYS}, {@code EVAL} included. The account's ACL is the real
 * boundary on the server, and Test connection shows what it allows. Keys outside the connection's allowlist or in its
 * denylist are refused ({@code redis_get}) or hidden ({@code redis_scan}). Values are untrusted content: the core
 * masks secrets in them before anyone sees them.
 *
 * <p>Redis Cluster and Sentinel aren't supported (Test connection says so); Redis 6.0+ is required ({@code HELLO}).
 */
public final class RedisConnector implements Connector {

  public static final String TYPE = "redis";

  private final ConnectorManifest manifest;
  private final RedisClients clients;

  public RedisConnector() {
    this(Clock.systemUTC());
  }

  RedisConnector(Clock clock) {
    SchemaResources schemas = SchemaResources.of(RedisConnector.class, "redis");
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Redis")
        .icon("layers")
        .group("Databases")
        .description("Read keys and scan by pattern. Read only.")
        .configSchema(schemas.load("config"))
        .tools(RedisTools.definitions(schemas))
        .build();
    this.clients = new RedisClients(clock);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public List<ToolDefinition> tools(EndpointView endpoint) {
    return RedisTools.forEndpoint(manifest.tools(), endpoint);
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    RedisSettings s;
    try {
      s = RedisSettings.parse(cfg);
    } catch (RedisSettings.InvalidSettingsException e) {
      return Classification.deny(policy.rule("config-invalid"), "The settings of " + cfg.name() + " can't be used: "
          + e.getMessage(), "Ask the person to fix the connection in GateRook.");
    }
    try {
      RedisRequest.of(call.tool(), call.arguments(), cfg, s);
    } catch (RedisRequest.Refusal r) {
      return Classification.deny(policy.rule(r.rule), r.getMessage(), r.hint);
    }
    return Classification.read();
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    try {
      RedisSettings s = RedisSettings.parse(cfg);
      RedisRequest request = RedisRequest.of(call.tool(), call.arguments(), cfg, s);
      return Actions.preview(request, s.withDatabase(request.database()), cfg);
    } catch (RedisSettings.InvalidSettingsException | RedisRequest.Refusal e) {
      // classify() denies such a call before any request exists; this preview only ever shows what was asked.
      return ActionPreview.of(ActionPreview.Kind.JSON, "json", call.arguments().toString(), cfg.name(),
          call.tool() + " on " + cfg.name());
    }
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    RedisSettings s;
    try {
      s = RedisSettings.parse(cfg);
    } catch (RedisSettings.InvalidSettingsException e) {
      return TestResult.failure("redis.config", e.getMessage(), null);
    }
    try (Credentials login = Credentials.resolve(s, secrets)) {
      return RedisProbe.run(cfg, s, login, ctx);
    } catch (SecretUnavailableException e) {
      return TestResult.failure("redis.secret", e.getMessage(), null);
    }
  }

  @Override
  public TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return test(cfg, secrets, ctx);
  }

  /** {@code PING} on the pool, at most 2 s; over 1 s is "Slow" (report 05 §5). */
  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    RedisSettings s;
    try {
      s = RedisSettings.parse(cfg);
    } catch (RedisSettings.InvalidSettingsException e) {
      return HealthResult.unreachable(e.getMessage());
    }
    Instant deadline = earlier(ctx.clock().instant().plus(Duration.ofSeconds(2)), ctx.deadline());
    try (RedisSession session = clients.open(cfg, s, Credentials.resolve(s, secrets), deadline)) {
      long t0 = System.nanoTime();
      session.call(Cmd.PING);
      return HealthResult.healthy((System.nanoTime() - t0) / 1_000_000);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    } catch (JedisException e) {
      return HealthResult.unreachable(RedisErrors.of(e, s).message());
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    RedisSettings s;
    try {
      s = RedisSettings.parse(cfg);
    } catch (RedisSettings.InvalidSettingsException e) {
      throw ConnectorException.badInput("The settings of " + cfg.name() + " can't be used: " + e.getMessage());
    }
    // An approved call (a person set the tool to Approval) runs the stored request, not the arguments again.
    Optional<JsonNode> approved = ctx.approved() ? ctx.approvedPayload() : Optional.empty();
    JsonNode arguments = approved.isPresent() ? approved.get() : call.arguments();
    String tool = approved.isPresent() ? approved.get().path("tool").asString(call.tool()) : call.tool();
    RedisRequest r;
    try {
      r = RedisRequest.of(tool, arguments, cfg, s);
    } catch (RedisRequest.Refusal e) {
      throw e.rule.equals("key-not-allowed") || e.rule.equals("database-not-allowed")
          ? ConnectorException.denied(e.getMessage())
          : ConnectorException.badInput(e.getMessage() + (e.hint == null ? "" : " " + e.hint));
    }
    s = s.withDatabase(r.database());
    Budget budget = Budget.of(ctx.limits(), switch (r) {
      case RedisRequest.Get g -> g.limit();
      case RedisRequest.Scan sc -> sc.count();
    });
    try (RedisSession session = clients.open(cfg, s, Credentials.resolve(s, secrets), ctx.deadline())) {
      ctx.onCancel(session::abort);
      RedisReads.Reading reading = switch (r) {
        case RedisRequest.Get g -> RedisReads.get(session, g, budget, ctx, s.cursorScope(cfg.id()));
        case RedisRequest.Scan sc -> ScanLoop.scan(session, sc, budget, ctx, s.cursorScope(cfg.id()));
      };
      if (!s.databases().isEmpty()) {
        reading.structured().put("database", s.database());
      }
      String text = r instanceof RedisRequest.Get ? RedisText.get(reading.structured(), s.database())
          : RedisText.scan(reading.structured());
      ToolResult result = ToolResult.json(reading.structured(), text);
      return reading.structured().path("truncated").asBoolean(false) ? result.truncated("partial") : result;
    } catch (JedisException e) {
      if (ctx.cancelled()) {
        throw ConnectorException.timeout("The call was stopped at its deadline.");
      }
      throw RedisErrors.call(e, s);
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    RedisSettings s;
    try {
      s = RedisSettings.parse(cfg);
    } catch (RedisSettings.InvalidSettingsException e) {
      throw ConnectorException.badInput("The settings of " + cfg.name() + " can't be used: " + e.getMessage());
    }
    if (s.databases().size() > 1) {
      if (object != null && !object.isBlank()) {
        throw ConnectorException.badInput("Choose a database with redis_scan and pass the key pattern as pattern.");
      }
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      var databases = out.putArray("databases");
      s.databases().forEach(databases::add);
      out.put("databaseRequired", true);
      var allow = out.putArray("keyAllowlist");
      s.keyAllowlist().forEach(pattern -> allow.add(pattern.pattern()));
      var deny = out.putArray("keyDenylist");
      s.keyDenylist().forEach(pattern -> deny.add(pattern.pattern()));
      out.put("reads", "Pass database with redis_get or redis_scan to choose one of the selected databases.");
      return Optional.of(out);
    }
    try (RedisSession session = clients.open(cfg, s, Credentials.resolve(s, secrets), ctx.deadline())) {
      ctx.onCancel(session::abort);
      return Optional.of(RedisDescribe.describe(session, object));
    } catch (JedisException e) {
      throw RedisErrors.call(e, s);
    }
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    clients.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    clients.evict(connectionId);
  }

  @Override
  public void close() {
    clients.close();
  }

  /** The pools (tests). */
  RedisClients clients() {
    return clients;
  }

  private static Instant earlier(Instant a, Instant b) {
    return b == null || a.isBefore(b) ? a : b;
  }
}
