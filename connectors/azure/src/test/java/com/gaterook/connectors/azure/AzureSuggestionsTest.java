package com.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.AzureSpec;
import com.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Azure form: the Azure CLI's subscriptions, tenants and cloud on this computer, and once the draft signs
 * in, the subscriptions, Log Analytics workspaces and Application Insights components of its identity. The Azure CLI
 * never runs and a device sign-in never starts for a suggestion.
 */
class AzureSuggestionsTest {

  private static final String SUB = FakeAzure.SUB;
  private static final String OTHER = FakeAzure.OTHER_SUB;
  private static final String GOV_SUB = "44444444-4444-4444-8444-444444444444";
  private static final String OTHER_TENANT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String WORKSPACE = "5b2f8c1e-0a3d-4e6f-9b7a-1c2d3e4f5a6b";

  @TempDir
  Path home;

  private FakeAzure azure;
  private AzureConnector connector;

  @BeforeEach
  void start() throws IOException {
    azure = new FakeAzure();
    connector = new AzureConnector(azure.cloud, Clock.systemUTC(), local());
  }

  @AfterEach
  void stop() {
    connector.close();
    azure.close();
  }

  private CloudLocal local() {
    return new CloudLocal(azure.cloud.endpoints(), Map.<String, String>of()::get, home);
  }

  /** {@code az login}'s profile: Stage (the default) and Other in the fake tenant, Gov in another tenant's cloud. */
  private void profile() throws IOException {
    Path dir = Files.createDirectories(home.resolve(".azure"));
    Files.writeString(dir.resolve("azureProfile.json"), "﻿" + """
        {"installationId":"x","subscriptions":[
          {"id":"%s","name":"Other","state":"Enabled","isDefault":false,"tenantId":"%s",
           "environmentName":"AzureCloud"},
          {"id":"%s","name":"Stage","state":"Enabled","isDefault":true,"tenantId":"%s",
           "environmentName":"AzureCloud"},
          {"id":"%s","name":"Gov","state":"Enabled","isDefault":false,"tenantId":"%s",
           "environmentName":"AzureUSGovernment"}]}""".formatted(OTHER, FakeAzure.TENANT, SUB, FakeAzure.TENANT,
        GOV_SUB, OTHER_TENANT));
  }

  private static ConnectionConfig draft(String json, String... secretFields) {
    return new ConnectionConfig("draft-1", "draft", "azure", Env.DEV, 1, TestCtx.JSON.readTree(json),
        Set.of(secretFields), null);
  }

  private static ConnectionConfig servicePrincipal(String subscriptions) {
    return draft("{\"tenantId\":\"" + FakeAzure.TENANT + "\",\"subscriptions\":[" + subscriptions + "],"
        + "\"credentials\":{\"source\":\"clientSecret\",\"clientId\":\"" + FakeAzure.CLIENT + "\"}}",
        "/credentials/clientSecret");
  }

