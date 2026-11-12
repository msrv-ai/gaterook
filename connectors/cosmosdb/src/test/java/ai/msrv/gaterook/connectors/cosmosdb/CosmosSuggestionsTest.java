package ai.msrv.gaterook.connectors.cosmosdb;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Cosmos DB form: the endpoint of a pasted connection string, an account name or the emulator, the Azure
 * CLI's tenants for Microsoft Entra ID, and once the draft signs in, the databases and containers it may read.
 */
class CosmosSuggestionsTest {

  private static final String STRING = "AccountEndpoint=https://acme.documents.azure.com:443/;AccountKey="
      + FakeCosmos.KEY + ";";

  @TempDir
  Path home;

  private FakeCosmos fake;
  private CosmosDbConnector cosmos;
  private final List<Integer> probed = new CopyOnWriteArrayList<>();
  private volatile CosmosSuggestions.Tls emulator = CosmosSuggestions.Tls.NONE;
  private final TestSecrets key = new TestSecrets().with(CosmosSettings.ACCOUNT_KEY, FakeCosmos.KEY);

  @BeforeEach
  void start() throws IOException {
    fake = new FakeCosmos();
    cosmos = connector(true);
  }

  @AfterEach
  void stop() {
    cosmos.close();
    fake.close();
  }

