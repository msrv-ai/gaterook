package ai.msrv.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Azure connector against a fake Entra ID, Resource Manager, Resource Graph, Azure Monitor and Log Analytics:
 * Test connection and its pins, Resource Graph with the Cloud policy, classified Resource Manager calls, Azure Monitor,
 * and the shared log tools over the connection's workspace.
 */
class AzureConnectorTest {

  private static final String SUB = FakeAzure.SUB;
  private static final String WORKSPACE = "5b2f8c1e-0a3d-4e6f-9b7a-1c2d3e4f5a6b";
  private static final String SITE = "/subscriptions/" + SUB + "/resourceGroups/stage-app/providers/Microsoft.Web/"
      + "sites/api";
  private FakeAzure azure;
  private AzureConnector connector;
  private final ConnectionConfig cfg = Configs.stage();

  @BeforeEach
  void start() throws Exception {
    azure = new FakeAzure();
    connector = new AzureConnector(azure.cloud, Clock.systemUTC());
    azure.on("GET /subscriptions", r -> FakeAzure.json(200, "{\"value\":[{\"subscriptionId\":\"" + SUB + "\","
        + "\"displayName\":\"Stage\",\"state\":\"Enabled\",\"tenantId\":\"" + FakeAzure.TENANT + "\"}]}"));
    azure.on("POST /providers/Microsoft.ResourceGraph/resources", r -> FakeAzure.json(200,
        "{\"totalRecords\":1,\"count\":1,\"data\":[{\"count_\":42}],\"resultTruncated\":\"false\"}"));
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.Authorization/permissions", r -> FakeAzure.json(200,
        "{\"value\":[{\"actions\":[\"*/read\"],\"notActions\":[]}]}"));
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.AlertsManagement/alerts", r -> FakeAzure.json(200,
        "{\"value\":[]}"));
  }

  @AfterEach
  void stop() {
    connector.close();
    azure.close();
  }

  /** A config of {@link #cfg} with what Test connection recorded. */
  private ConnectionConfig tested(ConnectionConfig c) {
    return new ConnectionConfig(c.id(), c.name(), c.type(), c.env(), c.version(), c.config(), c.secretFields(),
        TestCtx.JSON.createObjectNode().put("tenant", FakeAzure.TENANT));
  }

  private ToolResult run(ConnectionConfig c, PolicyView policy, String tool, String args, TestCtx ctx)
      throws ConnectorException {
    ToolCall call = Configs.call(tool, c.name(), args);
    Classification k = connector.classify(call, c, policy);
    assertThat(k.kind()).as(k.reason()).isNotEqualTo(Classification.Kind.DENY);
    return connector.execute(call, c, Configs.secrets(), ctx);
  }

  private ToolResult run(String tool, String args) throws ConnectorException {
    return run(cfg, Configs.policy(Env.STAGE), tool, args, TestCtx.of());
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aServicePrincipalTestsItsTenantSubscriptionsAndPermissions() {
    TestCtx ctx = TestCtx.of();
    TestResult r = connector.test(cfg, Configs.secrets(), ctx);

    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo(FakeAzure.CLIENT);
    assertThat(r.metadataUpdate().path("tenant").asString()).isEqualTo(FakeAzure.TENANT);
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(r.checks()).extracting(TestResult.Check::name).contains("sign-in", "subscriptions", "Resource Graph",
        "permissions on " + SUB, "alerts", "Log Analytics sign-in");
    assertThat(r.checks()).filteredOn(c -> c.name().equals("Resource Graph")).first()
        .satisfies(c -> assertThat(c.detail()).isEqualTo("42 resources"));
    assertThat(r.details().at("/subscriptions/" + SUB).asString()).isEqualTo("Stage");
    assertThat(ctx.taints).isNotEmpty();
    RecordedRequest token = azure.requests.getFirst();
    assertThat(token.getUrl().encodedPath()).isEqualTo("/" + FakeAzure.TENANT + "/oauth2/v2.0/token");
    assertThat(token.getBody().utf8()).contains("client_secret=cnry-azure-client-secret-0001");
  }

  @Test
  void aSignInOfAnotherTenantFailsTheTest() {
    azure.tokenTenant = "11111111-2222-4333-8444-555555555555";
    TestResult r = connector.test(cfg, Configs.secrets(), TestCtx.of());

    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("azure.wrong-tenant");
  }

  @Test
  void aSubscriptionTheSignInCantSeeFailsTheTest() {
    azure.on("GET /subscriptions", r -> FakeAzure.json(200, "{\"value\":[]}"));
    TestResult r = connector.test(cfg, Configs.secrets(), TestCtx.of());

    assertThat(r.error().code()).isEqualTo("azure.subscription");
    assertThat(r.error().message()).contains("Assign the Reader role");
  }

  @Test
  void anIdentityThatCanWriteGetsAWarningOnProd() {
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.Authorization/permissions", r -> FakeAzure.json(200,
        "{\"value\":[{\"actions\":[\"*\"],\"notActions\":[\"Microsoft.Authorization/*/Delete\"]}]}"));
    TestResult r = connector.test(Configs.prod(), Configs.secrets(), TestCtx.of().env(Env.PROD));

    assertThat(r.ok()).isTrue();
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("azure.can-write", "azure.client-secret");
    assertThat(r.hints()).filteredOn(h -> h.code().equals("azure.can-write")).first()
        .satisfies(h -> assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void theAzureCliRunsOnlyAfterThePersonAllowsTheCommand() throws Exception {
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "az-");
    Path az = dir.resolve("az");
    try (FakeAzure local = new FakeAzure(az)) {
      Files.writeString(az, "#!/bin/sh\necho ran >> '" + dir.resolve("runs") + "'\nprintf '{\"accessToken\":\""
          + local.token() + "\",\"expires_on\":" + Instant.now().plusSeconds(3000).getEpochSecond() + ",\"tenant\":\""
          + FakeAzure.TENANT + "\"}'\n");
      Files.setPosixFilePermissions(az, PosixFilePermissions.fromString("rwx------"));
      local.on("GET /subscriptions", r -> FakeAzure.json(200, "{\"value\":[{\"subscriptionId\":\"" + SUB + "\","
          + "\"displayName\":\"Stage\",\"state\":\"Enabled\",\"tenantId\":\"" + FakeAzure.TENANT + "\"}]}"));
      local.on("POST /providers/Microsoft.ResourceGraph/resources", r -> FakeAzure.json(200, "{\"data\":[]}"));
      try (AzureConnector cli = new AzureConnector(local.cloud, Clock.systemUTC())) {
        ConnectionConfig untrusted = Configs.of("cli", Env.DEV, "{\"subscriptions\":[\"" + SUB + "\"],"
            + "\"credentials\":{\"source\":\"cli\"}}");

        TestResult first = cli.test(untrusted, new TestSecrets(), TestCtx.of());

        assertThat(first.needsConfirmation()).isNotNull();
        assertThat(first.needsConfirmation().kind()).isEqualTo("azure-cli");
        assertThat(first.needsConfirmation().text()).contains(az.toString() + " account get-access-token --output "
            + "json --resource <resource>");
        String fingerprint = first.needsConfirmation().configPatch().path("trustedCli").asString();
        assertThat(fingerprint).hasSize(64);
        assertThat(dir.resolve("runs")).doesNotExist();
        assertThatThrownBy(() -> {
          ToolCall call = Configs.call("azure_describe", "cli", "{}");
          cli.classify(call, untrusted, Configs.policy(Env.DEV));
          cli.execute(call, untrusted, new TestSecrets(), TestCtx.of());
        }).hasMessageContaining("only after you allow the command");

        ObjectNode patched = (ObjectNode) untrusted.config().deepCopy();
        patched.put("trustedCli", fingerprint);
        ConnectionConfig trusted = new ConnectionConfig(untrusted.id(), "cli", "azure", Env.DEV, 2, patched,
            Set.of(), null);
        TestResult second = cli.test(trusted, new TestSecrets(), TestCtx.of());

        assertThat(second.ok()).as(String.valueOf(second.error())).isTrue();
        assertThat(second.metadataUpdate().path("tenant").asString()).isEqualTo(FakeAzure.TENANT);
        assertThat(second.hints()).extracting(TestResult.Hint::code).contains("azure.cli");
        assertThat(Files.readAllLines(dir.resolve("runs"))).isNotEmpty();

        // Without a tested tenant, calls refuse to read through a CLI sign-in of an unknown tenant.
        assertThatThrownBy(() -> {
          ToolCall call = Configs.call("azure_describe", "cli", "{}");
          cli.classify(call, trusted, Configs.policy(Env.DEV));
          cli.execute(call, trusted, new TestSecrets(), TestCtx.of());
        }).hasMessageContaining("Test the connection once");
        ConnectionConfig pinned = new ConnectionConfig(trusted.id(), "cli", "azure", Env.DEV, 2, patched, Set.of(),
            second.metadataUpdate());
        ToolCall call = Configs.call("azure_describe", "cli", "{}");
        cli.classify(call, pinned, Configs.policy(Env.DEV));
        assertThat(cli.execute(call, pinned, new TestSecrets(), TestCtx.of()).isError()).isFalse();
      }
    }
  }

  @Test
  void aDeviceSignInShowsTheCodeThenTests() {
    azure.on("POST /" + FakeAzure.TENANT + "/oauth2/v2.0/devicecode", r -> FakeAzure.json(200, "{\"device_code\":"
        + "\"dc-1\",\"user_code\":\"QWER-TYUI\",\"verification_uri\":\"https://microsoft.com/devicelogin\","
        + "\"expires_in\":900,\"interval\":5}"));
    azure.on("POST /token", r -> {
      String form = r.getBody().utf8();
      return FakeAzure.json(200, "{\"access_token\":\"" + azure.token() + "\",\"refresh_token\":\"rt-"
          + (form.contains("device_code") ? "1" : "2") + "\",\"expires_in\":3600}");
    });
    ConnectionConfig device = Configs.of("device", Env.DEV, "{\"tenantId\":\"" + FakeAzure.TENANT + "\","
        + "\"subscriptions\":[\"" + SUB + "\"],\"credentials\":{\"source\":\"deviceCode\"}}");

    TestResult first = connector.test(device, new TestSecrets(), TestCtx.of());

    assertThat(first.needsConfirmation().kind()).isEqualTo("azure-sign-in");
    assertThat(first.needsConfirmation().data().path("userCode").asString()).isEqualTo("QWER-TYUI");
    assertThat(first.needsConfirmation().configPatch().isEmpty()).isTrue();
    assertThat(first.needsConfirmation().text()).contains("https://microsoft.com/devicelogin", "QWER-TYUI");

    TestResult second = connector.test(device, new TestSecrets(), TestCtx.of());

    assertThat(second.ok()).as(String.valueOf(second.error())).isTrue();
    assertThat(second.hints()).extracting(TestResult.Hint::code).contains("azure.device-sign-in");
  }

  // ── azure_describe ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void resourceGraphReadsTheConnectionsSubscriptionsAndHidesWhatThePolicyHides() throws Exception {
    azure.on("POST /providers/Microsoft.ResourceGraph/resources", r -> {
      JsonNode body = TestCtx.JSON.readTree(r.getBody().utf8());
      boolean second = body.at("/options/$skipToken").asString("").equals("page-2");
      return FakeAzure.json(200, second ? "{\"totalRecords\":3,\"count\":1,\"data\":[{\"id\":\"" + SITE + "2\","
          + "\"type\":\"microsoft.web/sites\",\"location\":\"westeurope\",\"name\":\"api2\"}]}"
          : "{\"totalRecords\":3,\"count\":2,\"$skipToken\":\"page-2\",\"data\":["
          + "{\"id\":\"" + SITE + "\",\"type\":\"microsoft.web/sites\",\"location\":\"westeurope\",\"name\":\"api\","
          + "\"properties\":{\"siteConfig\":{\"appSettings\":[{\"name\":\"DB\",\"value\":\"Server=x;Password=p\"}]},"
          + "\"storage\":\"DefaultEndpointsProtocol=https;AccountName=a;AccountKey=cnry-key==\"}},"
          + "{\"id\":\"/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.KeyVault/vaults/kv\","
          + "\"type\":\"microsoft.keyvault/vaults\",\"location\":\"westeurope\",\"name\":\"kv\"}]}");
    });

    ToolResult first = OutputSchema.describe(run("azure_describe", "{\"query\":\"resources | where type =~ "
        + "'microsoft.web/sites' or type =~ 'microsoft.keyvault/vaults'\",\"limit\":2}"));

    JsonNode s = first.structuredContent();
    assertThat(s.path("rows")).hasSize(1);
    assertThat(s.path("hiddenByPolicy").asInt()).isEqualTo(1);
    assertThat(s.path("masked").asInt()).isEqualTo(2);
    assertThat(s.at("/rows/0/properties/storage").asString()).isEqualTo(Redactor.MASK);
    assertThat(s.at("/rows/0/properties/siteConfig/appSettings/0/value").asString()).isEqualTo(Redactor.MASK);
    assertThat(s.path("nextCursor").asString()).startsWith(Cursors.PREFIX);
    RecordedRequest graph = azure.to("POST /providers/Microsoft.ResourceGraph/resources").getFirst();
    JsonNode body = TestCtx.JSON.readTree(graph.getBody().utf8());
    assertThat(body.path("subscriptions").toString()).isEqualTo("[\"" + SUB + "\"]");
    assertThat(body.at("/options/$top").asInt()).isEqualTo(2);
    assertThat(body.at("/options/resultFormat").asString()).isEqualTo("objectArray");
    assertThat(graph.getHeaders().get("Authorization")).startsWith("Bearer ");

    ToolResult second = OutputSchema.describe(run("azure_describe", "{\"query\":\"resources | where type =~ "
        + "'microsoft.web/sites' or type =~ 'microsoft.keyvault/vaults'\",\"limit\":2,\"cursor\":\""
        + s.path("nextCursor").asString() + "\"}"));
    assertThat(second.structuredContent().path("rows").get(0).path("name").asString()).isEqualTo("api2");
    assertThatThrownBy(() -> run("azure_describe", "{\"query\":\"resources\",\"limit\":2,\"cursor\":\""
        + s.path("nextCursor").asString() + "\"}")).hasMessageContaining("another query");
  }

  @Test
  void aRestrictedPolicyRefusesRowsItCantCheck() {
    azure.on("POST /providers/Microsoft.ResourceGraph/resources", r -> FakeAzure.json(200,
        "{\"data\":[{\"type\":\"microsoft.web/sites\",\"count_\":3}]}"));
    PolicyView regions = Configs.policy(Env.STAGE, "{\"regions\":[\"westeurope\"]}");

    assertThatThrownBy(() -> run(cfg, regions, "azure_describe", "{\"query\":\"resources | summarize count() by "
        + "type\"}", TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("Project id, type and location");
        });
  }

  // ── azure_api_read, azure_api_change ────────────────────────────────────────────────────────────────────────

  @Test
  void aGetReturnsAzuresAnswerFilteredAndMasked() throws Exception {
    azure.on("GET /subscriptions/" + SUB + "/resourceGroups", r -> FakeAzure.json(200, "{\"value\":["
        + "{\"id\":\"/subscriptions/" + SUB + "/resourceGroups/stage-app\",\"location\":\"westeurope\"},"
        + "{\"id\":\"/subscriptions/" + SUB + "/resourceGroups/us-app\",\"location\":\"eastus\"}],"
        + "\"nextLink\":\"https://management.azure.com/subscriptions/" + SUB + "/resourceGroups?api-version=2021-04-01"
        + "&%24skiptoken=abc%3D\"}"));
    PolicyView regions = Configs.policy(Env.STAGE, "{\"regions\":[\"West Europe\"]}");

    ToolResult r = OutputSchema.apiRead(run(cfg, regions, CallRequest.READ, "{\"path\":\"/subscriptions/" + SUB + "/resourceGroups\","
        + "\"apiVersion\":\"2021-04-01\",\"query\":{\"$top\":10}}", TestCtx.of()));

    JsonNode s = r.structuredContent();
    assertThat(r.isError()).isFalse();
    assertThat(s.path("status").asInt()).isEqualTo(200);
    assertThat(s.at("/body/value")).hasSize(1);
    assertThat(s.path("hiddenByPolicy").asInt()).isEqualTo(1);
    assertThat(s.at("/next/query/$skiptoken").asString()).isEqualTo("abc=");
    RecordedRequest sent = azure.to("GET /subscriptions/" + SUB + "/resourceGroups").getFirst();
    assertThat(FakeAzure.param(sent, "api-version")).isEqualTo("2021-04-01");
    assertThat(FakeAzure.param(sent, "$top")).isEqualTo("10");
  }

  @Test
  void azuresRefusalIsData() throws Exception {
    azure.on("GET " + SITE, r -> FakeAzure.json(403, "{\"error\":{\"code\":\"AuthorizationFailed\",\"message\":"
        + "\"The client does not have authorization to perform action 'Microsoft.Web/sites/read'.\"}}"));

    ToolResult r = OutputSchema.apiRead(run(CallRequest.READ, "{\"path\":\"" + SITE + "\",\"apiVersion\":"
        + "\"2023-12-01\"}"));

    assertThat(r.isError()).isFalse();
    assertThat(r.structuredContent().path("status").asInt()).isEqualTo(403);
    assertThat(r.content().getFirst().text()).contains("403 AuthorizationFailed");
  }

  @Test
  void aChangeRunsOnlyAsApproved() throws Exception {
    azure.on("POST " + SITE + "/restart", r -> new MockResponse.Builder().code(202)
        .addHeader("Azure-AsyncOperation", "https://management.azure.com/subscriptions/" + SUB
            + "/providers/Microsoft.Web/locations/westeurope/operations/op-1?api-version=2023-12-01").build());
    String args = "{\"method\":\"POST\",\"path\":\"" + SITE + "/restart\",\"apiVersion\":\"2023-12-01\"}";
    ToolCall call = Configs.call(CallRequest.CHANGE, cfg.name(), args);
    Classification k = connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThat(k.kind()).isEqualTo(Classification.Kind.MUTATION);

    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .hasMessageContaining("only after the person approves");
    assertThat(azure.to("POST " + SITE)).isEmpty();

    JsonNode payload = connector.describeAction(call, cfg).payload();
    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    ToolResult r = connector.execute(call, cfg, Configs.secrets(), TestCtx.of().approved(payload));

    assertThat(r.structuredContent().path("status").asInt()).isEqualTo(202);
    assertThat(r.structuredContent().at("/operation/path").asString()).isEqualTo("/subscriptions/" + SUB
        + "/providers/Microsoft.Web/locations/westeurope/operations/op-1");
    assertThat(azure.to("POST " + SITE + "/restart")).hasSize(1);
  }

  @Test
  void aTokenOfAnotherTenantIsRefusedAtCallTime() {
    ConnectionConfig c = tested(Configs.stage());
    ToolCall call = Configs.call(CallRequest.READ, c.name(), "{\"path\":\"" + SITE + "\",\"apiVersion\":"
        + "\"2023-12-01\"}");
    connector.classify(call, c, Configs.policy(Env.STAGE));
    azure.tokenTenant = "11111111-2222-4333-8444-555555555555";

    assertThatThrownBy(() -> connector.execute(call, c, Configs.secrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("now belongs to tenant 11111111-2222-4333-8444-555555555555");
        });
    assertThat(azure.to("GET " + SITE)).isEmpty();
  }

  @Test
  void aCallWithoutItsClassificationDoesntRun() {
    ToolCall call = Configs.call("azure_describe", cfg.name(), "{}");
    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .hasMessageContaining("no policy decision");
  }

  // ── azure_monitor ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void metricsComeBackPerSeries() throws Exception {
    String vm = "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/vm1";
    azure.on("GET " + vm + "/providers/Microsoft.Insights/metrics", r -> FakeAzure.json(200, "{\"value\":[{\"name\":"
        + "{\"value\":\"Percentage CPU\"},\"unit\":\"Percent\",\"timeseries\":[{\"metadatavalues\":[],\"data\":["
        + "{\"timeStamp\":\"2026-11-11T10:00:00Z\",\"average\":12.5},{\"timeStamp\":\"2026-11-11T10:05:00Z\","
        + "\"average\":20.0}]}]}]}"));

    ToolResult r = OutputSchema.monitor(run("azure_monitor", "{\"resource\":\"" + vm + "\",\"metrics\":["
        + "\"Percentage CPU\"],\"from\":\"-1h\"}"));

    JsonNode s = r.structuredContent();
    assertThat(s.at("/metrics/0/name").asString()).isEqualTo("Percentage CPU");
    assertThat(s.at("/metrics/0/timeseries/0/data/1/average").asDouble()).isEqualTo(20.0);
    assertThat(s.path("points").asInt()).isEqualTo(2);
    RecordedRequest sent = azure.to("GET " + vm).getFirst();
    assertThat(FakeAzure.param(sent, "metricnames")).isEqualTo("Percentage CPU");
    assertThat(FakeAzure.param(sent, "interval")).isEqualTo("PT1M");
    assertThat(FakeAzure.param(sent, "aggregation")).isEqualTo("Average");
    assertThat(FakeAzure.param(sent, "timespan")).contains("/");
  }

  @Test
  void alertsAndTheActivityLogHideWhatThePolicyHides() throws Exception {
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.AlertsManagement/alerts", r -> FakeAzure.json(200,
        "{\"value\":[{\"id\":\"a1\",\"name\":\"High CPU\",\"properties\":{\"essentials\":{\"severity\":\"Sev2\","
        + "\"alertState\":\"New\",\"monitorCondition\":\"Fired\",\"targetResource\":\"" + SITE + "\"}}},"
        + "{\"id\":\"a2\",\"name\":\"Prod down\",\"properties\":{\"essentials\":{\"severity\":\"Sev0\","
        + "\"alertState\":\"New\",\"monitorCondition\":\"Fired\",\"targetResource\":\"/subscriptions/" + SUB
        + "/resourceGroups/prod-app/providers/Microsoft.Web/sites/api\"}}}]}"));
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.Insights/eventtypes/management/values",
        r -> FakeAzure.json(200, "{\"value\":[{\"eventTimestamp\":\"2026-11-11T09:00:00Z\",\"operationName\":"
            + "{\"value\":\"Microsoft.Web/sites/write\"},\"status\":{\"value\":\"Succeeded\"},\"caller\":"
            + "\"dev@contoso.example\",\"resourceId\":\"" + SITE + "\",\"claims\":{\"ipaddr\":\"203.0.113.9\"}}]}"));
    PolicyView stageOnly = Configs.policy(Env.STAGE, "{\"resources\":[\"/subscriptions/*/resourceGroups/stage-*\"]}");

    ToolResult alerts = OutputSchema.monitor(run(cfg, stageOnly, "azure_monitor", "{\"action\":\"alerts\"}",
        TestCtx.of()));
    ToolResult events = OutputSchema.monitor(run(cfg, stageOnly, "azure_monitor", "{\"action\":\"activity_log\","
        + "\"from\":\"-2h\"}", TestCtx.of()));

    assertThat(alerts.structuredContent().path("items")).hasSize(1);
    assertThat(alerts.structuredContent().path("hiddenByPolicy").asInt()).isEqualTo(1);
    assertThat(alerts.structuredContent().at("/items/0/name").asString()).isEqualTo("High CPU");
    RecordedRequest a = azure.to("GET /subscriptions/" + SUB + "/providers/Microsoft.AlertsManagement").getFirst();
    assertThat(FakeAzure.param(a, "api-version")).isEqualTo("2019-03-01");
    assertThat(FakeAzure.param(a, "monitorCondition")).isEqualTo("Fired");
    assertThat(FakeAzure.param(a, "timeRange")).isEqualTo("1d");
    assertThat(events.structuredContent().at("/items/0/operation").asString()).isEqualTo("Microsoft.Web/sites/write");
    assertThat(events.structuredContent().toString()).doesNotContain("203.0.113.9");
    RecordedRequest e = azure.to("GET /subscriptions/" + SUB + "/providers/Microsoft.Insights/eventtypes").getFirst();
    assertThat(FakeAzure.param(e, "$filter")).startsWith("eventTimestamp ge '").contains("and eventTimestamp le '");
    assertThat(FakeAzure.param(e, "$filter")).doesNotContain(".");
  }

  @Test
  void theActivityLogPagesOverTheRangeOfItsFirstPage() throws Exception {
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.Insights/eventtypes/management/values",
        r -> FakeAzure.json(200, "{\"value\":[" + event("e1", "Microsoft.Web/sites/write") + ","
            + event("e2", "Microsoft.Web/sites/restart/action") + "," + event("e3",
            "Microsoft.Web/sites/config/write") + "]}"));
    String args = "{\"action\":\"activity_log\",\"from\":\"-2h\",\"limit\":2}";

    ToolResult first = OutputSchema.monitor(run("azure_monitor", args));
    String cursor = first.structuredContent().path("nextCursor").asString();
    ToolResult second = OutputSchema.monitor(run("azure_monitor", args.replace("}", ",\"cursor\":\"" + cursor
        + "\"}")));

    assertThat(first.structuredContent().path("items")).hasSize(2);
    assertThat(first.structuredContent().toString()).doesNotContain("adminPassword").contains("statusCode");
    assertThat(cursor).startsWith(Cursors.PREFIX);
    assertThat(second.structuredContent().path("items")).hasSize(1);
    assertThat(second.structuredContent().at("/items/0/correlationId").asString()).isEqualTo("e3");
    var sent = azure.to("GET /subscriptions/" + SUB + "/providers/Microsoft.Insights/eventtypes");
    assertThat(FakeAzure.param(sent.get(1), "$filter")).isEqualTo(FakeAzure.param(sent.get(0), "$filter"));
    assertThat(second.structuredContent().path("from").asString())
        .isEqualTo(first.structuredContent().path("from").asString());
  }

  private static String event(String id, String operation) {
    return "{\"eventTimestamp\":\"2026-11-11T09:00:00Z\",\"correlationId\":\"" + id + "\",\"operationName\":"
        + "{\"value\":\"" + operation + "\"},\"status\":{\"value\":\"Succeeded\"},\"resourceId\":\"" + SITE + "\","
        + "\"properties\":{\"statusCode\":\"OK\",\"requestbody\":\"{\\\"adminPassword\\\":\\\"cnry\\\"}\"}}";
  }

  @Test
  void resourceHealthOfOneResource() throws Exception {
    azure.on("GET " + SITE + "/providers/Microsoft.ResourceHealth/availabilityStatuses/current",
        r -> FakeAzure.json(200, "{\"properties\":{\"availabilityState\":\"Degraded\",\"summary\":\"Slow responses\","
            + "\"reasonType\":\"Unplanned\",\"occuredTime\":\"2026-11-11T08:00:00Z\"}}"));

    ToolResult r = OutputSchema.monitor(run("azure_monitor", "{\"action\":\"health\",\"resource\":\"" + SITE
        + "\"}"));

    assertThat(r.structuredContent().at("/items/0/availabilityState").asString()).isEqualTo("Degraded");
    assertThat(r.structuredContent().at("/items/0/since").asString()).isEqualTo("2026-11-11T08:00:00Z");
  }

  // ── Logs through the connection ─────────────────────────────────────────────────────────────────────────────

  @Test
  void logSearchReadsTheConnectionsWorkspaceWithKql() throws Exception {
    azure.on("POST /v1/workspaces/" + WORKSPACE + "/query", r -> FakeAzure.json(200, "{\"tables\":[{\"name\":"
        + "\"PrimaryResult\",\"columns\":[{\"name\":\"TimeGenerated\",\"type\":\"datetime\"},{\"name\":"
        + "\"Message\",\"type\":\"string\"},{\"name\":\"SeverityLevel\",\"type\":\"int\"},{\"name\":\"_ItemId\","
        + "\"type\":\"string\"}],\"rows\":[[\"" + Instant.now().minusSeconds(90) + "\",\"Timeout calling payments\",3,"
        + "\"item-1\"]]}]}"));
    ConnectionConfig c = tested(cfg);

    ToolResult r = run(c, Configs.policy(Env.STAGE), "logs_search", "{\"query\":\"AppTraces | where Message has "
        + "'Timeout'\",\"from\":\"-1h\"}", TestCtx.of());

    assertThat(r.isError()).isFalse();
    assertThat(r.structuredContent().at("/entries/0/message").asString()).isEqualTo("Timeout calling payments");
    assertThat(r.structuredContent().at("/entries/0/severity").asString()).isEqualTo("ERROR");
    RecordedRequest sent = azure.to("POST /v1/workspaces/" + WORKSPACE + "/query").getFirst();
    JsonNode body = TestCtx.JSON.readTree(sent.getBody().utf8());
    assertThat(body.path("query").asString()).startsWith("AppTraces | where Message has 'Timeout'\n| where "
        + "TimeGenerated between (datetime(").contains("| top 101 by TimeGenerated desc");
    assertThat(body.path("timespan").asString()).contains("/");
    assertThat(body.has("workspaces")).isFalse();

    Classification refused = connector.classify(Configs.call("logs_search", c.name(), "{\"query\":\"union "
        + "workspace('other').AppTraces\"}"), c, Configs.policy(Env.STAGE));
    assertThat(refused.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(refused.ruleId()).isEqualTo("stage.cloud.kql_cross_resource");
    Classification restricted = connector.classify(Configs.call("logs_search", c.name(), "{\"query\":\"AppTraces\"}"),
        c, Configs.policy(Env.STAGE, "{\"allowedServices\":[\"web\"]}"));
    assertThat(restricted.ruleId()).isEqualTo("stage.cloud.log_scope");
  }
}
