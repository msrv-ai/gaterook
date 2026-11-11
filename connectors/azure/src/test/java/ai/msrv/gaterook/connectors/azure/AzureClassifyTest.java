package ai.msrv.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Classification without I/O: GET reads, the read-only POSTs, approval for every other change, refused secret
 * actions, the connection's subscriptions, and the Cloud policy's services and resource masks.
 */
class AzureClassifyTest {

  private static final String SUB = FakeAzure.SUB;
  private static final String SITE = "/subscriptions/" + SUB + "/resourceGroups/stage-app/providers/Microsoft.Web/"
      + "sites/api";
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final AzureConnector connector = new AzureConnector(cloud, Clock.systemUTC());
  private final ConnectionConfig cfg = Configs.stage();

  @AfterEach
  void close() {
    connector.close();
    cloud.close();
  }

  private Classification call(String tool, String method, String path, String extra) {
    return connector.classify(Configs.call(tool, cfg.name(), "{\"method\":\"" + method + "\",\"path\":\""
        + path + "\",\"apiVersion\":\"2023-12-01\"" + extra + "}"), cfg, Configs.policy(Env.STAGE));
  }

  /** {@code azure_api_read} for GET and POST, {@code azure_api_change} for the other methods. */
  private Classification call(String method, String path, String extra) {
    boolean read = method.equals("GET") || method.equals("POST");
    return call(read ? CallRequest.READ : CallRequest.CHANGE, method, path, extra);
  }

  private Classification call(String method, String path) {
    return call(method, path, "");
  }

  private Classification change(String method, String path, String extra) {
    return call(CallRequest.CHANGE, method, path, extra);
  }

