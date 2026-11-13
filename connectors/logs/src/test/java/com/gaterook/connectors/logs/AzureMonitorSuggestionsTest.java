package com.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Azure Monitor Logs form: the Azure CLI's tenants and cloud on this computer, and once the draft signs in
 * to Resource Manager, the workspaces and Application Insights components of its subscriptions. The CLI never runs
 * before Test connection had the person allow it, and a device sign-in never starts for a suggestion.
 */
class AzureMonitorSuggestionsTest {

  private static final String TENANT = "6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  private static final String CLIENT = "0a1b2c3d-1111-4222-8333-444455556666";
  private static final String PROD = "0b1f6471-1bf0-4dda-aec3-111122223333";
  private static final String STAGE = "1c2f7582-2cf1-4eeb-bfd4-222233334444";
  private static final String WORKSPACE = "5b2f8c1e-0a3d-4e6f-9b7a-1c2d3e4f5a6b";

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private volatile String tokenTenant = TENANT;
  private CloudEndpoints endpoints;
  private CloudCredentials cloud;

  @TempDir
  Path home;

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.endsWith("/oauth2/v2.0/token")) {
          return json(200, "{\"expires_in\":3599,\"access_token\":\"" + token() + "\"}");
        }
        if (path.equals("/subscriptions")) {
          return json(200, "{\"value\":[{\"subscriptionId\":\"" + PROD + "\",\"displayName\":\"Production\","
              + "\"state\":\"Enabled\"},{\"subscriptionId\":\"" + STAGE + "\",\"displayName\":\"Staging\",\"state\":"
              + "\"Enabled\"},{\"subscriptionId\":\"2d3e4f50-0000-4000-8000-000000000000\",\"displayName\":"
              + "\"Old\",\"state\":\"Disabled\"}]}");
        }
        if (path.equals("/providers/Microsoft.ResourceGraph/resources")) {
          return json(200, "{\"totalRecords\":2,\"count\":2,\"data\":[{\"id\":\"/subscriptions/" + PROD
              + "/resourceGroups/obs/providers/Microsoft.OperationalInsights/workspaces/prod-logs\",\"name\":"
              + "\"prod-logs\",\"type\":\"microsoft.operationalinsights/workspaces\",\"location\":\"westeurope\","
              + "\"subscriptionId\":\"" + PROD + "\",\"resourceGroup\":\"obs\",\"customerId\":\""
              + WORKSPACE.toUpperCase() + "\"},{\"id\":\"/subscriptions/" + STAGE + "/resourcegroups/stage-app/"
              + "providers/microsoft.insights/components/stage-api\",\"name\":\"stage-api\",\"type\":"
              + "\"microsoft.insights/components\",\"location\":\"northeurope\",\"subscriptionId\":\"" + STAGE
              + "\",\"resourceGroup\":\"stage-app\",\"customerId\":\"\"}]}");
        }
        return json(404, "{\"error\":{\"code\":\"NotFound\",\"message\":\"no such API\"}}");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    URI base = server.url("/").uri();
    // The Azure CLI exists, as on a person's computer: it must still never run for a suggestion.
    Path az = Files.writeString(home.resolve("az"), "#!/bin/sh\necho ran > " + home.resolve("ran") + "\n");
    az.toFile().setExecutable(true);
    endpoints = new CloudEndpoints(null, null, null, null, null, null, Map.of("azure.login", base, "azure.arm", base),
        az);
    cloud = new CloudCredentials(endpoints, Clock.systemUTC());
  }

  @AfterEach
  void stop() throws IOException {
    cloud.close();
    server.close();
  }

  private String token() {
    ObjectNode c = TestCtx.JSON.createObjectNode().put("tid", tokenTenant).put("appid", CLIENT).put("idtyp", "app")
        .put("exp", Instant.now().plusSeconds(3600).getEpochSecond());
    Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
    return e.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "." + e.encodeToString(
        TestCtx.JSON.writeValueAsBytes(c)) + ".c2ln";
  }

  private Suggestions suggest(String json, Suggestions.Scope scope) throws Exception {
    try (BackendConnector connector = new BackendConnector("azure-monitor", cloud, Clock.systemUTC(),
        new CloudLocal(endpoints, k -> null, home))) {
      return connector.suggest(new ConnectionConfig("draft-1", "draft", "azure-monitor", Env.DEV, 0,
          TestCtx.JSON.readTree(json), Set.of("/credentials/clientSecret"), null),
          new TestSecrets().with("/credentials/clientSecret", "cnry-azure-suggest"), TestCtx.of(), scope);
    }
  }

  private void azureProfile(String subscriptions) throws IOException {
    Files.createDirectories(home.resolve(".azure"));
    Files.writeString(home.resolve(".azure/azureProfile.json"), "﻿{\"subscriptions\":[" + subscriptions + "]}");
  }

  @Test
  void theAzureClisTenantIsFilledInForASignInThatNeedsOne() throws Exception {
    azureProfile("{\"id\":\"" + PROD + "\",\"name\":\"Production\",\"tenantId\":\"" + TENANT + "\",\"isDefault\":"
        + "true,\"environmentName\":\"AzureCloud\",\"state\":\"Enabled\"},{\"id\":\"" + STAGE + "\",\"name\":"
        + "\"Staging\",\"tenantId\":\"" + TENANT + "\",\"environmentName\":\"AzureCloud\",\"state\":\"Enabled\"}");

    Suggestions principal = suggest("{\"backend\":\"azure-monitor\",\"credentials\":{\"source\":\"clientSecret\","
        + "\"clientId\":\"" + CLIENT + "\"}}", Suggestions.Scope.LOCAL);
    Suggestions cli = suggest("{\"backend\":\"azure-monitor\",\"credentials\":{\"source\":\"cli\"}}",
        Suggestions.Scope.LOCAL);

    assertThat(principal.fields().get("/tenantId").value()).isEqualTo(TENANT);
    assertThat(principal.fields().get("/tenantId").options().getFirst().detail()).isEqualTo("Production, Staging");
    assertThat(principal.fields().get("/cloud").value()).isEqualTo("AzureCloud");
    assertThat(cli.fields().get("/tenantId").value()).as("empty uses the CLI's default tenant").isNull();
    assertThat(cli.fields().get("/tenantId").options()).extracting(Option::value).containsExactly(TENANT);
    assertThat(principal.notes()).isEmpty();
    assertThat(requests).isEmpty();
  }

  @Test
  void withoutAnAzureCliSignInItSaysWhatToDo() throws Exception {
    Suggestions cli = suggest("{\"backend\":\"azure-monitor\",\"credentials\":{\"source\":\"cli\"}}",
        Suggestions.Scope.LOCAL);
    Suggestions principal = suggest("{\"backend\":\"azure-monitor\",\"credentials\":{\"source\":\"clientSecret\"}}",
        Suggestions.Scope.LOCAL);

    assertThat(cli.notes()).containsExactly("The Azure CLI isn't signed in on this computer: run az login, or choose "
        + "another sign-in.");
    assertThat(principal.notes()).containsExactly("A service principal or a device sign-in signs in to one tenant: "
        + "set Tenant (directory) ID.");
  }

  @Test
  void signedInItListsTheWorkspacesAndComponentsOfTheSubscriptions() throws Exception {
    Suggestions s = suggest("{\"backend\":\"azure-monitor\",\"tenantId\":\"" + TENANT + "\",\"credentials\":{"
        + "\"source\":\"clientSecret\",\"clientId\":\"" + CLIENT + "\"}}", Suggestions.Scope.SERVICE);

    Suggestions.Field workspaces = s.fields().get("/workspaces");
    assertThat(workspaces.source()).isEqualTo("your Azure subscriptions");
    assertThat(workspaces.options()).singleElement().satisfies(o -> {
      assertThat(o.value()).isEqualTo(WORKSPACE);
      assertThat(o.label()).isEqualTo("prod-logs");
      assertThat(o.detail()).isEqualTo("westeurope · Production");
    });
    assertThat(s.fields().get("/applicationInsights").options()).extracting(Option::value).containsExactly(
        "/subscriptions/" + STAGE + "/resourceGroups/stage-app/providers/Microsoft.Insights/components/stage-api");
    assertThat(s.notes()).containsExactly("Signed in as " + CLIENT + ": 1 Log Analytics workspace and 1 Application "
        + "Insights component in 2 subscriptions. Choose the ones agents may query.");

    RecordedRequest login = requests.getFirst();
    assertThat(URLDecoder.decode(login.getBody().utf8(), StandardCharsets.UTF_8))
        .contains("scope=https://management.azure.com/.default");
    RecordedRequest graph = requests.stream().filter(r -> r.getUrl().encodedPath().startsWith("/providers/"))
        .findFirst().orElseThrow();
    JsonNode body = TestCtx.JSON.readTree(graph.getBody().utf8());
    assertThat(body.path("subscriptions").toString()).isEqualTo("[\"" + PROD + "\",\"" + STAGE + "\"]");
    assertThat(body.path("query").asString()).startsWith("resources | where type =~ "
        + "'microsoft.operationalinsights/workspaces'");
    assertThat(requests.subList(1, requests.size())).hasSize(2)
        .allSatisfy(r -> assertThat(r.getHeaders().get("Authorization")).startsWith("Bearer "));
  }

  @Test
  void theAzureCliRunsOnlyOnceTestConnectionHadItAllowed() throws Exception {
    Suggestions s = suggest("{\"backend\":\"azure-monitor\",\"credentials\":{\"source\":\"cli\"}}",
        Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("Workspaces and components appear after Test connection: it asks you to "
        + "allow the Azure CLI command first.");
    assertThat(requests).isEmpty();
    assertThat(home.resolve("ran")).doesNotExist();
  }

  @Test
  void aDeviceSignInNeverStartsForASuggestion() throws Exception {
    Suggestions s = suggest("{\"backend\":\"azure-monitor\",\"tenantId\":\"" + TENANT + "\",\"credentials\":{"
        + "\"source\":\"deviceCode\"}}", Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("Workspaces and components appear after Test connection: sign in with the "
        + "code it shows first.");
    assertThat(requests).isEmpty();
  }

  @Test
  void aSignInOfAnotherTenantListsNothing() throws Exception {
    tokenTenant = "99999999-9999-4999-8999-999999999999";

    Suggestions s = suggest("{\"backend\":\"azure-monitor\",\"tenantId\":\"" + TENANT + "\",\"credentials\":{"
        + "\"source\":\"clientSecret\",\"clientId\":\"" + CLIENT + "\"}}", Suggestions.Scope.SERVICE);

    assertThat(s.notes()).singleElement().asString().startsWith("The Azure sign-in belongs to tenant 99999999-");
    assertThat(s.fields()).isEmpty();
    assertThat(requests).hasSize(1);
  }

  private static MockResponse json(int status, String body) {
    return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build();
  }
}