  private CosmosDbConnector connector(boolean plainLoopback) {
    CloudLocal local = new CloudLocal(null, Map.<String, String>of()::get, home);
    return new CosmosDbConnector(plainLoopback, fake.base(), fake.base(), local, port -> {
      probed.add(port);
      return emulator;
    });
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-1", "draft", "cosmosdb", Env.DEV, 1, TestCtx.JSON.readTree(json),
        Set.of(CosmosSettings.ACCOUNT_KEY, CosmosSettings.CLIENT_SECRET), null);
  }

  private Suggestions local(String json, TestSecrets secrets) {
    return cosmos.suggest(draft(json), secrets, TestCtx.of(), Suggestions.Scope.LOCAL);
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aConnectionStringInAccountEndpointGivesItsEndpointAndNeverItsKey() {
    ObjectNode c = (ObjectNode) TestCtx.JSON.readTree("{\"auth\": {\"method\": \"key\"}}");
    c.put("endpoint", STRING);

    Suggestions s = cosmos.suggest(new ConnectionConfig("draft-1", "draft", "cosmosdb", Env.DEV, 1, c, Set.of(),
        null), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/endpoint").value()).isEqualTo("https://acme.documents.azure.com");
    assertThat(s.fields().get("/endpoint").source()).isEqualTo("the connection string");
    assertThat(s.notes()).containsExactly("That is the account's connection string: its endpoint goes into Account "
        + "endpoint, the text after AccountKey= into Account key.");
    assertThat(s.toString()).doesNotContain(FakeCosmos.KEY);
    // An Azure endpoint isn't the emulator's: its port isn't tried.
    assertThat(probed).isEmpty();
  }

  @Test
  void aConnectionStringPastedAsTheKeyFillsInTheEndpoint() {
    Suggestions s = local("{\"auth\": {\"method\": \"key\"}}", new TestSecrets().with(CosmosSettings.ACCOUNT_KEY,
        STRING));

    assertThat(s.fields().get("/endpoint").value()).isEqualTo("https://acme.documents.azure.com");
    assertThat(s.notes()).contains("Account key holds the whole connection string: paste only the text after "
        + "AccountKey=.");
    assertThat(s.toString()).doesNotContain(FakeCosmos.KEY);
  }

  @Test
  void anAccountNameTypedAloneIsOfferedAsItsEndpoint() {
    Suggestions s = local("{\"endpoint\": \"Acme\", \"auth\": {\"method\": \"key\"}}", key);

    Suggestions.Field endpoint = s.fields().get("/endpoint");
    assertThat(endpoint.value()).isNull();
    assertThat(endpoint.options()).extracting(Option::value).containsExactly("https://acme.documents.azure.com");
  }

  @Test
  void theEmulatorIsOfferedWithTheSchemeItsPortAnswers() {
    emulator = CosmosSuggestions.Tls.VERIFIED;
    Suggestions verified = local("{\"auth\": {\"method\": \"key\"}}", new TestSecrets());
    assertThat(verified.fields().get("/endpoint").options()).extracting(Option::value)
        .containsExactly("https://localhost:8081");
    assertThat(verified.fields().get("/endpoint").value()).isNull();
    assertThat(verified.notes()).isEmpty();

    emulator = CosmosSuggestions.Tls.PLAIN;
    assertThat(local("{\"auth\": {\"method\": \"key\"}}", new TestSecrets()).fields().get("/endpoint").options())
        .extracting(Option::value).containsExactly("http://localhost:8081");
    assertThat(probed).containsOnly(CosmosSuggestions.EMULATOR_PORT);
  }

  @Test
  void aReleaseBuildSaysWhatStandsInTheEmulatorsWayOnceItIsChosen() {
    try (CosmosDbConnector release = connector(false)) {
      emulator = CosmosSuggestions.Tls.PLAIN;
      Suggestions empty = release.suggest(draft("{\"auth\": {\"method\": \"key\"}}"), new TestSecrets(),
          TestCtx.of(), Suggestions.Scope.LOCAL);
      // Something else may use the port: nothing is offered, nothing said.
      assertThat(empty.isEmpty()).isTrue();

      Suggestions chosen = release.suggest(draft("{\"endpoint\": \"https://localhost:8081\", \"auth\": "
          + "{\"method\": \"key\"}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);
      assertThat(chosen.notes()).containsExactly("Port 8081 answers without TLS: start the emulator with --protocol "
          + "https, as GateRook reaches it over HTTPS.");

      emulator = CosmosSuggestions.Tls.UNTRUSTED;
      Suggestions untrusted = release.suggest(draft("{\"endpoint\": \"https://localhost:8081\", \"auth\": "
          + "{\"method\": \"key\"}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);
      assertThat(untrusted.notes()).singleElement().asString().contains("/_explorer/emulator.pem");
    }
  }

  @Test
  void microsoftEntraIdOffersTheAzureClisTenant() throws IOException {
    Path azure = Files.createDirectories(home.resolve(".azure"));
    Files.writeString(azure.resolve("azureProfile.json"), "{\"subscriptions\":[{\"id\":"
        + "\"0b1f6471-1bf0-4dda-aec3-111122223333\",\"name\":\"Prod\",\"state\":\"Enabled\",\"isDefault\":true,"
        + "\"tenantId\":\"" + FakeCosmos.TENANT + "\"}]}");

    Suggestions entra = local("{\"endpoint\": \"https://acme.documents.azure.com\", \"auth\": {\"method\": "
        + "\"entra\"}}", new TestSecrets());
    Suggestions withKey = local("{\"endpoint\": \"https://acme.documents.azure.com\", \"auth\": {\"method\": "
        + "\"key\"}}", key);

    assertThat(entra.fields().get("/auth/tenantId").value()).isEqualTo(FakeCosmos.TENANT);
    assertThat(entra.fields().get("/auth/tenantId").source()).isEqualTo("your Azure CLI sign-in");
    assertThat(withKey.fields()).doesNotContainKey("/auth/tenantId");
    assertThat(fake.requests).isEmpty();
  }

  @Test
  void theProbeTellsTlsFromPlainTextOnThisComputer() throws Exception {
    // A server that answers anything with an HTTP error in plain text, as the emulator without --protocol https.
    try (ServerSocket plain = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
      Thread server = Thread.ofVirtual().start(() -> {
        while (!plain.isClosed()) {
          try (Socket s = plain.accept()) {
            if (s.getInputStream().read(new byte[512]) > 0) {
              s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes());
            }
          } catch (IOException e) {
            // A check that only connects, or the end of the test.
          }
        }
      });
      assertThat(CosmosSuggestions.probe(plain.getLocalPort())).isEqualTo(CosmosSuggestions.Tls.PLAIN);
      plain.close();
      server.join(2_000);
    }
    int closed;
    try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closed = s.getLocalPort();
    }
    assertThat(CosmosSuggestions.probe(closed)).isEqualTo(CosmosSuggestions.Tls.NONE);
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  private void account() {
    fake.on("GET /dbs", r -> FakeCosmos.json(200, "{\"_rid\": \"r==\", \"Databases\": [{\"id\": \"app\"}, "
        + "{\"id\": \"audit\"}, {\"id\": \"bad/name\"}], \"_count\": 3}"));
    fake.on("GET /dbs/app/colls", r -> FakeCosmos.json(200, "{\"DocumentCollections\": [{\"id\": \"orders\", "
        + "\"partitionKey\": {\"paths\": [\"/customerId\"], \"kind\": \"Hash\"}}, {\"id\": \"events\", "
        + "\"partitionKey\": {\"paths\": [\"/tenantId\", \"/userId\"], \"kind\": \"MultiHash\"}}]}"));
    fake.on("GET /dbs/audit/colls", r -> FakeCosmos.json(200, "{\"DocumentCollections\": [{\"id\": \"log\"}]}"));
  }

  @Test
  void signedInItListsTheDatabasesAndTheContainersOfTheAllowedOnes() {
    account();

    Suggestions s = cosmos.suggest(CosmosDbConnectorTest.config(Env.DEV, CosmosDbConnectorTest.KEY_RO,
        "\"databases\": [\"app\"]"), key, TestCtx.of(), Suggestions.Scope.SERVICE);

    Suggestions.Field databases = s.fields().get("/databases");
    assertThat(databases.source()).isEqualTo(CosmosSuggestions.ACCOUNT);
    assertThat(databases.options()).extracting(Option::value).containsExactly("app", "audit");
    assertThat(databases.complete()).isTrue();
    // The containers of the databases the connection allows, named as database/container.
    assertThat(s.fields().get("/containers").options()).extracting(Option::value)
        .containsExactly("app/orders", "app/events");
    assertThat(s.fields().get("/containers").options().get(1).detail()).isEqualTo("partition key /tenantId, /userId");
    assertThat(s.notes()).containsExactly("Signed in to acme.documents.azure.com: 2 databases. Choose the ones "
        + "agents may use; empty means all of them.");
    assertThat(fake.to("GET /dbs/audit")).isEmpty();
    assertThat(fake.refused).isEmpty();
    assertThat(fake.requests).extracting(RecordedRequest::getMethod).containsOnly("GET");
  }

  @Test
  void aPrincipalThatMayNotListTheAccountsDatabasesIsToldToNameThem() {
    fake.on("GET /dbs", r -> FakeCosmos.json(403, "{\"code\": \"Forbidden\", \"message\": \"Request blocked by Auth "
        + "acme : principal [" + FakeCosmos.OID + "] does not have required RBAC permissions.\"}", "x-ms-substatus",
        "5301"));
    fake.on("GET /dbs/app/colls", r -> FakeCosmos.json(200, "{\"DocumentCollections\": [{\"id\": \"orders\"}]}"));
    TestSecrets entra = new TestSecrets().with(CosmosSettings.CLIENT_SECRET, "entra-test-secret-0001");
    ConnectionConfig cfg = new ConnectionConfig("draft-2", "draft", "cosmosdb", Env.DEV, 1, CosmosDbConnectorTest
        .config(Env.DEV, CosmosDbConnectorTest.ENTRA, "\"databases\": [\"app\", \"audit-*\"]").config(),
        Set.of(CosmosSettings.CLIENT_SECRET), null);

    Suggestions s = cosmos.suggest(cfg, entra, TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("The credentials may not list the account's databases: name the ones "
        + "agents use under Databases, without *.");
    // The databases it names are looked at one by one.
    assertThat(s.fields().get("/containers").options()).extracting(Option::value).containsExactly("app/orders");
    assertThat(s.fields()).doesNotContainKey("/databases");
    // The draft's token went with its suggestions: the next one signs in again.
    cosmos.suggest(cfg, entra, TestCtx.of(), Suggestions.Scope.SERVICE);
    assertThat(fake.tokenRequests.get()).isEqualTo(2);
  }

  @Test
  void aRefusedSignInIsANote() {
    fake.tokenAnswer = r -> FakeCosmos.json(401, "{\"error\": \"invalid_client\", \"error_description\": "
        + "\"AADSTS7000215: Invalid client secret provided.\"}");

    Suggestions s = cosmos.suggest(CosmosDbConnectorTest.config(Env.DEV, CosmosDbConnectorTest.ENTRA, null),
        new TestSecrets().with(CosmosSettings.CLIENT_SECRET, "wrong-secret-0001"), TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).singleElement().asString().contains("AADSTS7000215").doesNotContain("wrong-secret");
  }
}