  private void subscriptions() {
    azure.on("GET /subscriptions", r -> FakeAzure.json(200, """
        {"value":[
          {"subscriptionId":"%s","displayName":"Stage","state":"Enabled","tenantId":"%s"},
          {"subscriptionId":"%s","displayName":"Other","state":"Enabled","tenantId":"%s"},
          {"subscriptionId":"55555555-5555-4555-8555-555555555555","displayName":"Old","state":"Disabled",
           "tenantId":"%s"},
          {"subscriptionId":"66666666-6666-4666-8666-666666666666","displayName":"Delegated","state":"Enabled",
           "tenantId":"%s"}]}""".formatted(SUB, FakeAzure.TENANT, OTHER, FakeAzure.TENANT, FakeAzure.TENANT,
        OTHER_TENANT)));
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theCliProfileFillsInItsDefaultSubscriptionAndItsTenantAndCloud() throws Exception {
    profile();

    Suggestions s = connector.suggest(draft("{\"credentials\":{\"source\":\"cli\"}}"), new TestSecrets(),
        TestCtx.of(), Suggestions.Scope.LOCAL);

    Suggestions.Field subs = s.fields().get("/subscriptions");
    assertThat(subs.value()).isEqualTo(SUB);
    assertThat(subs.source()).isEqualTo(AzureSuggestions.CLI);
    assertThat(subs.options()).extracting(Option::value).containsExactly(SUB, OTHER, GOV_SUB);
    assertThat(subs.options().getFirst().label()).isEqualTo("Stage (default)");
    // Two tenants on this computer, but the default subscription is in one of them.
    assertThat(s.fields().get("/tenantId").value()).isEqualTo(FakeAzure.TENANT);
    assertThat(s.fields().get("/tenantId").options()).extracting(Option::value)
        .containsExactly(FakeAzure.TENANT, OTHER_TENANT);
    assertThat(s.fields().get("/cloud").value()).isEqualTo("AzureCloud");
    assertThat(s.notes()).isEmpty();
    // Local suggestions never leave the computer.
    assertThat(azure.requests).isEmpty();
  }

  @Test
  void chosenSubscriptionsBringTheirOwnTenantAndCloud() throws Exception {
    profile();

    Suggestions s = connector.suggest(draft("{\"subscriptions\":[\"" + GOV_SUB + "\"],"
        + "\"credentials\":{\"source\":\"cli\"}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);

    // The list isn't empty: nothing more is filled into it.
    assertThat(s.fields().get("/subscriptions").value()).isNull();
    assertThat(s.fields().get("/tenantId").value()).isEqualTo(OTHER_TENANT);
    assertThat(s.fields().get("/cloud").value()).isEqualTo("AzureUSGovernment");
    assertThat(s.fields().get("/cloud").options().getFirst().label()).isEqualTo("Azure Government");
  }

  @Test
  void subscriptionsOfTwoTenantsAreOneConnectionTooMany() throws Exception {
    profile();

    Suggestions s = connector.suggest(draft("{\"subscriptions\":[\"" + SUB + "\",\"" + GOV_SUB + "\"],"
        + "\"credentials\":{\"source\":\"cli\"}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/tenantId").value()).isNull();
    assertThat(s.fields()).doesNotContainKey("/cloud");
    assertThat(s.notes()).containsExactly("The chosen subscriptions belong to 2 tenants: a connection signs in to "
        + "one, so make one connection per tenant.");
  }

  @Test
  void withoutAnAzureCliSignInTheCliChoiceSaysWhatToRun() {
    Suggestions cli = connector.suggest(draft("{\"credentials\":{\"source\":\"cli\"}}"), new TestSecrets(),
        TestCtx.of(), Suggestions.Scope.LOCAL);
    Suggestions sp = connector.suggest(servicePrincipal(""), new TestSecrets(), TestCtx.of(),
        Suggestions.Scope.LOCAL);

    assertThat(cli.fields()).isEmpty();
    assertThat(cli.notes()).containsExactly("The Azure CLI isn't signed in on this computer: run az login, or choose "
        + "another way to sign in.");
    // A service principal doesn't need the CLI: nothing to say.
    assertThat(sp.isEmpty()).isTrue();
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aServicePrincipalListsItsSubscriptionsWorkspacesAndComponents() {
    subscriptions();
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.OperationalInsights/workspaces", r -> FakeAzure.json(
        200, """
        {"value":[
          {"name":"stage-logs","location":"westeurope","properties":{"customerId":"%s"},
           "id":"/subscriptions/%s/resourceGroups/stage-ops/providers/Microsoft.OperationalInsights/workspaces/stage-logs"},
          {"name":"broken","properties":{}}]}""".formatted(WORKSPACE.toUpperCase(), SUB)));
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.Insights/components", r -> FakeAzure.json(200, """
        {"value":[{"name":"stage-api","location":"westeurope","kind":"web",
          "id":"/subscriptions/%s/resourcegroups/stage-app/providers/microsoft.insights/components/stage-api"}]}"""
        .formatted(SUB)));
    String missing = "11111111-2222-4333-8444-555555555555";

    Suggestions s = connector.suggest(servicePrincipal("\"" + SUB + "\",\"" + missing + "\""), Configs.secrets(),
        TestCtx.of(), Suggestions.Scope.SERVICE);

    Suggestions.Field subs = s.fields().get("/subscriptions");
    assertThat(subs.source()).isEqualTo(AzureSuggestions.AZURE);
    // Enabled subscriptions of the sign-in's tenant only, by name.
    assertThat(subs.options()).extracting(Option::value).containsExactly(OTHER, SUB);
    assertThat(subs.options()).extracting(Option::label).containsExactly("Other", "Stage");
    assertThat(subs.value()).isNull();
    assertThat(s.fields().get("/logAnalyticsWorkspaces").options()).singleElement().satisfies(o -> {
      assertThat(o.value()).isEqualTo(WORKSPACE);
      assertThat(o.label()).isEqualTo("stage-logs");
      assertThat(o.detail()).isEqualTo("stage-ops · westeurope");
    });
    // The form's spelling of the ID, whatever case Resource Manager answered in.
    assertThat(s.fields().get("/applicationInsights").options()).extracting(Option::value).containsExactly(
        "/subscriptions/" + SUB + "/resourceGroups/stage-app/providers/Microsoft.Insights/components/stage-api");
    assertThat(s.notes()).containsExactly(
        "Signed in as " + FakeAzure.CLIENT + ": 2 subscriptions. Choose the ones agents may read.",
        FakeAzure.CLIENT + " can't see subscription " + missing + ": assign the Reader role on it, or choose one it "
            + "can see.",
        "1 Log Analytics workspace and 1 Application Insights component in the chosen subscriptions: add the ones "
            + "logs_search and logs_tail may read.");
    assertThat(azure.api()).extracting(RecordedRequest::getMethod).containsOnly("GET");
    // A draft's tokens go when its suggestions are done.
    assertThat(azure.issued).isPositive();
    assertThat(azure.cloud.issued()).isEmpty();
  }

  @Test
  void withSubscriptionsStillEmptyTheOnlyOneTheIdentitySeesIsFilledInWithItsLogs() {
    azure.on("GET /subscriptions", r -> FakeAzure.json(200, "{\"value\":[{\"subscriptionId\":\"" + SUB + "\","
        + "\"displayName\":\"Stage\",\"state\":\"Enabled\",\"tenantId\":\"" + FakeAzure.TENANT + "\"}]}"));
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.OperationalInsights/workspaces", r -> FakeAzure.json(
        200, "{\"value\":[{\"name\":\"stage-logs\",\"properties\":{\"customerId\":\"" + WORKSPACE + "\"}}]}"));
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.Insights/components", r -> FakeAzure.json(200,
        "{\"value\":[]}"));

    // Subscriptions is marked x-suggest: the core asks before it holds a valid ID, a half-typed one included.
    for (String typed : new String[] {"", "\"0b1f6471-1bf0\""}) {
      Suggestions s = connector.suggest(servicePrincipal(typed), Configs.secrets(), TestCtx.of(),
          Suggestions.Scope.SERVICE);

      assertThat(s.fields().get("/subscriptions").value()).as(typed).isEqualTo(SUB);
      assertThat(s.fields().get("/logAnalyticsWorkspaces").options()).extracting(Option::value)
          .containsExactly(WORKSPACE);
      assertThat(s.notes()).first().asString().isEqualTo("Signed in as " + FakeAzure.CLIENT + ": 1 subscription. "
          + "Choose the ones agents may read.");
    }
  }

  @Test
  void withSubscriptionsStillEmptySeveralAreOfferedAndNoneFilledIn() {
    subscriptions();

    Suggestions s = connector.suggest(servicePrincipal(""), Configs.secrets(), TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/subscriptions").value()).isNull();
    assertThat(s.fields().get("/subscriptions").options()).extracting(Option::value).containsExactly(OTHER, SUB);
    // No subscription chosen yet: no workspace or component list.
    assertThat(s.fields()).containsOnlyKeys("/subscriptions");
    assertThat(azure.to("GET /subscriptions/")).isEmpty();
  }

  @Test
  void aListTheIdentityMayNotReadIsANote() {
    subscriptions();
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.OperationalInsights/workspaces", r -> FakeAzure.json(
        403, "{\"error\":{\"code\":\"AuthorizationFailed\",\"message\":\"no\"}}"));
    azure.on("GET /subscriptions/" + SUB + "/providers/Microsoft.Insights/components", r -> FakeAzure.json(200,
        "{\"value\":[]}"));

    Suggestions s = connector.suggest(servicePrincipal("\"" + SUB + "\""), Configs.secrets(), TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.fields()).containsOnlyKeys("/subscriptions");
    assertThat(s.notes()).contains("The identity may not list Log Analytics workspaces in Stage: type the ones the "
        + "log tools read.");
  }

  @Test
  void aSignInOfAnotherTenantIsANote() {
    azure.tokenTenant = OTHER_TENANT;

    Suggestions s = connector.suggest(servicePrincipal("\"" + SUB + "\""), Configs.secrets(), TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("The sign-in belongs to tenant " + OTHER_TENANT + ", not "
        + FakeAzure.TENANT + ": check the tenant ID.");
    assertThat(azure.api()).isEmpty();
  }

  @Test
  void aDeviceSignInIsNeverStartedForASuggestion() {
    ConnectionConfig device = draft("{\"tenantId\":\"" + FakeAzure.TENANT + "\",\"subscriptions\":[\"" + SUB
        + "\"],\"credentials\":{\"source\":\"deviceCode\"}}");

    Suggestions s = connector.suggest(device, new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("Choose Test connection to sign in with a code: the form then lists the "
        + "subscriptions, workspaces and components the account can read.");
    // No code was asked for, nothing reached Entra ID.
    assertThat(azure.requests).isEmpty();
  }

  @Test
  void aDeviceSignInCompletedInTestConnectionListsWhatTheAccountCanRead() {
    subscriptions();
    azure.on("POST /" + FakeAzure.TENANT + "/oauth2/v2.0/devicecode", r -> FakeAzure.json(200, "{\"device_code\":"
        + "\"dc-1\",\"user_code\":\"QWER-TYUI\",\"verification_uri\":\"https://microsoft.com/devicelogin\","
        + "\"expires_in\":900,\"interval\":5}"));
    azure.on("POST /token", r -> FakeAzure.json(200, "{\"access_token\":\"" + azure.token() + "\","
        + "\"refresh_token\":\"rt-1\",\"expires_in\":3600}"));
    azure.on("GET /subscriptions/" + SUB + "/providers/", r -> FakeAzure.json(200, "{\"value\":[]}"));
    ConnectionConfig device = draft("{\"tenantId\":\"" + FakeAzure.TENANT + "\",\"subscriptions\":[\"" + SUB
        + "\"],\"credentials\":{\"source\":\"deviceCode\"}}");
    assertThat(connector.test(device, new TestSecrets(), TestCtx.of()).needsConfirmation()).isNotNull();
    // The person entered the code; Test connection collects the sign-in.
    connector.test(device, new TestSecrets(), TestCtx.of());

    Suggestions s = connector.suggest(device, new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/subscriptions").options()).extracting(Option::value).containsExactly(OTHER, SUB);
    assertThat(s.notes()).first().asString().startsWith("Signed in as ");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void theAzureCliNeverRunsForASuggestionEvenOnceAllowed() throws Exception {
    Path az = home.resolve("az");
    Files.writeString(az, "#!/bin/sh\necho ran >> '" + home.resolve("runs") + "'\nexit 1\n");
    Files.setPosixFilePermissions(az, PosixFilePermissions.fromString("rwx------"));
    try (FakeAzure local = new FakeAzure(az);
        AzureConnector cli = new AzureConnector(local.cloud, Clock.systemUTC(), local())) {
      ObjectNode config = (ObjectNode) TestCtx.JSON.readTree("{\"subscriptions\":[\"" + SUB + "\"],"
          + "\"credentials\":{\"source\":\"cli\"}}");
      String allowed = local.cloud.azure().cliCommand(AzureSpec.fromConfig(config, "/credentials")).fingerprint();
      config.put("trustedCli", allowed);

      Suggestions s = cli.suggest(new ConnectionConfig("draft-2", "draft", "azure", Env.DEV, 1, config, Set.of(),
          null), new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

      assertThat(s.notes()).containsExactly("Workspaces and Application Insights components aren't listed for an "
          + "Azure CLI sign-in: GateRook runs az only for Test connection and agents' calls.");
      assertThat(home.resolve("runs")).doesNotExist();
      assertThat(local.requests).isEmpty();
    }
  }

  @Test
  void componentIdsTakeTheFormsSpellingAndNothingElse() {
    assertThat(AzureSuggestions.componentId("/subscriptions/" + SUB.toUpperCase()
        + "/resourcegroups/rg/providers/microsoft.insights/components/api")).isEqualTo("/subscriptions/" + SUB
        + "/resourceGroups/rg/providers/Microsoft.Insights/components/api");
    assertThat(AzureSuggestions.componentId("/subscriptions/" + SUB
        + "/resourceGroups/rg/providers/Microsoft.Insights/components/api/extra")).isNull();
    assertThat(AzureSuggestions.componentId("/subscriptions/" + SUB
        + "/resourceGroups/rg/providers/Microsoft.Web/sites/api")).isNull();
    assertThat(AzureSuggestions.componentId("/subscriptions/" + SUB + "/providers/Microsoft.Insights/components/a"))
        .isNull();
  }
}