  @Test
  void getIsARead() {
    assertThat(call("GET", SITE).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(call("GET", "/subscriptions/" + SUB + "/resourceGroups").kind()).isEqualTo(Classification.Kind.READ);
    assertThat(call("GET", SITE + "/config/web").kind()).isEqualTo(Classification.Kind.READ);
    assertThat(call("GET", "/subscriptions/" + SUB + "/providers/Microsoft.Web").kind())
        .isEqualTo(Classification.Kind.READ);
  }

  @ParameterizedTest
  @CsvSource({
      "/subscriptions/" + FakeAzure.SUB + "/providers/Microsoft.Storage/checkNameAvailability",
      "/subscriptions/" + FakeAzure.SUB + "/providers/Microsoft.CostManagement/query",
      "/subscriptions/" + FakeAzure.SUB + "/resourceGroups/rg/providers/Microsoft.CostManagement/forecast",
      "/subscriptions/" + FakeAzure.SUB + "/providers/Microsoft.Insights/metrics"})
  void theReadOnlyPostsAreReads(String path) {
    assertThat(call("POST", path, ",\"body\":{}").kind()).isEqualTo(Classification.Kind.READ);
  }

  @ParameterizedTest
  @CsvSource({
      "POST,/restart", "POST,/stop", "PUT,''", "PATCH,''", "DELETE,''", "POST,/slots/staging/slotsswap",
      "POST,/providers/Microsoft.Insights/metrics"})
  void everyOtherChangeWaitsForApproval(String method, String suffix) {
    Classification c = change(method, SITE + suffix, method.equals("DELETE") ? "" : ",\"body\":{}");
    assertThat(c.kind()).as(method + " " + suffix).isEqualTo(Classification.Kind.MUTATION);
    assertThat(c.reason()).startsWith("Azure " + method);
  }

  @ParameterizedTest
  @CsvSource({
      "/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/acct/listKeys",
      "/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/acct/regenerateKey",
      "/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/acct/listAccountSas",
      "/resourceGroups/rg/providers/Microsoft.ContainerService/managedClusters/aks/listClusterUserCredential",
      "/resourceGroups/rg/providers/Microsoft.ContainerService/managedClusters/aks/listClusterAdminCredential",
      "/resourceGroups/rg/providers/Microsoft.ContainerRegistry/registries/acr/listCredentials",
      "/resourceGroups/rg/providers/Microsoft.DocumentDB/databaseAccounts/db/listConnectionStrings",
      "/resourceGroups/rg/providers/Microsoft.Web/sites/api/config/appsettings/list",
      "/resourceGroups/rg/providers/Microsoft.Web/sites/api/config/publishingcredentials/list",
      "/resourceGroups/rg/providers/Microsoft.Web/sites/api/slots/b/config/connectionstrings/list",
      "/resourceGroups/rg/providers/Microsoft.Web/sites/api/host/default/listkeys",
      "/resourceGroups/rg/providers/Microsoft.Web/sites/api/functions/f/listsecrets",
      "/resourceGroups/rg/providers/Microsoft.Web/sites/api/publishxml",
      "/resourceGroups/rg/providers/Microsoft.Logic/workflows/w/triggers/t/listCallbackUrl",
      "/resourceGroups/rg/providers/Microsoft.Search/searchServices/s/listQueryKeys",
      "/resourceGroups/rg/providers/Microsoft.ServiceBus/namespaces/n/authorizationRules/r/listKeys"})
  void secretActionsAreRefusedWhateverTheMethod(String suffix) {
    for (String tool : new String[] {CallRequest.READ, CallRequest.CHANGE}) {
      for (String method : tool.equals(CallRequest.READ) ? new String[] {"GET", "POST"} : new String[] {"POST"}) {
        Classification c = call(tool, method, "/subscriptions/" + SUB + suffix, "");
        assertThat(c.kind()).as(tool + " " + method + " " + suffix).isEqualTo(Classification.Kind.DENY);
        assertThat(c.ruleId()).isEqualTo("stage.cloud.azure_secret_action");
      }
    }
  }

  @Test
  void eachApiToolRefusesTheOthersRequests() {
    Classification restart = call(CallRequest.READ, "POST", SITE + "/restart", "");
    assertThat(restart.ruleId()).isEqualTo("stage.cloud.not_a_read");
    assertThat(restart.hint()).contains("azure_api_change");
    Classification get = call(CallRequest.CHANGE, "GET", SITE, "");
    assertThat(get.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(get.hint()).contains("azure_api_read");
    Classification check = call(CallRequest.CHANGE, "POST", "/subscriptions/" + SUB
        + "/providers/Microsoft.Storage/checkNameAvailability", ",\"body\":{}");
    assertThat(check.ruleId()).isEqualTo("stage.cloud.is_a_read");
    assertThat(call(CallRequest.READ, "PUT", SITE, ",\"body\":{}").kind()).isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void resourceNamesAreNeverMistakenForActions() {
    assertThat(call("GET", "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.Web/sites/listkeys-demo")
        .kind()).isEqualTo(Classification.Kind.READ);
    assertThat(call("GET", "/subscriptions/" + SUB + "/resourceGroups/getsecret/providers/Microsoft.Web/sites/a")
        .kind()).isEqualTo(Classification.Kind.READ);
  }

  @ParameterizedTest
  @CsvSource({
      "https://management.azure.com/subscriptions/x", "/subscriptions/" + FakeAzure.OTHER_SUB + "/resourceGroups",
      "/providers/Microsoft.ResourceGraph/resources", "/subscriptions/" + FakeAzure.SUB + "/../x",
      "/subscriptions/" + FakeAzure.SUB + "/resourceGroups/a%2Fb", "/subscriptions/" + FakeAzure.SUB + "//x",
      "/subscriptions/" + FakeAzure.SUB + "/resourceGroups?x=1", "/subscriptions/not-a-guid/resourceGroups"})
  void pathsOutsideTheConnectionAreRefused(String path) {
    Classification c = call("GET", path);
    assertThat(c.kind()).as(path).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isIn("stage.cloud.azure_path", "stage.cloud.subscription");
  }

  @Test
  void argumentsAreChecked() {
    assertThat(connector.classify(Configs.call(CallRequest.READ, cfg.name(), "{\"path\":\"" + SITE + "\","
        + "\"apiVersion\":\"latest\"}"), cfg, Configs.policy(Env.STAGE)).kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(call("GET", SITE, ",\"body\":{\"a\":1}").kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(call("TRACE", SITE).kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(call("GET", SITE, ",\"query\":{\"api-version\":\"2020-01-01\"}").kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(call("GET", SITE, ",\"query\":{\"$filter\":\"name eq 'a'\",\"$top\":5}").kind())
        .isEqualTo(Classification.Kind.READ);
  }

  @Test
  void theCloudPolicyNarrowsServicesAndResources() {
    PolicyView compute = Configs.policy(Env.STAGE, "{\"allowedServices\":[\"compute\"]}");
    Classification web = connector.classify(Configs.call(CallRequest.READ, cfg.name(), "{\"path\":\"" + SITE + "\","
        + "\"apiVersion\":\"2023-12-01\"}"), cfg, compute);
    assertThat(web.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(web.ruleId()).isEqualTo("stage.cloud.service_not_allowed");
    assertThat(web.reason()).contains("compute is allowed on stage");

    PolicyView appService = Configs.policy(Env.STAGE, "{\"allowedServices\":[\"appservice\"]}");
    assertThat(connector.classify(Configs.call(CallRequest.READ, cfg.name(), "{\"path\":\"" + SITE + "\","
        + "\"apiVersion\":\"2023-12-01\"}"), cfg, appService).kind()).isEqualTo(Classification.Kind.READ);

    PolicyView masks = Configs.policy(Env.STAGE, "{\"resources\":[\"/subscriptions/*/resourceGroups/prod-*\"]}");
    Classification masked = connector.classify(Configs.call(CallRequest.READ, cfg.name(), "{\"path\":\"" + SITE + "\","
        + "\"apiVersion\":\"2023-12-01\"}"), cfg, masks);
    assertThat(masked.ruleId()).isEqualTo("stage.cloud.resource_not_allowed");
    PolicyView stageMask = Configs.policy(Env.STAGE, "{\"resources\":[\"/SUBSCRIPTIONS/*/resourcegroups/STAGE-*\"]}");
    assertThat(connector.classify(Configs.call(CallRequest.READ, cfg.name(), "{\"path\":\"" + SITE + "/config/web\","
        + "\"apiVersion\":\"2023-12-01\"}"), cfg, stageMask).kind()).isEqualTo(Classification.Kind.READ);
  }

  @Test
  void keyVaultAndRoleAssignmentsNeedToBeNamed() {
    String vault = "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.KeyVault/vaults/kv";
    Classification hidden = call("GET", vault);
    assertThat(hidden.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(hidden.ruleId()).isEqualTo("stage.cloud.sensitive_service");
    assertThat(connector.classify(Configs.call(CallRequest.READ, cfg.name(), "{\"path\":\"" + vault + "\","
        + "\"apiVersion\":\"2023-07-01\"}"), cfg, Configs.policy(Env.STAGE, "{\"allowedServices\":[\"keyvault\"]}"))
        .kind()).isEqualTo(Classification.Kind.READ);
    Classification roles = connector.classify(Configs.call("azure_describe", cfg.name(),
        "{\"query\":\"authorizationresources | where type =~ 'microsoft.authorization/roleassignments'\"}"), cfg,
        Configs.policy(Env.STAGE));
    assertThat(roles.ruleId()).isEqualTo("stage.cloud.sensitive_service");
  }

  @Test
  void anApprovalShowsTheExactRequest() {
    ActionPreview p = connector.describeAction(Configs.call(CallRequest.CHANGE, cfg.name(), "{\"method\":\"POST\","
        + "\"path\":\"" + SITE + "/restart\",\"apiVersion\":\"2023-12-01\",\"query\":{\"softRestart\":true}}"), cfg);
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(p.text()).startsWith("POST https://management.azure.com" + SITE
        + "/restart?api-version=2023-12-01&softRestart=true\nAuthorization: Bearer ‹masked›");
    assertThat(p.payload().path("tool").asString()).isEqualTo("azure_api_change");
    assertThat(p.summary()).startsWith("azure_api_change on azure-stage: POST");
    assertThat(p.payload().at("/arguments/query/softRestart").asString()).isEqualTo("true");
    assertThat(p.fields()).extracting(ActionPreview.Field::label).contains("Subscription", "Resource group",
        "Resource", "API version");
    ActionPreview delete = connector.describeAction(Configs.call(CallRequest.CHANGE, cfg.name(), "{\"method\":\"DELETE\","
        + "\"path\":\"" + SITE + "\",\"apiVersion\":\"2023-12-01\"}"), cfg);
    assertThat(delete.warnings()).anyMatch(w -> w.contains("can't undo"));
  }

  @Test
  void describeRefusesControlCommandsAndOtherSubscriptions() {
    assertThat(connector.classify(Configs.call("azure_describe", cfg.name(), "{\"query\":\".show tables\"}"), cfg,
        Configs.policy(Env.STAGE)).ruleId()).isEqualTo("stage.cloud.kql_command");
    assertThat(connector.classify(Configs.call("azure_describe", cfg.name(), "{\"id\":\"/subscriptions/"
        + FakeAzure.OTHER_SUB + "/resourceGroups/rg\"}"), cfg, Configs.policy(Env.STAGE)).ruleId())
        .isEqualTo("stage.cloud.subscription");
    assertThat(connector.classify(Configs.call("azure_describe", cfg.name(), "{\"subscription\":\""
        + FakeAzure.OTHER_SUB + "\"}"), cfg, Configs.policy(Env.STAGE)).ruleId())
        .isEqualTo("stage.cloud.subscription");
    assertThat(connector.classify(Configs.call("azure_describe", cfg.name(), "{}"), cfg, Configs.policy(Env.STAGE))
        .kind()).isEqualTo(Classification.Kind.READ);
  }

  @Test
  void monitorRangesAndPointsFollowThePolicy() {
    String vm = "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/vm1";
    Classification tooLong = connector.classify(Configs.call("azure_monitor", cfg.name(), "{\"resource\":\"" + vm
        + "\",\"metrics\":[\"Percentage CPU\"],\"from\":\"-30d\"}"), cfg, Configs.policy(Env.STAGE));
    assertThat(tooLong.ruleId()).isEqualTo("stage.cloud.range");
    assertThat(connector.classify(Configs.call("azure_monitor", cfg.name(), "{\"resource\":\"" + vm
        + "\",\"metrics\":[\"Percentage CPU\"],\"from\":\"-7d\",\"interval\":\"PT1M\"}"), cfg,
        Configs.policy(Env.STAGE)).kind()).isEqualTo(Classification.Kind.READ);
    ActionPreview raised = connector.describeAction(Configs.call("azure_monitor", cfg.name(), "{\"resource\":\"" + vm
        + "\",\"metrics\":[\"Percentage CPU\"],\"from\":\"-7d\",\"interval\":\"PT1M\"}"), cfg);
    assertThat(raised.text()).contains("interval PT15M").contains("timespan: ");
    assertThat(connector.classify(Configs.call("azure_monitor", cfg.name(), "{\"action\":\"query\"}"), cfg,
        Configs.policy(Env.STAGE)).kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(connector.classify(Configs.call("azure_monitor", cfg.name(), "{\"action\":\"activity_log\","
        + "\"from\":\"-100d\"}"), cfg, Configs.policy(Env.STAGE, "{\"maxRangeDays\":120}")).ruleId())
        .isEqualTo("stage.cloud.range");
    assertThat(connector.classify(Configs.call("azure_monitor", cfg.name(), "{\"action\":\"alerts\","
        + "\"timeRange\":\"30d\"}"), cfg, Configs.policy(Env.STAGE)).ruleId()).isEqualTo("stage.cloud.range");
    assertThat(connector.classify(Configs.call("azure_monitor", cfg.name(), "{\"action\":\"alerts\","
        + "\"severity\":\"Sev9\"}"), cfg, Configs.policy(Env.STAGE)).kind()).isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void brokenSettingsDenyEveryCall() {
    ConnectionConfig broken = Configs.of("broken", Env.DEV, "{\"subscriptions\":[\"nope\"],\"credentials\":"
        + "{\"source\":\"cli\"}}");
    Classification c = connector.classify(Configs.call("azure_describe", "broken", "{}"), broken,
        Configs.policy(Env.DEV));
    assertThat(c.ruleId()).isEqualTo("dev.cloud.config_invalid");
    assertThat(c.reason()).contains("isn't a subscription ID");
  }

  @Test
  void theLogToolsAreOnlyWhereTheConnectionNamesSources() {
    assertThat(connector.supportsTool("logs_search", cfg)).isTrue();
    assertThat(connector.supportsTool("logs_search", Configs.prod())).isFalse();
    assertThat(connector.supportsTool(CallRequest.CHANGE, Configs.prod())).isTrue();
    assertThat(connector.previewTools(Configs.prod())).extracting(t -> t.name())
        .containsExactly("azure_describe", "azure_api_read", "azure_api_change", "azure_monitor");
  }
}
