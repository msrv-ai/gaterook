package ai.msrv.gaterook.connectors.prometheus;

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
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.LocalPorts;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prometheus and servers with its HTTP API (docs/prometheus.md): VictoriaMetrics, Thanos Query, Grafana Mimir, Cortex.
 *
 * <p>Every tool reads. {@link #classify} turns the call into its request without I/O ({@link Plan}) and refuses what
 * can't be one: an unreadable time, a step that asks for more points per series than the connection allows. The
 * client then sends only GET requests to fixed read paths of {@code /api/v1} under the connection's URL, with the
 * call's remaining time as the server's evaluation {@code timeout}; no admin, write or lifecycle endpoint is ever
 * built. PromQL itself can't change data, so the read API is the boundary GateRook enforces, and the credentials'
 * scope on the server (a tenant, a proxy) decides which metrics they read: GateRook has no per-metric list, as it
 * can't tell from PromQL text which series every compatible server reads.
 */
public final class PrometheusConnector implements Connector {

  static final String TYPE = "prometheus";
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final Clock clock;
  private final PrometheusApi api = new PrometheusApi();
  private final ConnectorManifest manifest;

  public PrometheusConnector(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    SchemaResources schemas = SchemaResources.of(PrometheusConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Prometheus")
        .icon("activity")
        .group("Databases")
        .description("PromQL queries, series, labels, targets, rules and alerts of Prometheus-compatible servers. "
            + "Read only.")
        .configSchema(schemas.load("config"))
        .tools(PrometheusTools.definitions(schemas))
        .access(ConnectorManifest.RequiredAccess.none())
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    PrometheusSettings s;
    try {
      s = PrometheusSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), "The settings of " + cfg.name() + " can't be used: "
          + e.getMessage(), "Ask the person to fix the connection in GateRook.");
    }
    try {
      Plan.of(call.tool(), call.arguments(), s, clock.instant());
      return Classification.read();
    } catch (Plan.Refusal r) {
      return Classification.deny(policy.rule(r.rule), r.getMessage(), r.hint);
    }
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The request a read tool an endpoint put in Approval sends: its URL with every parameter and the absolute times it
   * was asked with, which the approval stores and the execution sends. Only the evaluation {@code timeout} is added
   * when it runs: what is then left of the call.
   */
  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    try {
      PrometheusSettings s = PrometheusSettings.of(cfg);
      Plan plan = Plan.of(call.tool(), call.arguments(), s, clock.instant());
      StringBuilder text = new StringBuilder("GET ").append(PrometheusApi.uri(s, plan, null))
          .append("\nAccept: application/json");
      if (s.tenant() != null) {
        text.append("\nX-Scope-OrgID: ").append(s.tenant());
      }
      if (s.auth() != PrometheusSettings.Auth.NONE) {
        text.append("\nAuthorization: ‹masked›");
      }
      List<Field> fields = new ArrayList<>();
      if (plan.query != null) {
        fields.add(new Field("Query", shorten(plan.query, 4_000)));
      }
      if (plan.time != null) {
        fields.add(new Field("Time", Plan.iso(plan.time)));
      }
      if (plan.start != null) {
        fields.add(new Field("Range", Plan.iso(plan.start) + " → " + Plan.iso(plan.end)));
      }
      if (plan.step != null) {
        fields.add(new Field("Step", Durations.human(plan.step)));
      }
      if (plan.label != null) {
        fields.add(new Field("Label", plan.label));
      }
      if (plan.evaluates) {
        fields.add(new Field("Server timeout", "What is left of the call when it runs, sent as timeout"));
      }
      String summary = call.tool() + " on " + cfg.name() + (plan.query == null ? "" : ": " + plan.query);
      return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text.toString(), cfg.name() + " · " + s.target(),
              shorten(summary.replace('\n', ' '), 120))
          .withFields(fields)
          .withImpact(List.of(new Field("Reads", "Metrics and their metadata, no change")))
          .withFacets(Map.of("tool", call.tool()))
          .withPayload(F.objectNode().set("arguments", plan.arguments));
    } catch (ConnectorException | Plan.Refusal e) {
      // classify() denies such a call before any request exists; this preview only shows what was asked.
      ObjectNode args = call.arguments().deepCopy();
      return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), cfg.name(),
              shorten(call.tool() + " on " + cfg.name(), 120))
          .withPayload(F.objectNode().set("arguments", args))
          .withFacets(Map.of("tool", call.tool()));
    }
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    PrometheusSettings s = PrometheusSettings.of(cfg);
    Plan plan;
    try {
      plan = Plan.of(call.tool(), arguments(call, ctx), s, ctx.clock().instant());
    } catch (Plan.Refusal r) {
      throw r.rule.equals("unknown-tool") ? ConnectorException.badInput(r.getMessage())
          : ConnectorException.badInput(r.getMessage() + (r.hint == null ? "" : " " + r.hint));
    }
    PrometheusApi.Call c = new PrometheusApi.Call(cfg, s, secrets, ctx);
    QueryAnswers.Shape shape = new QueryAnswers.Shape(Math.max(1, Math.min(plan.rows, ctx.limits().maxRows())),
        budget(ctx), ctx.limits().maxCellChars());
    PrometheusApi.Answer answer = api.send(c, plan);
    if (plan.evaluates) {
      return QueryAnswers.answer(answer, plan, shape, s);
    }
    int depth = switch (plan.endpoint) {
      case TARGETS, RULES, ALERTS -> 2;
      default -> 1;
    };
    PromJson.Tree tree = PromJson.read(answer.body(), answer.cut(), new PromJson.Bounds(depth, shape.rows() + 1));
    if (!PromJson.envelope(tree)) {
      throw ConnectorException.resourceError("The server answered with something that isn't Prometheus' JSON. Check "
          + "the server URL.", null);
    }
    if (tree.status().equals("error")) {
      throw ConnectorException.badInput("The server refused " + PrometheusApi.what(plan) + ": "
          + PrometheusApi.excerpt(tree.root().path("error").asString("")) + ".");
    }
    return switch (plan.endpoint) {
      case SERIES -> ListAnswers.series(tree, plan, shape, s);
      case LABELS -> ListAnswers.labels(tree, plan, shape, s);
      case LABEL_VALUES -> ListAnswers.labelValues(tree, plan, shape, s);
      case METADATA -> ListAnswers.metadata(tree, plan, shape, s);
      case TARGETS -> ListAnswers.targets(tree, plan, shape, s);
      case RULES -> ListAnswers.rules(tree, plan, shape, s);
      case ALERTS -> ListAnswers.alerts(tree, plan, shape, s);
      default -> throw ConnectorException.badInput("Prometheus has no tool " + call.tool() + ".");
    };
  }

  /** The arguments that run: the approved request's (its absolute times), or the call's. */
  private static JsonNode arguments(ToolCall call, ExecContext ctx) {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      return ctx.approvedPayload().get().get("arguments");
    }
    return call.arguments();
  }

  /** The bytes an answer may take; a quarter is left for masking and the core's own fields (as in Logs and GCP). */
  static long budget(ExecContext ctx) {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  /** A local server; the server's kind, its sign-in, tenant and API path ({@link PrometheusSuggestions}). */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return PrometheusSuggestions.suggest(cfg, secrets, ctx, scope, api, LocalPorts::listening);
  }

  /**
   * The server's build information (its version; compatible servers may not report one), an instant query of
   * {@code vector(1)}, and the number of {@code up} series, which says whether anything is scraped into it. The hints
   * say what the connection can't keep from agents.
   */
  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    PrometheusSettings s;
    try {
      s = PrometheusSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("prometheus.settings", e.getMessage(), null);
    }
    PrometheusApi.Call c = new PrometheusApi.Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    String server = "Prometheus-compatible server";
    long started = System.nanoTime();
    Plan info = Plan.buildInfo();
    try {
      PrometheusApi.Answer a = api.fetch(c, info);
      if (a.ok()) {
        PromJson.Tree t = PromJson.read(a.body(), a.cut(), new PromJson.Bounds(1, 50));
        String application = t.data().path("application").asString("Prometheus");
        String version = t.data().path("version").asString("");
        server = QueryReader.cell(application + (version.isEmpty() ? "" : " " + version), 80);
        checks.add(new TestResult.Check("server", true, ms(started), server));
      } else if (a.status() == 404) {
        checks.add(new TestResult.Check("server", true, ms(started), "Version not reported (404)"));
      } else {
        throw PrometheusApi.error(a, info, s);
      }
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("server", false, ms(started), e.getMessage()));
      return failure(e, checks);
    }
    long queryStarted = System.nanoTime();
    long latency;
    Plan probe = Plan.probe("vector(1)", ctx.clock().instant());
    try {
      PrometheusApi.Answer a = api.send(c, probe);
      latency = ms(queryStarted);
      QueryReader.Facts f = QueryReader.walk(a.body(), a.cut(), counting(), 64);
      if (f == null || !f.status.equals("success")) {
        throw ConnectorException.resourceError("The server's answer to vector(1) isn't Prometheus' JSON. Check the "
            + "server URL.", null);
      }
      checks.add(new TestResult.Check("query", true, latency, "PromQL evaluates: vector(1) gave " + f.series
          + (f.series == 1 ? " series" : " series")));
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("query", false, ms(queryStarted), e.getMessage()));
      return failure(e, checks);
    }
    List<Hint> hints = new ArrayList<>();
    long upStarted = System.nanoTime();
    try {
      PrometheusApi.Answer a = api.send(c, Plan.probe("count(up)", ctx.clock().instant()));
      QueryReader.Facts f = QueryReader.walk(a.body(), a.cut(), counting(), 64);
      String count = f == null ? null : firstValue(a);
      if (f != null && f.series == 0) {
        checks.add(new TestResult.Check("targets", true, ms(upStarted), "No up series"));
        hints.add(new Hint(Severity.INFO, "prometheus.no-up", null, "The server has no up series: nothing is "
            + "scraped into it now" + (s.tenant() == null ? "" : ", or tenant " + s.tenant() + " has no data")
            + "."));
      } else {
        checks.add(new TestResult.Check("targets", true, ms(upStarted), (count == null ? "Some" : count)
            + " up series (scrape targets)"));
      }
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("targets", false, ms(upStarted), e.getMessage()));
    }
    hints.addAll(hints(cfg.env(), s));
    TestResult result = TestResult.success(latency, server, identity(s), new TestResult.Permissions(
        TestResult.Level.READ, "PromQL queries, series, labels, metadata, targets, rules and alerts",
        "None through GateRook: it calls no write, admin or lifecycle endpoint", List.of(), null))
        .withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", server));
    for (Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  /** The first sample's value of an instant query's answer, or {@code null}. */
  private static String firstValue(PrometheusApi.Answer a) {
    String[] value = {null};
    QueryReader.walk(a.body(), a.cut(), new QueryReader.Visitor() {
      @Override public boolean wants(int index) { return index == 0; }
      @Override public void labels(int index, ObjectNode labels) {}
      @Override public void point(int index, String time, String v, JsonNode histogram) { value[0] = v; }
      @Override public void points(int index, long count) {}
    }, 64);
    return value[0];
  }

  private static QueryReader.Visitor counting() {
    return new QueryReader.Visitor() {
      @Override public boolean wants(int index) { return false; }
      @Override public void labels(int index, ObjectNode labels) {}
      @Override public void point(int index, String time, String value, JsonNode histogram) {}
      @Override public void points(int index, long count) {}
    };
  }

  static List<Hint> hints(Env env, PrometheusSettings s) {
    List<Hint> out = new ArrayList<>();
    if (s.auth() == PrometheusSettings.Auth.NONE && !s.loopback()) {
      out.add(new Hint(env == Env.DEV ? Severity.INFO : Severity.WARNING, "prometheus.no-auth", "/auth/mode",
          s.base().getHost() + " answers without credentials: anyone who can reach it reads every metric. Outside "
              + "dev, put it behind a sign-in and give GateRook a read-only account or token."));
    }
    out.add(new Hint(Severity.INFO, "prometheus.read-api", null, "GateRook calls only the read API: queries, series, "
        + "labels, metadata, targets, rules and alerts. It never calls admin, write or lifecycle endpoints, and "
        + "PromQL can't change data."));
    out.add(new Hint(Severity.INFO, "prometheus.metric-scope", null, "Agents can query every metric these "
        + "credentials read: GateRook has no per-metric list. Scope access on the server: a tenant, or a token your "
        + "proxy limits."));
    return out;
  }

  private static String identity(PrometheusSettings s) {
    String who = switch (s.auth()) {
      case BASIC -> s.username();
      case BEARER -> "bearer token";
      case NONE -> "no sign-in";
    };
    return s.tenant() == null ? who : who + " · tenant " + s.tenant();
  }

  private static TestResult failure(ConnectorException e, List<TestResult.Check> checks) {
    String code = switch (e.kind()) {
      case TIMEOUT -> "prometheus.timeout";
      case RESOURCE_UNAVAILABLE -> "prometheus.unreachable";
      case DENIED -> "prometheus.denied";
      case BAD_INPUT -> "prometheus.invalid";
      default -> "prometheus.refused";
    };
    Throwable cause = e.getCause();
    return TestResult.failure(code, e.getMessage(), cause == null ? null : cause.getClass().getSimpleName())
        .withChecks(checks);
  }

  /** The build information, or a query of {@code vector(1)} where the server reports none. */
  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      PrometheusSettings s = PrometheusSettings.of(cfg);
      PrometheusApi.Call c = new PrometheusApi.Call(cfg, s, secrets, ctx);
      long started = System.nanoTime();
      Plan info = Plan.buildInfo();
      PrometheusApi.Answer a = api.fetch(c, info);
      if (a.ok()) {
        return HealthResult.healthy(ms(started));
      }
      if (a.status() != 404) {
        return HealthResult.unreachable(PrometheusApi.error(a, info, s).getMessage());
      }
      started = System.nanoTime();
      api.send(c, Plan.probe("vector(1)", ctx.clock().instant()));
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode();
    try {
      PrometheusSettings s = PrometheusSettings.of(cfg);
      out.put("server", s.target());
      if (s.tenant() != null) {
        out.put("tenant", s.tenant());
      }
      out.put("seriesPerAnswer", s.maxSeries());
      out.put("pointsPerSeries", s.maxPoints());
    } catch (ConnectorException e) {
      out.put("settings", e.getMessage());
    }
    out.put("language", "PromQL");
    out.put("reads", "prometheus_query at one time, prometheus_query_range over start, end and step; find series, "
        + "label names and values first with prometheus_series, prometheus_labels and prometheus_label_values");
    out.put("times", "ISO 8601 with an offset or relative (-1h, now); answers give ISO 8601 in UTC and values as the "
        + "server wrote them");
    out.put("metrics", "Every metric the credentials read: GateRook keeps no per-metric list");
    if (object != null && !object.isBlank()) {
      out.put("metric", QueryReader.cell(object, 256));
      out.put("next", "Call prometheus_metadata with metric " + QueryReader.cell(object, 256)
          + ", or prometheus_series with match " + QueryReader.cell(object, 256) + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    api.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    api.evict(connectionId);
  }

  @Override
  public void close() {
    api.close();
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
