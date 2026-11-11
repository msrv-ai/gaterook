package ai.msrv.gaterook.connectors.prometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** What a call becomes before anything is sent: settings, classification without I/O, the request's parts. */
class PlanTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
  private static final PrometheusConnector CONNECTOR = new PrometheusConnector(Clock.fixed(NOW, ZoneOffset.UTC));

  static ConnectionConfig config(String url, Env env, String advanced) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("url", url);
    c.putObject("auth").put("mode", "none");
    if (advanced != null) {
      c.set("advanced", JSON.readTree(advanced));
    }
    return new ConnectionConfig("conn-prom-" + env.id(), env.id() + "-prom", "prometheus", env, 1, c, Set.of(), null);
  }

  private static ConnectionConfig config() {
    return config("https://prometheus.example.com", Env.DEV, null);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".prometheus", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_plan", tool, "dev-prom", (ObjectNode) JSON.readTree(args), null);
  }

  private static Classification classify(String tool, String args) {
    return CONNECTOR.classify(call(tool, args), config(), policy(Env.DEV));
  }

  private static Plan plan(String tool, String args) throws Exception {
    return Plan.of(tool, JSON.readTree(args), PrometheusSettings.of(config()), NOW);
  }

  private static Map<String, List<String>> params(Plan p) {
    return p.params.stream().collect(Collectors.groupingBy(Map.Entry::getKey, java.util.LinkedHashMap::new,
        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
  }

  // ── Tools ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesNineReadToolsThatStayReadOnlyOnEveryEnvironment() {
    assertThat(CONNECTOR.manifest().id()).isEqualTo("prometheus");
    assertThat(CONNECTOR.manifest().name()).isEqualTo("Prometheus");
    assertThat(CONNECTOR.manifest().group()).isEqualTo("Databases");
    assertThat(CONNECTOR.manifest().icon()).isEqualTo("activity");
    assertThat(CONNECTOR.manifest().tools()).extracting(ToolDefinition::name).containsExactly("prometheus_query",
        "prometheus_query_range", "prometheus_series", "prometheus_labels", "prometheus_label_values",
        "prometheus_metadata", "prometheus_targets", "prometheus_rules", "prometheus_alerts");
    for (ToolDefinition t : CONNECTOR.manifest().tools()) {
      assertThat(t.nature()).as(t.name()).isEqualTo(ToolDefinition.Nature.READ);
      assertThat(t.mutating()).as(t.name()).isFalse();
      assertThat(t.defaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
      assertThat(t.prodDefaultMode()).as(t.name()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
      assertThat(t.annotations().readOnlyHint()).isTrue();
      assertThat(t.annotations().destructiveHint()).isFalse();
      assertThat(t.annotations().openWorldHint()).isFalse();
      assertThat(t.inputSchema().at("/properties/connection/type").asString()).isEqualTo("string");
      assertThat(t.description()).isNotBlank();
    }
  }

  // ── Settings ──────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theUrlIsHttpsOrPlainHttpToThisComputer() throws Exception {
    assertThat(PrometheusSettings.base("https://Prometheus.Example.com")).hasToString(
        "https://prometheus.example.com/");
    assertThat(PrometheusSettings.base("https://mimir.example.com/prometheus/")).hasToString(
        "https://mimir.example.com/prometheus/");
    assertThat(PrometheusSettings.base("https://grafana.example.com:8443/api/prom")).hasToString(
        "https://grafana.example.com:8443/api/prom/");
    assertThat(PrometheusSettings.base("http://127.0.0.1:9090")).hasToString("http://127.0.0.1:9090/");
    assertThat(PrometheusSettings.base("http://localhost:9090/")).hasToString("http://localhost:9090/");
    assertThat(PrometheusSettings.base("http://[::1]:9090")).hasToString("http://[::1]:9090/");
    assertThat(PrometheusSettings.base("https://10.20.30.40:9090")).hasToString("https://10.20.30.40:9090/");
    for (String bad : List.of("http://prometheus.example.com:9090", "http://10.0.0.5:9090",
        "https://reader:secret@prometheus.example.com", "https://prometheus.example.com/?tenant=a",
        "https://prometheus.example.com/#x", "https://169.254.169.254/", "https://metadata.google.internal/",
        "https://prometheus.example.com/api/v1", "https://prometheus.example.com/api/v1/query",
        "https://prometheus.example.com/a%2Fb", "ftp://prometheus.example.com", "https://0177.0.0.1/", "")) {
      assertThatThrownBy(() -> PrometheusSettings.base(bad)).as(bad).isInstanceOf(ConnectorException.class);
    }
    // Dot segments are resolved when the URL is read: what remains must still be outside /api/v1.
    assertThat(PrometheusSettings.base("https://prometheus.example.com/a/../b")).hasToString(
        "https://prometheus.example.com/b/");
    assertThatThrownBy(() -> PrometheusSettings.base("https://prometheus.example.com/x/../api/v1"))
        .isInstanceOf(ConnectorException.class);
    assertThatThrownBy(() -> PrometheusSettings.base("http://prometheus.example.com"))
        .hasMessageContaining("Plain http:// reaches only this computer");
  }

  @Test
  void credentialsTenantAndLimitsAreChecked() throws Exception {
    ObjectNode c = (ObjectNode) JSON.readTree("{\"url\": \"https://mimir.example.com/prometheus\", \"tenant\": "
        + "\"team-a|team-b\", \"auth\": {\"mode\": \"basic\", \"username\": \"reader\"}, \"advanced\": "
        + "{\"maxSeries\": 50, \"maxPoints\": 99999, \"maxResponseMb\": 2}}");
    PrometheusSettings s = PrometheusSettings.of(new ConnectionConfig("c", "n", "prometheus", Env.DEV, 1, c,
        Set.of("/auth/secret"), null));
    assertThat(s.tenant()).isEqualTo("team-a|team-b");
    assertThat(s.auth()).isEqualTo(PrometheusSettings.Auth.BASIC);
    assertThat(s.username()).isEqualTo("reader");
    assertThat(s.maxSeries()).isEqualTo(50);
    assertThat(s.maxPoints()).isEqualTo(11_000);
    assertThat(s.maxResponseBytes()).isEqualTo(2L << 20);
    assertThat(s.target()).isEqualTo("mimir.example.com/prometheus");
    PrometheusSettings defaults = PrometheusSettings.of(config());
    assertThat(defaults.maxSeries()).isEqualTo(1_000);
    assertThat(defaults.maxResponseBytes()).isEqualTo(16L << 20);
    assertThat(defaults.auth()).isEqualTo(PrometheusSettings.Auth.NONE);
    for (String bad : List.of("{\"url\": \"https://p.example.com\", \"tenant\": \"team a\"}",
        "{\"url\": \"https://p.example.com\", \"auth\": {\"mode\": \"basic\"}}",
        "{\"url\": \"https://p.example.com\", \"auth\": {\"mode\": \"basic\", \"username\": \"a:b\"}}",
        "{\"url\": \"https://p.example.com\", \"auth\": {\"mode\": \"oauth\"}}",
        "{\"url\": \"https://p.example.com\", \"caCertificate\": \"not a certificate\"}", "{}")) {
      ConnectionConfig cfg = new ConnectionConfig("c", "n", "prometheus", Env.DEV, 1, JSON.readTree(bad), Set.of(),
          null);
      assertThatThrownBy(() -> PrometheusSettings.of(cfg)).as(bad).isInstanceOf(ConnectorException.class);
      assertThat(CONNECTOR.classify(call("prometheus_query", "{\"query\": \"up\"}"), cfg, policy(Env.DEV)).ruleId())
          .isEqualTo("dev.prometheus.bad-config");
    }
  }

  // ── Classification ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void everyToolReadsWhenItsArgumentsMakeARequest() {
    Map<String, String> calls = Map.of(
        "prometheus_query", "{\"query\": \"sum by (job) (rate(http_requests_total[5m]))\", \"time\": \"-5m\"}",
        "prometheus_query_range", "{\"query\": \"up\", \"start\": \"-6h\", \"step\": \"1m\"}",
        "prometheus_series", "{\"match\": [\"up\", \"{job=\\\"api\\\"}\"], \"start\": \"now-30m\"}",
        "prometheus_labels", "{}",
        "prometheus_label_values", "{\"label\": \"__name__\", \"match\": [\"{job=\\\"api\\\"}\"]}",
        "prometheus_metadata", "{\"metric\": \"http_requests_total\"}",
        "prometheus_targets", "{\"state\": \"any\", \"scrapePool\": \"node\"}",
        "prometheus_rules", "{\"type\": \"alert\", \"names\": [\"HighErrorRate\"], \"groups\": [\"api\"]}",
        "prometheus_alerts", "{\"state\": \"firing\"}");
    for (Map.Entry<String, String> e : calls.entrySet()) {
      Classification c = classify(e.getKey(), e.getValue());
      assertThat(c.kind()).as(e.getKey() + ": " + c.reason()).isEqualTo(Classification.Kind.READ);
      assertThat(CONNECTOR.classify(call(e.getKey(), e.getValue()), config("https://prometheus.example.com",
          Env.PROD, null), policy(Env.PROD)).kind()).isEqualTo(Classification.Kind.READ);
    }
  }

  @Test
  void whatCantBecomeARequestIsDeniedWithItsRule() {
    record Case(String tool, String args, String rule) {}
    for (Case c : List.of(
        new Case("prometheus_query", "{\"query\": \"   \"}", "bad-argument"),
        new Case("prometheus_query", "{\"query\": \"up\\u0000\"}", "bad-argument"),
        new Case("prometheus_query", "{\"query\": \"up\\u001b[31m\"}", "bad-argument"),
        new Case("prometheus_query", "{\"query\": \"" + "x".repeat(8_193) + "\"}", "bad-argument"),
        new Case("prometheus_query", "{\"query\": \"up\", \"time\": \"yesterday\"}", "time"),
        new Case("prometheus_query", "{\"query\": \"up\", \"time\": \"1700000000\"}", "time"),
        new Case("prometheus_query", "{\"query\": \"up\", \"limit\": 0}", "bad-argument"),
        new Case("prometheus_query_range", "{\"query\": \"up\", \"start\": \"now\", \"end\": \"-1h\"}", "range"),
        new Case("prometheus_query_range", "{\"query\": \"up\", \"step\": \"fast\"}", "bad-argument"),
        new Case("prometheus_query_range", "{\"query\": \"up\", \"step\": \"0s\"}", "bad-argument"),
        new Case("prometheus_query_range", "{\"query\": \"up\", \"start\": \"-30d\", \"step\": \"15s\"}",
            "too-many-points"),
        new Case("prometheus_series", "{}", "bad-argument"),
        new Case("prometheus_series", "{\"match\": []}", "bad-argument"),
        new Case("prometheus_series", "{\"match\": [\"\"]}", "bad-argument"),
        new Case("prometheus_label_values", "{}", "bad-argument"),
        new Case("prometheus_label_values", "{\"label\": \"job\\n\"}", "bad-argument"),
        new Case("prometheus_targets", "{\"state\": \"all\"}", "bad-argument"),
        new Case("prometheus_rules", "{\"type\": \"recording\"}", "bad-argument"),
        new Case("prometheus_alerts", "{\"state\": \"resolved\"}", "bad-argument"),
        new Case("prometheus_admin_tsdb", "{}", "unknown-tool"))) {
      Classification cl = classify(c.tool(), c.args());
      assertThat(cl.kind()).as(c.args()).isEqualTo(Classification.Kind.DENY);
      assertThat(cl.ruleId()).as(c.args()).isEqualTo("dev.prometheus." + c.rule());
      assertThat(cl.reason()).isNotBlank();
    }
  }

  @Test
  void aStepThatAsksTooManyPointsIsRefusedWithTheSmallestThatFits() throws Exception {
    Classification c = classify("prometheus_query_range", "{\"query\": \"up\", \"start\": \"2026-11-01T00:00:00Z\", "
        + "\"end\": \"2026-11-08T00:00:00Z\", \"step\": \"15s\"}");
    assertThat(c.reason()).contains("40321 points per series", "at most 11000");
    assertThat(c.hint()).isEqualTo("Use a step of at least 55s, or shorten the range.");
    assertThat(classify("prometheus_query_range", "{\"query\": \"up\", \"start\": \"2026-11-01T00:00:00Z\", "
        + "\"end\": \"2026-11-08T00:00:00Z\", \"step\": \"55s\"}").kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("prometheus_query_range", "{\"query\": \"up\", \"start\": \"2026-11-01T00:00:00Z\", "
        + "\"end\": \"2026-11-08T00:00:00Z\", \"step\": \"54s\"}").kind()).isEqualTo(Classification.Kind.DENY);
    // The connection's own limit is lower than Prometheus'.
    ConnectionConfig tight = config("https://prometheus.example.com", Env.DEV, "{\"maxPoints\": 100}");
    Classification t = CONNECTOR.classify(call("prometheus_query_range", "{\"query\": \"up\", \"step\": \"30s\"}"),
        tight, policy(Env.DEV));
    assertThat(t.ruleId()).isEqualTo("dev.prometheus.too-many-points");
    assertThat(t.hint()).contains("at least 37s");
  }

  @Test
  void withoutAStepARangeGetsAboutAHundredAndTwentyRoundPoints() throws Exception {
    Plan hour = plan("prometheus_query_range", "{\"query\": \"up\"}");
    assertThat(hour.start).isEqualTo(NOW.minus(Duration.ofHours(1)));
    assertThat(hour.end).isEqualTo(NOW);
    assertThat(hour.step).isEqualTo(Duration.ofSeconds(30));
    assertThat(Durations.points(Duration.ofHours(1), hour.step)).isEqualTo(121);
    assertThat(plan("prometheus_query_range", "{\"query\": \"up\", \"start\": \"-7d\"}").step)
        .isEqualTo(Duration.ofHours(2));
    assertThat(plan("prometheus_query_range", "{\"query\": \"up\", \"start\": \"-30d\"}").step)
        .isEqualTo(Duration.ofHours(6));
    assertThat(plan("prometheus_query_range", "{\"query\": \"up\", \"start\": \"-1m\"}").step)
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(Durations.defaultStep(Duration.ofDays(3_650), 11_000)).isEqualTo(Duration.ofSeconds(2_628_000));
    // Never more points than the connection allows.
    assertThat(Durations.points(Duration.ofHours(1), Durations.defaultStep(Duration.ofHours(1), 10)))
        .isLessThanOrEqualTo(10);
  }

  @Test
  void durationsAreReadAsPrometheusWritesThem() {
    assertThat(Durations.parse("1h30m")).isEqualTo(Duration.ofMinutes(90));
    assertThat(Durations.parse("15s")).isEqualTo(Duration.ofSeconds(15));
    assertThat(Durations.parse("500ms")).isEqualTo(Duration.ofMillis(500));
    assertThat(Durations.parse("5m")).isEqualTo(Duration.ofMinutes(5));
    assertThat(Durations.parse("1d12h")).isEqualTo(Duration.ofHours(36));
    assertThat(Durations.parse("2w")).isEqualTo(Duration.ofDays(14));
    assertThat(Durations.parse("15")).isEqualTo(Duration.ofSeconds(15));
    assertThat(Durations.parse("0.5")).isEqualTo(Duration.ofMillis(500));
    for (String bad : List.of("", "fast", "-5m", "5m1h", "1.5m", "0", "0s", "1e3", "5 m", "1h1h")) {
      assertThat(Durations.parse(bad)).as(bad).isNull();
    }
    assertThat(Durations.seconds(Duration.ofSeconds(60))).isEqualTo("60");
    assertThat(Durations.seconds(Duration.ofMillis(1_500))).isEqualTo("1.5");
    assertThat(Durations.human(Duration.ofSeconds(5_430))).isEqualTo("1h30m30s");
  }

  // ── The requests ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void relativeTimesBecomeAbsoluteAndTheServerIsAskedForOneMoreSeriesThanShown() throws Exception {
    Plan q = plan("prometheus_query", "{\"query\": \"up\", \"time\": \"-5m\", \"limit\": 20}");
    assertThat(params(q)).containsExactly(Map.entry("query", List.of("up")),
        Map.entry("time", List.of("2026-11-11T09:55:00Z")), Map.entry("limit", List.of("21")));
    assertThat(q.arguments.path("time").asString()).isEqualTo("2026-11-11T09:55:00Z");
    assertThat(q.evaluates).isTrue();
    assertThat(q.path()).isEqualTo("api/v1/query");

    Plan r = plan("prometheus_query_range", "{\"query\": \"rate(x[5m])\", \"start\": \"-2h\", \"end\": \"-1h\", "
        + "\"step\": \"1m\"}");
    assertThat(params(r)).containsEntry("start", List.of("2026-11-11T08:00:00Z"))
        .containsEntry("end", List.of("2026-11-11T09:00:00Z")).containsEntry("step", List.of("60"))
        .containsEntry("limit", List.of("1001"));
    // An approval stores absolute times: planned again later, the request is the same.
    Plan again = Plan.of("prometheus_query_range", r.arguments, PrometheusSettings.of(config()),
        NOW.plus(Duration.ofHours(5)));
    assertThat(again.params).isEqualTo(r.params);

    Plan s = plan("prometheus_series", "{\"match\": [\"up\", \"{job=\\\"api\\\"}\"], \"limit\": 5}");
    assertThat(params(s)).containsEntry("match[]", List.of("up", "{job=\"api\"}"))
        .containsEntry("start", List.of("2026-11-11T09:00:00Z")).containsEntry("limit", List.of("6"));
    assertThat(s.evaluates).isFalse();
    assertThat(s.queryString(null)).startsWith("?match%5B%5D=up&match%5B%5D=%7Bjob%3D%22api%22%7D&start=");

    Plan rules = plan("prometheus_rules", "{\"type\": \"alert\", \"names\": [\"A\", \"B\"], \"groups\": [\"g\"]}");
    assertThat(params(rules)).containsEntry("type", List.of("alert")).containsEntry("rule_name[]", List.of("A", "B"))
        .containsEntry("rule_group[]", List.of("g")).containsEntry("exclude_alerts", List.of("true"));
    Plan meta = plan("prometheus_metadata", "{\"metric\": \"up\", \"limit\": 3}");
    assertThat(params(meta)).containsEntry("metric", List.of("up")).containsEntry("limit", List.of("4"));
    assertThat(plan("prometheus_targets", "{}").state).isEqualTo("active");
    assertThat(plan("prometheus_alerts", "{}").params).isEmpty();
  }

  @Test
  void labelNamesOutsideTheClassicSetAreEncodedAsPrometheus3ReadsThem() throws Exception {
    assertThat(Plan.labelSegment("job")).isEqualTo("job");
    assertThat(Plan.labelSegment("__name__")).isEqualTo("__name__");
    assertThat(Plan.labelSegment("http.status_code")).isEqualTo("U__http_2e_status__code");
    assertThat(Plan.labelSegment("2xx")).isEqualTo("U___32_xx");
    assertThat(Plan.labelSegment("U__x")).isEqualTo("U__U____x");
    assertThat(Plan.labelSegment("région")).isEqualTo("U__r_e9_gion");
    assertThat(Plan.labelSegment("a/b")).isEqualTo("U__a_2f_b");
    assertThat(Plan.labelSegment("emoji😀")).isEqualTo("U__emoji_1f600_");
    assertThat(plan("prometheus_label_values", "{\"label\": \"http.method\"}").path())
        .isEqualTo("api/v1/label/U__http_2e_method/values");
    assertThat(plan("prometheus_label_values", "{\"label\": \"job\"}").path()).isEqualTo("api/v1/label/job/values");
  }

  @Test
  void requestsStayOnTheServersUrlAndItsReadPaths() throws Exception {
    PrometheusSettings s = PrometheusSettings.of(config("https://mimir.example.com/prometheus", Env.DEV, null));
    Plan p = Plan.of("prometheus_label_values", JSON.readTree("{\"label\": \"a/../../admin\"}"), s, NOW);
    assertThat(PrometheusApi.uri(s, p, null).toString())
        .startsWith("https://mimir.example.com/prometheus/api/v1/label/U__a_2f__2e__2e__2f__2e__2e__2f_admin/values?");
    Plan q = Plan.of("prometheus_query", JSON.readTree("{\"query\": \"up # ?x=1&y=/../-/quit\"}"), s, NOW);
    String uri = PrometheusApi.uri(s, q, "900ms").toString();
    assertThat(uri).startsWith("https://mimir.example.com/prometheus/api/v1/query?query=up+%23+%3Fx%3D1%26y%3D%2F..%2F-"
        + "%2Fquit&time=").endsWith("&timeout=900ms");
    for (Plan.Endpoint e : Plan.Endpoint.values()) {
      assertThat(e.path).startsWith("api/v1/").doesNotContain("admin", "write", "-/", "..");
    }
  }

  @Test
  void hintsSayWhatTheConnectionCantKeepFromAgents() throws Exception {
    PrometheusSettings open = PrometheusSettings.of(config());
    List<TestResult.Hint> prod = PrometheusConnector.hints(Env.PROD, open);
    assertThat(prod).extracting(TestResult.Hint::code).containsExactly("prometheus.no-auth", "prometheus.read-api",
        "prometheus.metric-scope");
    assertThat(prod.getFirst().severity()).isEqualTo(TestResult.Severity.WARNING);
    assertThat(PrometheusConnector.hints(Env.DEV, open).getFirst().severity()).isEqualTo(TestResult.Severity.INFO);
    PrometheusSettings local = PrometheusSettings.of(config("http://127.0.0.1:9090", Env.PROD, null));
    assertThat(PrometheusConnector.hints(Env.PROD, local)).extracting(TestResult.Hint::code)
        .doesNotContain("prometheus.no-auth");
  }

  @Test
  void describeSaysWhatTheConnectionReadsWithoutIo() throws Exception {
    JsonNode d = CONNECTOR.describe(config(), new TestSecrets(), TestCtx.of(), "http_requests_total").orElseThrow();
    assertThat(d.path("server").asString()).isEqualTo("prometheus.example.com");
    assertThat(d.path("language").asString()).isEqualTo("PromQL");
    assertThat(d.path("next").asString()).contains("prometheus_metadata with metric http_requests_total");
  }
}
