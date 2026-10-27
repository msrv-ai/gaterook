package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The manifest, the tools and everything decided without I/O: classification (report 07 §3.5), the exact action a
 * person approves, the published descriptions.
 */
class LogsConnectorTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:30:00Z");
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final LogsConnector connector = new LogsConnector(cloud, Clock.fixed(NOW, ZoneOffset.UTC));

  @AfterEach
  void close() {
    cloud.close();
  }

  @Test
  void manifestIsTheCatalogCard() {
    ConnectorManifest m = connector.manifest();

    assertThat(m.id()).isEqualTo("logs");
    assertThat(m.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
    assertThat(m.name()).isEqualTo("Logs");
    assertThat(m.group()).isEqualTo("Logs");
    assertThat(m.icon()).isEqualTo("scroll-text");
    assertThat(m.configSchema().path("type").asString()).isEqualTo("object");
    assertThat(m.configSchema().path("x-variants").size()).isEqualTo(4);
    assertThat(m.policySchema().path("x-policy-section").asString()).isEqualTo("logs");
  }

  @Test
  void publishesTheToolsOfTheKit() {
    List<ToolDefinition> tools = connector.manifest().tools();

    assertThat(tools).extracting(ToolDefinition::name).containsExactly("logs_search", "logs_tail",
        "opensearch_indices", "opensearch_query", "opensearch_request");
    assertTool(tools.get(0), "logs_search", Mode.READ_ONLY, Mode.READ_ONLY, true);
    assertTool(tools.get(1), "logs_tail", Mode.OFF, Mode.OFF, false);
    assertTool(tools.get(2), "opensearch_indices", Mode.READ_ONLY, Mode.READ_ONLY, true);
    assertTool(tools.get(3), "opensearch_query", Mode.READ_ONLY, Mode.READ_ONLY, true);
    ToolDefinition request = tools.get(4);
    assertThat(request.nature()).isEqualTo(Nature.MUTATION);
    assertThat(request.defaultMode()).isEqualTo(Mode.APPROVAL);
    assertThat(request.prodDefaultMode()).isEqualTo(Mode.APPROVAL);
    assertThat(request.readOnlyModeAllowed()).isFalse();
    assertThat(request.outputSchema()).as("Approval handles are not REST responses").isNull();
  }

  @Test
  void openSearchToolsAreOfferedOnlyToCompatibleConnections() {
    ConnectionConfig logs = Configs.openSearch("http://127.0.0.1:9200");
    ConnectionConfig full = Configs.of("search", Env.DEV, """
        {"backend":"opensearch","url":"http://127.0.0.1:9200","auth":{"mode":"none"}}""");
    ConnectionConfig loki = Configs.loki("http://127.0.0.1:3100");
    for (String tool : List.of("opensearch_indices", "opensearch_query", "opensearch_request")) {
      assertThat(connector.supportsTool(tool, loki)).as(tool + " on Loki").isFalse();
      assertThat(connector.supportsTool(tool, full)).as(tool + " on OpenSearch by default").isTrue();
      assertThat(connector.supportsTool(tool, logs)).as(tool + " on scoped OpenSearch")
          .isEqualTo(tool.equals("opensearch_indices"));
    }
    assertThat(connector.supportsTool("logs_search", loki)).isTrue();
    assertThat(connector.supportsTool("logs_search", full)).isTrue();
  }

  private static void assertTool(ToolDefinition t, String name, Mode dev, Mode prod, boolean idempotent) {
    assertThat(t.name()).isEqualTo(name);
    assertThat(t.nature()).as(name).isEqualTo(Nature.READ);
    assertThat(t.defaultMode()).as(name + " dev/stage mode").isEqualTo(dev);
    assertThat(t.prodDefaultMode()).as(name + " prod mode").isEqualTo(prod);
    assertThat(t.outputSchema()).as(name + " outputSchema").isNotNull();
    assertThat(t.inputSchema().at("/properties/connection/type").asString()).as(name).isEqualTo("string");
    assertThat(t.inputSchema().at("/required").toString()).as(name).contains("\"connection\"");
    assertThat(t.annotations().readOnlyHint()).isTrue();
    assertThat(t.annotations().destructiveHint()).isFalse();
    assertThat(t.annotations().idempotentHint()).isEqualTo(idempotent);
    assertThat(t.risk().untrustedContent()).as("log lines are untrusted content").isTrue();
  }

  @Test
  void descriptionsNameEachConnectionsLanguage() {
    EndpointView view = new EndpointView("dev", Env.DEV, List.of(Configs.loki("http://loki.dev.internal:3100"),
        Configs.gcp()), Map.of(), List.of());

    List<ToolDefinition> tools = connector.tools(view);

    assertThat(tools.get(0).description()).contains("app-logs: LogQL (Loki), e.g. {app=\"api\"} |= \"ERROR\"")
        .contains("prod-logs: Logging query language (GCP Cloud Logging), e.g. severity>=ERROR");
    assertThat(tools.get(0).inputSchema()).isEqualTo(connector.manifest().tools().get(0).inputSchema());
    assertThat(connector.tools(new EndpointView("e", Env.DEV, List.of(), Map.of(), List.of())))
        .isEqualTo(connector.manifest().tools());
  }

  @ParameterizedTest(name = "{0} {1} → {2}")
  @CsvSource(delimiter = ';', quoteCharacter = '~', textBlock = """
      loki       ; {"query":"{app=\\"api\\"}"}                                              ; READ
      loki       ; {"query":"{app=\\"api\\"}","source":"anything"}                          ; dev.logs.source_not_allowed
      loki       ; {"query":"x","from":"1761136200"}                                         ; dev.logs.range
      loki       ; {"query":"x","from":"-10d"}                                               ; dev.logs.range
      loki       ; {"query":"x","from":"now","to":"-1h"}                                     ; dev.logs.range
      loki       ; {"query":"x","from":"2026-10-22T12:00:00"}                                ; dev.logs.range
      loki       ; {"query":"sum by (msg) (count_over_time({app=\\"api\\"} | label_format msg=\\"{{__line__}}\\" [30d]))"} ; dev.logs.range
      loki       ; {"query":"count_over_time({app=\\"api\\"}[1w1h])"}                         ; dev.logs.range
      loki       ; {"query":"count_over_time({app=\\"api\\"}[7d])"}                           ; READ
      loki       ; {"query":"rate({app=\\"api\\"} |= \\"x[30d]\\" |= `[30d]` [5m])"}          ; READ
      loki       ; {"query":"count_over_time({app=\\"api\\"}[1h] offset 30d)"}                ; READ
      cloudwatch ; {"query":"SOURCE logGroups(namePrefix: ['/']) | fields @message"}       ; dev.logs.cw_source_command
      cloudwatch ; {"query":"fields @message | source  logGroups()"}                       ; dev.logs.cw_source_command
      cloudwatch ; {"query":"filter status >= 500 | join type=inner left=a right=b where a.id=b.id (SOURCE '/aws/x') | fields b.token"} ; dev.logs.cw_source_command
      cloudwatch ; {"query":"filter requestId in (\\n  SOURCE '/aws/lambda/db' | fields requestId\\n) | fields @message"} ; dev.logs.cw_source_command
      cloudwatch ; {"query":"fields svc | appendcols ( SOURCE lg | stats avg(latency) as l by svc )"} ; dev.logs.cw_source_command
      cloudwatch ; {"query":"# the other team's groups\\nSOURCE logGroups(namePrefix: ['/']) | fields @message"} ; dev.logs.cw_source_command
      cloudwatch ; {"query":"fields @message | # later\\n source logGroups()"}             ; dev.logs.cw_source_command
      cloudwatch ; {"query":"fields action | lookup user_data id OUTPUT name, email"}      ; dev.logs.cw_lookup
      cloudwatch ; {"query":"fields @timestamp, @message | unmask(@message)"}              ; dev.logs.cw_unmask
      cloudwatch ; {"query":"fields source, @message | filter source = \\"api\\" | stats count(*) by source"} ; READ
      cloudwatch ; {"query":"filter @message like /ERROR/","source":"/aws/ecs/worker"}       ; READ
      cloudwatch ; {"query":"x","source":"/aws/rds/secret"}                                  ; dev.logs.source_not_allowed
      opensearch ; {"query":"{\\"terms\\":{\\"message\\":{\\"index\\":\\"secrets\\"}}}"}     ; dev.logs.os_dsl
      opensearch ; {"query":"  {\\"match_all\\":{}}"}                                        ; dev.logs.os_dsl
      opensearch ; {"query":"log.level:error AND message:timeout","source":"logs-app-*"}     ; READ
      gcp        ; {"query":"severity>=ERROR","source":"projects/other"}                    ; prod.logs.source_not_allowed
      gcp        ; {"query":"severity>=ERROR","from":"-2d"}                                  ; prod.logs.range
      gcp        ; {"query":"severity>=ERROR","from":"-23h"}                                 ; READ
      gcp        ; {"query":"severity>=ERROR) AND timestamp>=\\"2015-01-01T00:00:00Z\\" --"} ; prod.logs.gcp_comment
      gcp        ; {"query":"textPayload:\\"--dry-run\\""}                                   ; prod.logs.gcp_comment
      """)
  void classifiesTheSearchTableOfReport07(String backend, String arguments, String expected) {
    ConnectionConfig cfg = config(backend);
    Classification c = connector.classify(Configs.call("logs_search", arguments), cfg,
        Configs.policy(cfg.env(), cfg.env() == Env.PROD ? TestCtx.prod() : TestCtx.dev()));

    if (expected.equals("READ")) {
      assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    } else {
      assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).isEqualTo(expected);
      assertThat(c.reason()).isNotBlank();
    }
  }

  @Test
  void filterPatternsAreLimitedTo1024Characters() {
    ConnectionConfig cw = Configs.cloudWatch(true);
    String pattern = "\"" + "x".repeat(1_100) + "\"";

    Classification tail = connector.classify(Configs.call("logs_tail", "{\"query\":" + pattern + "}"), cw,
        Configs.policy(Env.DEV, TestCtx.dev()));
    Classification insights = connector.classify(Configs.call("logs_search", "{\"query\":" + pattern + "}"), cw,
        Configs.policy(Env.DEV, TestCtx.dev()));

    assertThat(tail.ruleId()).isEqualTo("dev.logs.query_too_long");
    assertThat(insights.kind()).as("Insights takes 10,000").isEqualTo(Classification.Kind.READ);
  }

  @Test
  void tailSinceIsCheckedLikeARange() {
    ConnectionConfig cfg = Configs.loki("http://127.0.0.1:3100");
    assertThat(connector.classify(Configs.call("logs_tail", "{\"query\":\"x\",\"since\":\"-5m\"}"), cfg,
        Configs.policy(Env.DEV, TestCtx.dev())).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(connector.classify(Configs.call("logs_tail", "{\"query\":\"x\",\"since\":\"2030-01-01T00:00:00Z\"}"),
        cfg, Configs.policy(Env.DEV, TestCtx.dev())).ruleId()).isEqualTo("dev.logs.range");
  }

  @Test
  void neverClassifiesAMutation() {
    String[] args = {"{}", "{\"query\":\"x\"}", "{\"query\":\"DELETE FROM logs\"}", "{\"query\":\"x\",\"limit\":999999}"};
    for (String backend : List.of("loki", "gcp", "cloudwatch", "opensearch")) {
      for (String tool : List.of("logs_search", "logs_tail")) {
        for (String a : args) {
          ConnectionConfig cfg = config(backend);
          Classification c = connector.classify(Configs.call(tool, a), cfg, Configs.policy(cfg.env(), TestCtx.dev()));
          assertThat(c.kind()).as(backend + " " + tool + " " + a).isNotEqualTo(Classification.Kind.MUTATION);
        }
      }
    }
    ConnectionConfig cfg = config("loki");
    assertThat(connector.classify(Configs.call("logs_delete", "{}"), cfg, Configs.policy(Env.DEV, TestCtx.dev()))
        .ruleId()).isEqualTo("dev.logs.unknown_tool");
    assertThatThrownBy(() -> connector.execute(Configs.call("logs_delete", "{}"), cfg, new TestSecrets(),
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
  }

  @Test
  void unusableSettingsAreDeniedWithWhatToFix() {
    ConnectionConfig metadata = Configs.of("bad", Env.DEV, """
        {"backend":"loki","url":"http://169.254.169.254/latest"}""");
    ConnectionConfig noGroups = Configs.of("bad", Env.DEV, """
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":[],"credentials":{"source":"profile",
         "profile":"default"}}""");

    Classification c = connector.classify(Configs.call("logs_search", "{\"query\":\"x\"}"), metadata,
        Configs.policy(Env.DEV, TestCtx.dev()));
    assertThat(c.ruleId()).isEqualTo("dev.logs.config_invalid");
    assertThat(c.reason()).contains("metadata");
    assertThat(connector.classify(Configs.call("logs_search", "{\"query\":\"x\"}"), noGroups,
        Configs.policy(Env.DEV, TestCtx.dev())).reason()).contains("log groups");
    assertThat(connector.test(metadata, new TestSecrets(), TestCtx.of()).error().code()).isEqualTo("logs.config");
  }

  @Test
  void theApprovedActionIsTheQueryAsSentWithAbsoluteTimes() {
    ActionPreview gcp = connector.describeAction(Configs.call("logs_search",
        "{\"query\":\"severity>=ERROR\",\"from\":\"-1h\",\"limit\":100}"), Configs.gcp());

    assertThat(gcp.kind()).isEqualTo(ActionPreview.Kind.TEXT);
    assertThat(gcp.text()).isEqualTo("""
        logs_search · prod-logs (GCP Cloud Logging · projects/acme-prod · prod)
        resources: projects/acme-prod
        filter: (resource.type="k8s_container")
        AND (severity>=ERROR)
        AND timestamp>="2026-10-22T11:30:00Z"
        AND timestamp<="2026-10-22T12:30:00Z"
        range: 2026-10-22T11:30:00Z → 2026-10-22T12:30:00Z (1 h) · limit 100""");
    assertThat(gcp.payload().toString()).isEqualTo("{\"tool\":\"logs_search\",\"arguments\":{\"query\":"
        + "\"severity>=ERROR\",\"from\":\"2026-10-22T11:30:00Z\",\"to\":\"2026-10-22T12:30:00Z\",\"limit\":100}}");

    ActionPreview insights = connector.describeAction(Configs.call("logs_search",
        "{\"query\":\"filter @message like /timeout/\"}"), Configs.cloudWatch(true));
    assertThat(insights.text()).contains("query: fields @timestamp, @message, @logStream, @log | filter @message like "
        + "/timeout/ | sort @timestamp desc").contains("log groups: /aws/ecs/api, /aws/ecs/worker");
    assertThat(connector.describeAction(Configs.call("logs_tail", "{\"query\":\"ERROR\",\"wait\":5}"),
        Configs.cloudWatch(true)).text()).contains("log group: /aws/ecs/api", "filter pattern: ERROR",
        "since: 2026-10-22T12:30:00Z · waits up to 5 s · limit 100");
    // A tail of Cloud Logging has no upper bound in what the approver reads: the window moves with each poll.
    assertThat(connector.describeAction(Configs.call("logs_tail", "{\"query\":\"severity>=ERROR\",\"wait\":5}"),
        Configs.gcp()).text()).contains("""
            filter: (resource.type="k8s_container")
            AND (severity>=ERROR)
            AND timestamp>="2026-10-22T12:30:00Z"
            since:""").doesNotContain("timestamp<=");
  }

  @Test
  void insightsQueriesGetFieldsAndOrderOnlyWhenTheyHaveNone() {
    assertThat(CloudWatchBackend.insightsQuery("")).isEqualTo("fields @timestamp, @message, @logStream, @log | sort "
        + "@timestamp desc");
    assertThat(CloudWatchBackend.insightsQuery("filter @message like /x/")).isEqualTo("fields @timestamp, @message, "
        + "@logStream, @log | filter @message like /x/ | sort @timestamp desc");
    assertThat(CloudWatchBackend.insightsQuery("fields @message | sort @timestamp asc"))
        .isEqualTo("fields @message | sort @timestamp asc");
    assertThat(CloudWatchBackend.insightsQuery("stats count(*) by bin(5m)")).isEqualTo("stats count(*) by bin(5m)");
  }

  private static ConnectionConfig config(String backend) {
    return switch (backend) {
      case "loki" -> Configs.loki("http://loki.dev.internal:3100");
      case "gcp" -> Configs.gcp();
      case "cloudwatch" -> Configs.cloudWatch(true);
      default -> Configs.openSearch("https://search.dev.internal:9200");
    };
  }
}
