package com.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.time.Clock;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * The service rows of the Azure plan that {@code azure_api_read}, {@code azure_api_change} and {@code azure_monitor}
 * cover without tools of their own: App Service and Functions, Container Apps, Static Web Apps, Cost Management,
 * budgets and Advisor, Event Hubs, Event Grid, AKS discovery and Service Bus counts. Each row's requests read, wait
 * for approval or are refused as {@code docs/azure.md} says.
 */
class AzureServiceRecipesTest {

  private static final String SUB = FakeAzure.SUB;
  private static final String RG = "/subscriptions/" + SUB + "/resourceGroups/stage-app/providers/";
  private static final String SITE = RG + "Microsoft.Web/sites/api";
  private static final String APP = RG + "Microsoft.App/containerApps/web";
  private FakeAzure azure;
  private AzureConnector connector;
  private final ConnectionConfig cfg = tested(Configs.stage());

  @BeforeEach
  void start() throws Exception {
    azure = new FakeAzure();
    connector = new AzureConnector(azure.cloud, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    connector.close();
    azure.close();
  }

  private static ConnectionConfig tested(ConnectionConfig c) {
    return new ConnectionConfig(c.id(), c.name(), c.type(), c.env(), c.version(), c.config(), c.secretFields(),
        TestCtx.JSON.createObjectNode().put("tenant", FakeAzure.TENANT));
  }

  private Classification classify(String tool, String method, String path) {
    return connector.classify(call(tool, method, path, ""), cfg, Configs.policy(Env.STAGE));
  }

  private static ToolCall call(String tool, String method, String path, String extra) {
    return Configs.call(tool, "azure-stage", "{\"method\":\"" + method + "\",\"path\":\"" + path + "\","
        + "\"apiVersion\":\"2024-04-01\"" + extra + "}");
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
      // App Service and Functions: state, slots, deployments and their logs, configuration without values
      "GET,Microsoft.Web/sites/api",
      "GET,Microsoft.Web/sites/api/slots",
      "GET,Microsoft.Web/sites/api/slots/staging",
      "GET,Microsoft.Web/sites/api/deployments",
      "GET,Microsoft.Web/sites/api/deployments/7c1e/log",
      "GET,Microsoft.Web/sites/api/config/web",
      "GET,Microsoft.Web/sites/api/config/logs",
      "GET,Microsoft.Web/sites/api/config/slotConfigNames",
      "GET,Microsoft.Web/sites/api/config/authsettingsV2",
      "GET,Microsoft.Web/sites/api/functions",
      "GET,Microsoft.Web/serverfarms/plan",
      "GET,Microsoft.Web/staticSites/docs/builds",
      // Container Apps: revisions, replicas, jobs and their executions
      "GET,Microsoft.App/containerApps/web",
      "GET,Microsoft.App/containerApps/web/revisions",
      "GET,Microsoft.App/containerApps/web/revisions/web--r7/replicas",
      "GET,Microsoft.App/jobs/nightly/executions",
      "GET,Microsoft.App/managedEnvironments/env",
      // Event Hubs partitions and consumer groups, Service Bus counts, Event Grid subscriptions
      "GET,Microsoft.EventHub/namespaces/ns/eventhubs/orders",
      "GET,Microsoft.EventHub/namespaces/ns/eventhubs/orders/consumergroups",
      "GET,Microsoft.ServiceBus/namespaces/bus/queues/orders",
      "GET,Microsoft.ServiceBus/namespaces/bus/topics/events/subscriptions/billing",
      "GET,Microsoft.EventGrid/topics/t/providers/Microsoft.EventGrid/eventSubscriptions/s",
      // AKS discovery
      "GET,Microsoft.ContainerService/managedClusters/aks",
      "GET,Microsoft.ContainerService/managedClusters/aks/agentPools",
      "GET,Microsoft.ContainerService/managedClusters/aks/upgradeProfiles/default"})
  void readsOfTheServiceRowsRead(String method, String suffix) {
    Classification c = classify(CallRequest.READ, method, RG + suffix);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "/subscriptions/" + FakeAzure.SUB + "/providers/Microsoft.Consumption/budgets",
      "/subscriptions/" + FakeAzure.SUB + "/providers/Microsoft.Advisor/recommendations",
      "/subscriptions/" + FakeAzure.SUB + "/providers/Microsoft.ContainerService/managedClusters",
      "/subscriptions/" + FakeAzure.SUB + "/providers/Microsoft.Web/sites"})
  void subscriptionWideReadsRead(String path) {
    assertThat(classify(CallRequest.READ, "GET", path).kind()).isEqualTo(Classification.Kind.READ);
  }

  @Test
  void costQueriesAreTheReadOnlyPosts() {
    for (String path : new String[] {"/subscriptions/" + SUB + "/providers/Microsoft.CostManagement/query",
        "/subscriptions/" + SUB + "/providers/Microsoft.CostManagement/forecast",
        "/subscriptions/" + SUB + "/resourceGroups/stage-app/providers/Microsoft.CostManagement/query"}) {
      Classification c = connector.classify(call(CallRequest.READ, "POST", path, ",\"body\":{\"type\":\"ActualCost\","
          + "\"timeframe\":\"MonthToDate\"}"), cfg, Configs.policy(Env.STAGE));
      assertThat(c.kind()).as(path).isEqualTo(Classification.Kind.READ);
    }
    Classification report = classify(CallRequest.CHANGE, "POST", "/subscriptions/" + SUB
        + "/providers/Microsoft.CostManagement/generateCostDetailsReport");
    assertThat(report.kind()).isEqualTo(Classification.Kind.MUTATION);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "Microsoft.Web/sites/api/restart",
      "Microsoft.Web/sites/api/stop",
      "Microsoft.Web/sites/api/start",
      "Microsoft.Web/sites/api/slotsswap",
      "Microsoft.Web/sites/api/slots/staging/slotsswap",
      "Microsoft.Web/sites/api/slots/staging/restart",
      "Microsoft.Web/sites/api/syncfunctiontriggers",
      "Microsoft.App/containerApps/web/revisions/web--r7/restart",
      "Microsoft.App/containerApps/web/revisions/web--r7/activate",
      "Microsoft.App/containerApps/web/revisions/web--r7/deactivate",
      "Microsoft.App/jobs/nightly/start",
      "Microsoft.App/jobs/nightly/executions/nightly-1/stop",
      "Microsoft.ContainerService/managedClusters/aks/runCommand"})
  void theCommonActionsWaitForApproval(String suffix) {
    Classification c = classify(CallRequest.CHANGE, "POST", RG + suffix);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.MUTATION);
    Classification asRead = classify(CallRequest.READ, "POST", RG + suffix);
    assertThat(asRead.ruleId()).isEqualTo("stage.cloud.not_a_read");
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
      // App settings, connection strings and keys of App Service and Functions stay with people
      "POST,Microsoft.Web/sites/api/config/appsettings/list",
      "POST,Microsoft.Web/sites/api/slots/staging/config/appsettings/list",
      "POST,Microsoft.Web/sites/api/config/connectionstrings/list",
      "POST,Microsoft.Web/sites/api/config/azurestorageaccounts/list",
      "GET,Microsoft.Web/sites/api/config/appsettings",
      "GET,Microsoft.Web/sites/api/slots/staging/config/connectionstrings",
      "GET,Microsoft.Web/sites/api/config/azurestorageaccounts",
      "GET,Microsoft.Web/sites/api/config/publishingcredentials",
      "POST,Microsoft.Web/sites/api/host/default/listkeys",
      "POST,Microsoft.Web/sites/api/functions/http1/listkeys",
      "POST,Microsoft.Web/staticSites/docs/listAppSettings",
      "POST,Microsoft.Web/staticSites/docs/listFunctionAppSettings",
      "POST,Microsoft.Web/staticSites/docs/listSecrets",
      // Container Apps secrets and tokens
      "POST,Microsoft.App/containerApps/web/listSecrets",
      "POST,Microsoft.App/containerApps/web/getAuthtoken",
      "POST,Microsoft.App/jobs/nightly/listSecrets",
      "POST,Microsoft.App/managedEnvironments/env/daprComponents/state/listSecrets",
      // Event Hubs, Service Bus and Event Grid keys, full endpoint URLs and secret delivery attributes
      "POST,Microsoft.EventHub/namespaces/ns/authorizationRules/RootManageSharedAccessKey/listKeys",
      "POST,Microsoft.EventHub/namespaces/ns/eventhubs/orders/authorizationRules/send/regenerateKeys",
      "POST,Microsoft.ServiceBus/namespaces/bus/queues/orders/authorizationRules/listen/listKeys",
      "POST,Microsoft.EventGrid/topics/t/listKeys",
      "POST,Microsoft.EventGrid/topics/t/providers/Microsoft.EventGrid/eventSubscriptions/s/getFullUrl",
      "POST,Microsoft.EventGrid/topics/t/providers/Microsoft.EventGrid/eventSubscriptions/s/getDeliveryAttributes",
      // AKS kubeconfigs: workloads go through the Kubernetes connector with kubelogin
      "POST,Microsoft.ContainerService/managedClusters/aks/listClusterUserCredential",
      "POST,Microsoft.ContainerService/managedClusters/aks/listClusterAdminCredential",
      "POST,Microsoft.ContainerService/managedClusters/aks/listClusterMonitoringUserCredential"})
  void secretsOfTheServiceRowsAreRefused(String method, String suffix) {
    for (String tool : new String[] {CallRequest.READ, CallRequest.CHANGE}) {
      if (method.equals("GET") && tool.equals(CallRequest.CHANGE)) {
        continue;
      }
      Classification c = classify(tool, method, RG + suffix);
      assertThat(c.kind()).as(tool + " " + suffix).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).isEqualTo("stage.cloud.azure_secret_action");
    }
  }

  @Test
  void writingAppSettingsIsAnApprovedChangeThatWarnsAboutPut() {
    ToolCall put = call(CallRequest.CHANGE, "PUT", SITE + "/config/appsettings", ",\"body\":{\"properties\":"
        + "{\"FEATURE_X\":\"on\"}}");
    assertThat(connector.classify(put, cfg, Configs.policy(Env.STAGE)).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(connector.describeAction(put, cfg).warnings())
        .anySatisfy(w -> assertThat(w).contains("replaces the whole resource"));
  }

  @Test
  void theApprovalCardSaysWhatTheActionDoes() {
    ActionPreview restart = connector.describeAction(call(CallRequest.CHANGE, "POST", SITE + "/slots/staging/restart",
        ""), cfg);
    assertThat(restart.warnings()).anySatisfy(w -> assertThat(w).startsWith("Restarts api (slot staging) on every "
        + "instance"));
    assertThat(restart.fields()).anySatisfy(f -> assertThat(f.value()).isEqualTo("restart"));

    ActionPreview swap = connector.describeAction(call(CallRequest.CHANGE, "POST", SITE + "/slotsswap",
        ",\"body\":{\"targetSlot\":\"staging\",\"preserveVnet\":true}"), cfg);
    assertThat(swap.warnings()).anySatisfy(w -> assertThat(w).contains("Swaps the slots of api"));
    assertThat(swap.text()).contains("\"targetSlot\" : \"staging\"").contains("/slotsswap?api-version=2024-04-01");

    ActionPreview activate = connector.describeAction(call(CallRequest.CHANGE, "POST", APP
        + "/revisions/web--r7/activate", ""), cfg);
    assertThat(activate.warnings()).anySatisfy(w -> assertThat(w).startsWith("Activates revision web--r7"));
    ActionPreview deactivate = connector.describeAction(call(CallRequest.CHANGE, "POST", APP
        + "/revisions/web--r7/deactivate", ""), cfg);
    assertThat(deactivate.warnings()).anySatisfy(w -> assertThat(w).contains("serves no traffic"));
  }

  @Test
  void anApprovedSlotSwapSendsExactlyTheApprovedBody() throws Exception {
    azure.on("POST " + SITE + "/slotsswap", r -> FakeAzure.json(202, ""));
    ToolCall swap = call(CallRequest.CHANGE, "POST", SITE + "/slotsswap", ",\"body\":{\"targetSlot\":\"staging\","
        + "\"preserveVnet\":true}");
    assertThat(connector.classify(swap, cfg, Configs.policy(Env.STAGE)).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    JsonNode payload = connector.describeAction(swap, cfg).payload();

    ToolResult r = connector.execute(swap, cfg, Configs.secrets(), TestCtx.of().approved(payload));

    assertThat(r.structuredContent().path("status").asInt()).isEqualTo(202);
    RecordedRequest sent = azure.to("POST " + SITE + "/slotsswap").getFirst();
    assertThat(TestCtx.JSON.readTree(sent.getBody().utf8())).isEqualTo(TestCtx.JSON.readTree(
        "{\"targetSlot\":\"staging\",\"preserveVnet\":true}"));
    assertThat(FakeAzure.param(sent, "api-version")).isEqualTo("2024-04-01");
  }

  @Test
  void containerAppReplicasAndEnvironmentValuesComeBackMasked() throws Exception {
    azure.on("GET " + APP, r -> FakeAzure.json(200, "{\"id\":\"" + APP + "\",\"type\":\"Microsoft.App/containerApps"
        + "\",\"location\":\"westeurope\",\"properties\":{\"latestRevisionName\":\"web--r7\",\"configuration\":"
        + "{\"secrets\":[{\"name\":\"db-password\"}],\"activeRevisionsMode\":\"Multiple\"},\"template\":"
        + "{\"containers\":[{\"name\":\"web\",\"env\":[{\"name\":\"LOG_LEVEL\",\"value\":\"debug\"},{\"name\":"
        + "\"DB_PASSWORD\",\"secretRef\":\"db-password\"}]}]}}}"));

    ToolCall get = read(APP);
    assertThat(connector.classify(get, cfg, Configs.policy(Env.STAGE)).kind()).isEqualTo(Classification.Kind.READ);
    ToolResult r = connector.execute(get, cfg, Configs.secrets(), TestCtx.of());

    JsonNode body = r.structuredContent().path("body");
    assertThat(body.at("/properties/template/containers/0/env/0/value").asString()).isEqualTo(Redactor.MASK);
    assertThat(body.at("/properties/template/containers/0/env/1/secretRef").asString()).isEqualTo("db-password");
    assertThat(body.at("/properties/configuration/secrets/0/name").asString()).isEqualTo("db-password");
    assertThat(body.at("/properties/latestRevisionName").asString()).isEqualTo("web--r7");
  }

  @Test
  void advisorCostRecommendationsReadWithTheirFilter() throws Exception {
    String path = "/subscriptions/" + SUB + "/providers/Microsoft.Advisor/recommendations";
    azure.on("GET " + path, r -> FakeAzure.json(200, "{\"value\":[{\"id\":\"/subscriptions/" + SUB
        + "/resourceGroups/stage-app/providers/Microsoft.Compute/virtualMachines/vm1/providers/Microsoft.Advisor/"
        + "recommendations/r1\",\"type\":\"Microsoft.Advisor/recommendations\",\"properties\":{\"category\":"
        + "\"Cost\",\"impact\":\"High\",\"shortDescription\":{\"problem\":\"Right-size or shut down underutilized "
        + "virtual machines\"}}}]}"));
    ToolCall call = Configs.call(CallRequest.READ, "azure-stage", "{\"path\":\"" + path + "\",\"apiVersion\":"
        + "\"2023-01-01\",\"query\":{\"$filter\":\"Category eq 'Cost'\"}}");
    assertThat(connector.classify(call, cfg, Configs.policy(Env.STAGE)).kind()).isEqualTo(Classification.Kind.READ);

    ToolResult r = connector.execute(call, cfg, Configs.secrets(), TestCtx.of());

    assertThat(r.structuredContent().at("/body/value/0/properties/impact").asString()).isEqualTo("High");
    assertThat(FakeAzure.param(azure.to("GET " + path).getFirst(), "$filter")).isEqualTo("Category eq 'Cost'");
  }

  @Test
  void eventGridDeliveryFailuresAreMetricsOfTheTopic() throws Exception {
    String topic = RG + "Microsoft.EventGrid/topics/orders";
    azure.on("GET " + topic + "/providers/Microsoft.Insights/metrics", r -> FakeAzure.json(200, "{\"value\":[{"
        + "\"name\":{\"value\":\"DeliveryAttemptFailCount\"},\"unit\":\"Count\",\"timeseries\":[{\"metadatavalues\":"
        + "[{\"name\":{\"value\":\"EventSubscriptionName\"},\"value\":\"BILLING\"}],\"data\":[{\"timeStamp\":"
        + "\"2026-11-11T10:00:00Z\",\"total\":3}]}]},{\"name\":{\"value\":\"DeadLetteredCount\"},\"unit\":\"Count\","
        + "\"timeseries\":[]}]}"));
    ToolCall call = Configs.call("azure_monitor", "azure-stage", "{\"resource\":\"" + topic + "\",\"metrics\":["
        + "\"DeliveryAttemptFailCount\",\"DeadLetteredCount\"],\"aggregation\":[\"Total\"],\"filter\":"
        + "\"EventSubscriptionName eq '*'\",\"from\":\"-1h\"}");
    assertThat(connector.classify(call, cfg, Configs.policy(Env.STAGE)).kind()).isEqualTo(Classification.Kind.READ);

    ToolResult r = connector.execute(call, cfg, Configs.secrets(), TestCtx.of());

    assertThat(r.structuredContent().at("/metrics/0/name").asString()).isEqualTo("DeliveryAttemptFailCount");
    RecordedRequest sent = azure.to("GET " + topic).getFirst();
    assertThat(FakeAzure.param(sent, "metricnames")).isEqualTo("DeliveryAttemptFailCount,DeadLetteredCount");
    assertThat(FakeAzure.param(sent, "$filter")).isEqualTo("EventSubscriptionName eq '*'");
  }

  private static ToolCall read(String path) {
    return Configs.call(CallRequest.READ, "azure-stage", "{\"path\":\"" + path + "\",\"apiVersion\":\"2024-03-01\"}");
  }
}
