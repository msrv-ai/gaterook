package com.gaterook.connectors.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Azure AI Search signed in with Microsoft Entra ID, against fakes of the token endpoint and of the search service,
 * which answers as role-based access does: the token's audience in each cloud, the bearer that replaces api-key, the
 * decisions of the Azure CLI and of a device sign-in, what Test connection learns of the identity's roles, and the
 * role each refusal names.
 */
class AzureSearchEntraTest {

  private static final String TENANT = "6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  private static final String CLIENT = "0a1b2c3d-1111-4222-8333-444455556666";
  private static final String SECRET = "cnry-search-client-secret-0001";
  private static final String READER = "Reader";
  private static final String DATA_READER = "Search Index Data Reader";
  private static final String DATA_CONTRIBUTOR = "Search Index Data Contributor";
  private static final String DEFINITION = "{\"name\": \"hotels\", \"fields\": [{\"name\": \"hotelId\", \"type\": "
      + "\"Edm.String\", \"key\": true}, {\"name\": \"name\", \"type\": \"Edm.String\", \"searchable\": true}]}";
  private static final String FORBIDDEN = "{\"error\": {\"code\": \"\", \"message\": \"Authorization failed.\"}}";

  private final List<CloudCredentials> clouds = new ArrayList<>();

  @AfterEach
  void close() {
    clouds.forEach(CloudCredentials::close);
  }

  // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aServicePrincipalsTokenForTheSearchAudienceReplacesTheKey() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      AtomicInteger issued = tokens(fake);
      service(fake, Set.of(READER, DATA_CONTRIBUTOR));
      Connector c = connector(fake);
      ConnectionConfig cfg = config(servicePrincipal("https://contoso.search.windows.net", ", \"indexes\": "
          + "[\"hotels\"]"));
      TestSecrets s = new TestSecrets().with("/credentials/clientSecret", SECRET);
      TestCtx ctx = TestCtx.of();

      TestResult t = c.test(cfg, s, ctx);

      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.identity()).isEqualTo(CLIENT);
      assertThat(t.permissions().level()).isEqualTo(TestResult.Level.UNKNOWN);
      assertThat(t.permissions().read()).isEqualTo("May search and read documents, list indexes and read index "
          + "definitions.");
      assertThat(t.permissions().write()).isEqualTo("Not checked: writing documents needs Search Index Data "
          + "Contributor. GateRook's writes wait for approval.");
      assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("sign-in", "index list",
          "search hotels", "definition of hotels");
      assertThat(t.checks()).allSatisfy(check -> assertThat(check.ok()).isTrue());
      assertThat(t.checks().getFirst().detail()).isEqualTo(CLIENT + " in tenant " + TENANT);
      assertThat(t.checks().get(2).detail()).isEqualTo("3 documents");
      assertThat(t.details().path("tenant").asString()).isEqualTo(TENANT);
      assertThat(t.details().path("application").asBoolean()).isTrue();
      assertThat(t.details().path("cloud").asString()).isEqualTo("AzureCloud");

      RecordedRequest tokenRequest = fake.to("POST /" + TENANT + "/oauth2/v2.0/token").getFirst();
      assertThat(FakeServer.form(tokenRequest)).containsEntry("grant_type", "client_credentials")
          .containsEntry("client_id", CLIENT).containsEntry("client_secret", SECRET)
          .containsEntry("scope", "https://search.azure.com/.default");
      RecordedRequest probe = fake.to("POST /indexes/hotels/docs/search").getFirst();
      assertThat(FakeServer.jsonBody(probe).path("top").asInt()).as("the probe reads no documents").isZero();
      assertThat(probe.getHeaders().get("api-key")).isNull();
      String bearer = probe.getHeaders().get("Authorization");
      assertThat(bearer).startsWith("Bearer ey");
      assertThat(ctx.taints).contains(bearer.substring("Bearer ".length()));
      assertThat(issued.get()).as("one token for every request of the test").isEqualTo(1);

      JsonNode hits = run(c, cfg, s, SearchTools.QUERY, "{\"index\": \"hotels\", \"query\": \"fancy\"}");
      assertThat(hits.path("hits").get(0).path("id").asString()).isEqualTo("1");
      assertThat(fake.to("POST /indexes/hotels/docs/search").getLast().getHeaders().get("Authorization"))
          .isEqualTo(bearer);

      ToolCall write = TestCtx.call(SearchTools.INDEX, "{\"index\": \"hotels\", \"documents\": [{\"hotelId\": \"1\", "
          + "\"name\": \"Fancy\"}]}");
      PreparedAction p = c.prepare(write, cfg, s, TestCtx.of());
      assertThat(p.outcome()).as(String.valueOf(p.message())).isEqualTo(PreparedAction.Outcome.APPROVAL);
      assertThat(p.preview().text()).contains("Authorization: Bearer ‹secret›").doesNotContain("api-key")
          .doesNotContain(bearer.substring("Bearer ".length()));
      JsonNode written = c.execute(write, cfg, s, TestCtx.of().approved(p.preview().payload())).structuredContent();
      assertThat(written.path("indexed").asInt()).isEqualTo(1);
      assertThat(fake.to("POST /indexes/hotels/docs/index").getLast().getHeaders().get("Authorization"))
          .isEqualTo(bearer);
      assertThat(FakeServer.body(fake.to("POST /indexes/hotels/docs/index").getLast())).doesNotContain(SECRET);
    }
  }

  @Test
  void theEndpointsSuffixPicksTheCloudAndTheTokenGoesNowhereElse() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      tokens(fake);
      service(fake, Set.of(READER, DATA_READER));
      ConnectionConfig gov = config(servicePrincipal("https://contoso.search.azure.us", ""));
      TestSecrets s = new TestSecrets().with("/credentials/clientSecret", SECRET);

      TestResult t = connector(fake).test(gov, s, TestCtx.of());

      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(FakeServer.form(fake.to("POST /" + TENANT).getFirst()))
          .containsEntry("scope", "https://search.azure.us/.default");
      assertThat(t.details().path("cloud").asString()).isEqualTo("AzureUSGovernment");

      // A test sign-in's tokens go only to a test search service; this one names none.
      CloudCredentials signInOnly = new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null,
          Map.of("azure.login", URI.create(fake.url()))), Clock.systemUTC());
      clouds.add(signInOnly);
      Connector elsewhere = new SearchConnector(new AzureSearchBackend(signInOnly, URI.create(fake.url())));
      int before = fake.to("GET /indexes").size();

      TestResult refused = elsewhere.test(gov, s, TestCtx.of());

      assertThat(refused.ok()).isFalse();
      assertThat(refused.error().message()).isEqualTo("GateRook sends Entra ID tokens only to Azure AI Search "
          + "services of AzureUSGovernment, not to 127.0.0.1.");
      assertThat(fake.to("GET /indexes")).hasSize(before);
    }
  }

  @Test
  void aRefusedTokenIsDroppedAndTheTestSaysToAllowRoleBasedAccess() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      AtomicInteger issued = tokens(fake);
      fake.on("GET /indexes", 401, "").on("POST /indexes", 401, "");
      Connector c = connector(fake);
      ConnectionConfig cfg = config(servicePrincipal("https://contoso.search.windows.net", ", \"indexes\": "
          + "[\"hotels\"]"));
      TestSecrets s = new TestSecrets().with("/credentials/clientSecret", SECRET);

      TestResult t = c.test(cfg, s, TestCtx.of());

      assertThat(t.ok()).isFalse();
      assertThat(t.error().code()).isEqualTo("search.denied");
      assertThat(t.error().message()).isEqualTo("Azure AI Search refused Azure AI Search's index list (401). "
          + AzureSearchBackend.TOKEN_REFUSED);
      assertThat(t.checks()).extracting(TestResult.Check::ok).containsExactly(true, false);
      assertThat(issued.get()).isEqualTo(1);

      ToolCall query = TestCtx.call(SearchTools.QUERY, "{\"index\": \"hotels\"}");
      assertThatThrownBy(() -> c.execute(query, cfg, s, TestCtx.of()))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
            assertThat(e.getMessage()).contains("Role-based access control");
          });
      assertThat(issued.get()).as("the token the service refused was dropped").isEqualTo(2);
    }
  }

  // ── Roles ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aDataReaderSearchesTheNamedIndexesAndMayNotWrite() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      tokens(fake);
      service(fake, Set.of(DATA_READER));
      Connector c = connector(fake);
      ConnectionConfig cfg = config(servicePrincipal("https://contoso.search.windows.net", ", \"indexes\": "
          + "[\"hotels\"]"));
      TestSecrets s = new TestSecrets().with("/credentials/clientSecret", SECRET);

      TestResult t = c.test(cfg, s, TestCtx.of());

      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.permissions().read()).isEqualTo("May search and read documents.");
      assertThat(t.permissions().write()).isEqualTo("Not checked: writing documents needs Search Index Data "
          + "Contributor, and the index's definition (Reader or Search Service Contributor) for its key field. "
          + "GateRook's writes wait for approval.");
      assertThat(t.checks().get(1).detail()).isEqualTo("not allowed (Reader or Search Service Contributor may list "
          + "them): the connection names hotels");
      assertThat(t.checks().get(3).detail()).isEqualTo("not allowed (Reader or Search Service Contributor may read "
          + "it)");
      assertThat(t.hints()).extracting(TestResult.Hint::code).contains("azure-ai-search.no-definitions");

      JsonNode list = run(c, cfg, s, SearchTools.LIST, "{}");
      assertThat(list.path("indexes").get(0).path("listedBy").asString())
          .isEqualTo("the connection: the identity may not list indexes");
      JsonNode described = run(c, cfg, s, SearchTools.DESCRIBE, "{\"index\": \"hotels\"}");
      assertThat(described.path("definition").asString()).isEqualTo("Not readable by this identity: reading index "
          + "definitions needs Reader or Search Service Contributor.");
      assertThat(described.path("documents").asLong()).isEqualTo(3);
      JsonNode docs = run(c, cfg, s, SearchTools.GET, "{\"index\": \"hotels\", \"ids\": [\"1\", \"9\"]}");
      assertThat(docs.path("documents")).hasSize(1);
      assertThat(docs.path("missing").toString()).isEqualTo("[\"9\"]");

      PreparedAction refused = c.prepare(TestCtx.call(SearchTools.INDEX, "{\"index\": \"hotels\", \"documents\": "
          + "[{\"hotelId\": \"1\"}]}"), cfg, s, TestCtx.of());
      assertThat(refused.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
      assertThat(refused.message()).startsWith("Azure AI Search refused Azure AI Search's definition of hotels (403): "
          + "a write needs the index's key field").contains("Assign it Reader or Search Service Contributor")
          .contains("besides Search Index Data Contributor to write");
      assertThat(fake.to("POST /indexes/hotels/docs/index")).isEmpty();
    }
  }

  @Test
  void aWriteWithoutTheContributorRoleNamesIt() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      tokens(fake);
      service(fake, Set.of(READER, DATA_READER));
      Connector c = connector(fake);
      ConnectionConfig cfg = config(servicePrincipal("https://contoso.search.windows.net", ""));
      TestSecrets s = new TestSecrets().with("/credentials/clientSecret", SECRET);
      ToolCall delete = TestCtx.call(SearchTools.DELETE, "{\"index\": \"hotels\", \"ids\": [\"1\"]}");

      PreparedAction p = c.prepare(delete, cfg, s, TestCtx.of());
      assertThat(p.outcome()).as(String.valueOf(p.message())).isEqualTo(PreparedAction.Outcome.APPROVAL);

      assertThatThrownBy(() -> c.execute(delete, cfg, s, TestCtx.of().approved(p.preview().payload())))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
            assertThat(e.getMessage()).isEqualTo("Azure AI Search refused Azure AI Search's batch on hotels (403): "
                + "Authorization failed. Writing documents needs Search Index Data Contributor on the search "
                + "service. A new role assignment can take up to 10 minutes to apply. Nothing changed.");
          });
    }
  }

  @Test
  void anIdentityThatMayNotSearchFailsTheTestWithTheRoleItNeeds() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      tokens(fake);
      service(fake, Set.of(READER));
      ConnectionConfig cfg = config(servicePrincipal("https://contoso.search.windows.net", ""));

      TestResult t = connector(fake).test(cfg, new TestSecrets().with("/credentials/clientSecret", SECRET),
          TestCtx.of());

      assertThat(t.ok()).isFalse();
      assertThat(t.error().message()).isEqualTo("Azure AI Search refused Azure AI Search's search of hotels (403): "
          + "Authorization failed. Searching and reading documents needs Search Index Data Reader (or Search Index "
          + "Data Contributor) on the search service. A new role assignment can take up to 10 minutes to apply.");
      assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("sign-in", "index list",
          "search hotels");
    }
  }

  @Test
  void withoutNamesAnIdentityThatMayNotListIndexesIsToldWhichRoleMay() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      tokens(fake);
      service(fake, Set.of(DATA_READER));
      ConnectionConfig cfg = config(servicePrincipal("https://contoso.search.windows.net", ""));

      TestResult t = connector(fake).test(cfg, new TestSecrets().with("/credentials/clientSecret", SECRET),
          TestCtx.of());

      assertThat(t.ok()).isFalse();
      assertThat(t.error().message()).isEqualTo("Azure AI Search refused Azure AI Search's index list (403): the "
          + "identity may not list indexes. Name the indexes agents may use under Indexes, or assign it Reader or "
          + "Search Service Contributor on the search service.");
    }
  }

  @Test
  void theRoleARefusalNamesFollowsTheRequestNotTheIndexName() {
    URI base = URI.create("https://contoso.search.windows.net");
    ServiceHttp.Request definition = ServiceHttp.Request.get(URI.create(base + "/indexes/docs-archive?api-version=x"),
        null, "d");
    ServiceHttp.Request lookup = ServiceHttp.Request.get(URI.create(base + "/indexes/docs/docs/index"), null, "l");
    ServiceHttp.Request batch = ServiceHttp.Request.json("POST", URI.create(base + "/indexes/docs/docs/index"), null,
        FakeServer.JSON.createObjectNode(), "b", true);

    assertThat(AzureSearchBackend.roleNeeded(definition)).contains("Reader or Search Service Contributor");
    assertThat(AzureSearchBackend.roleNeeded(lookup)).contains("needs Search Index Data Reader");
    assertThat(AzureSearchBackend.roleNeeded(batch)).contains("needs Search Index Data Contributor");
  }

  // ── Sign-ins that wait for the person ───────────────────────────────────────────────────────────────────────

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void theAzureCliRunsOnlyAfterThePersonAllowsItsCommand() throws Exception {
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "az-");
    Path args = dir.resolve("args.txt");
    Path az = dir.resolve("az");
    Files.writeString(az, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + args + "'\nprintf '{\"accessToken\":\""
        + jwt(Map.of("tid", TENANT, "upn", "me@contoso.example")) + "\",\"expires_on\":"
        + Instant.now().plusSeconds(3000).getEpochSecond() + ",\"tenant\":\"" + TENANT + "\"}'\n");
    Files.setPosixFilePermissions(az, PosixFilePermissions.fromString("rwx------"));
    try (FakeServer fake = new FakeServer()) {
      service(fake, Set.of(DATA_READER, READER));
      CloudCredentials cloud = new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null,
          Map.of("azure.login", URI.create(fake.url()), "azure.search", URI.create(fake.url())), az),
          Clock.systemUTC());
      clouds.add(cloud);
      Connector c = new SearchConnector(new AzureSearchBackend(cloud, URI.create(fake.url())));
      String base = "{\"endpoint\": \"https://contoso.search.windows.net\", \"auth\": \"entraId\", \"credentials\": "
          + "{\"source\": \"cli\"}";

      TestResult asked = c.test(config(base + "}"), new TestSecrets(), TestCtx.of());

      assertThat(asked.ok()).isFalse();
      assertThat(asked.needsConfirmation().kind()).isEqualTo("azure-cli");
      assertThat(asked.needsConfirmation().text()).contains("account get-access-token --output json --resource "
          + "<resource>");
      String fingerprint = asked.needsConfirmation().configPatch().path("trustedCli").asString();
      assertThat(fingerprint).matches("[0-9a-f]{64}");
      assertThat(args).as("nothing ran before the person allowed it").doesNotExist();
      assertThat(fake.requests).isEmpty();

      TestResult t = c.test(config(base + ", \"trustedCli\": \"" + fingerprint + "\"}"), new TestSecrets(),
          TestCtx.of());

      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.identity()).isEqualTo("me@contoso.example");
      assertThat(Files.readAllLines(args)).containsExactly("account", "get-access-token", "--output", "json",
          "--resource", "https://search.azure.com");
      assertThat(t.hints()).extracting(TestResult.Hint::code).contains("azure-ai-search.cli");
    }
  }

  @Test
  void aDeviceSignInShowsItsCodeBeforeAnythingElse() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      fake.on("POST /" + TENANT + "/oauth2/v2.0/devicecode", 200, "{\"device_code\": \"dev-code-1\", \"user_code\": "
          + "\"ABCD-EFGH\", \"verification_uri\": \"https://microsoft.com/devicelogin\", \"expires_in\": 900}")
          .on("POST /" + TENANT + "/oauth2/v2.0/token", r -> FakeServer.form(r).get("grant_type").startsWith("urn:")
              ? FakeServer.json(200, "{\"access_token\": \"" + jwt(Map.of("tid", TENANT, "upn", "dev@contoso.example"))
                  + "\", \"refresh_token\": \"rt-1\", \"expires_in\": 3600, \"id_token\": \""
                  + jwt(Map.of("preferred_username", "dev@contoso.example")) + "\"}")
              : FakeServer.json(200, "{\"access_token\": \"" + jwt(Map.of("tid", TENANT, "upn", "dev@contoso.example",
                  "scp", "user_impersonation")) + "\", \"refresh_token\": \"rt-2\", \"expires_in\": 3600}"));
      service(fake, Set.of(DATA_READER, READER));
      Connector c = connector(fake);
      ConnectionConfig cfg = config("{\"endpoint\": \"https://contoso.search.windows.net\", \"auth\": \"entraId\", "
          + "\"tenantId\": \"" + TENANT + "\", \"credentials\": {\"source\": \"deviceCode\"}}");

      TestResult asked = c.test(cfg, new TestSecrets(), TestCtx.of());

      assertThat(asked.needsConfirmation().kind()).isEqualTo("azure-sign-in");
      assertThat(asked.needsConfirmation().data().path("userCode").asString()).isEqualTo("ABCD-EFGH");
      assertThat(fake.to("GET /indexes")).isEmpty();

      TestResult t = c.test(cfg, new TestSecrets(), TestCtx.of());

      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.identity()).isEqualTo("dev@contoso.example");
      assertThat(fake.to("POST /" + TENANT + "/oauth2/v2.0/token")).extracting(r -> FakeServer.form(r).get("scope"))
          .contains("https://search.azure.com/.default offline_access");
      assertThat(t.hints()).extracting(TestResult.Hint::code).contains("azure-ai-search.device-sign-in");
    }
  }

  // ── Settings ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void incompleteSignInSettingsAreRefusedBeforeAnyRequest() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      Connector c = connector(fake);
      ConnectionConfig none = config("{\"endpoint\": \"https://contoso.search.windows.net\", \"auth\": \"entraId\"}");
      ConnectionConfig noClient = config("{\"endpoint\": \"https://contoso.search.windows.net\", \"auth\": "
          + "\"entraId\", \"tenantId\": \"" + TENANT + "\", \"credentials\": {\"source\": \"clientSecret\"}}");
      ToolCall list = TestCtx.call(SearchTools.LIST, "{}");

      Classification denied = c.classify(list, none, TestCtx.policy("azure-ai-search"));
      assertThat(denied.kind()).isEqualTo(Classification.Kind.DENY);
      assertThat(denied.reason()).isEqualTo("Choose how to sign in with Microsoft Entra ID under Credentials.");
      assertThat(c.classify(list, noClient, TestCtx.policy("azure-ai-search")).reason())
          .isEqualTo("A service principal needs its application (client) ID.");
      TestResult t = c.test(noClient, new TestSecrets(), TestCtx.of());
      assertThat(t.error().code()).isEqualTo("azure-ai-search.settings");
      assertThat(fake.requests).isEmpty();
    }
  }

  @Test
  void theKeyStaysTheDefault() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      fake.on("GET /indexes", 200, "{\"value\": [{\"name\": \"hotels\"}]}");
      Connector c = connector(fake);
      ConnectionConfig keyed = config("{\"endpoint\": \"https://contoso.search.windows.net\", \"auth\": \"apiKey\"}");

      TestResult t = c.test(keyed, new TestSecrets().with(AzureSearchBackend.API_KEY, "admin-key-0001"),
          TestCtx.of());

      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.identity()).isEqualTo("admin key");
      assertThat(fake.requests).allSatisfy(r -> {
        assertThat(r.getHeaders().get("api-key")).isEqualTo("admin-key-0001");
        assertThat(r.getHeaders().get("Authorization")).isNull();
      });
    }
  }

  // ── Fakes ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** The token endpoint of {@link #TENANT}: an application's token for each request, counted. */
  private static AtomicInteger tokens(FakeServer fake) {
    AtomicInteger issued = new AtomicInteger();
    fake.on("POST /" + TENANT + "/oauth2/v2.0/token", r -> FakeServer.json(200, "{\"token_type\": \"Bearer\", "
        + "\"expires_in\": 3599, \"access_token\": \"" + jwt(Map.of("tid", TENANT, "appid", CLIENT, "idtyp", "app",
            "n", Integer.toString(issued.incrementAndGet()))) + "\"}"));
    return issued;
  }

  /**
   * The index hotels of a service with role-based access, for an identity with {@code roles}: Reader lists indexes
   * and reads definitions, Search Index Data Reader searches and reads documents, Search Index Data Contributor also
   * writes them. A request without a bearer token is refused with 401, one the roles don't allow with 403.
   */
  private static void service(FakeServer fake, Set<String> roles) {
    boolean reader = roles.contains(READER);
    boolean data = roles.contains(DATA_READER) || roles.contains(DATA_CONTRIBUTOR);
    boolean writer = roles.contains(DATA_CONTRIBUTOR);
    fake.on("GET /indexes", r -> answer(r, reader, "{\"value\": [{\"name\": \"hotels\"}, {\"name\": \"payroll\"}]}"))
        .on("GET /indexes/hotels", r -> answer(r, reader, DEFINITION))
        .on("GET /indexes/hotels/stats", r -> answer(r, reader, "{\"documentCount\": 3, \"storageSize\": 900}"))
        .on("GET /indexes/hotels/docs/$count", r -> data ? FakeServer.text(200, "3") : FakeServer.json(403, FORBIDDEN))
        .on("GET /indexes/hotels/docs/1", r -> answer(r, data, "{\"hotelId\": \"1\", \"name\": \"Fancy\"}"))
        .on("GET /indexes/hotels/docs/9", r -> data ? FakeServer.json(404, "") : FakeServer.json(403, FORBIDDEN))
        .on("POST /indexes/hotels/docs/search", r -> answer(r, data, "{\"@odata.count\": 3, \"value\": "
            + "[{\"@search.score\": 1.5, \"hotelId\": \"1\", \"name\": \"Fancy\"}]}"))
        .on("POST /indexes/hotels/docs/index", r -> answer(r, writer, "{\"value\": [{\"key\": \"1\", \"status\": "
            + "true, \"statusCode\": 200}]}"));
  }

  private static MockResponse answer(RecordedRequest r, boolean allowed, String body) {
    String bearer = r.getHeaders().get("Authorization");
    if (bearer == null || !bearer.startsWith("Bearer ") || r.getHeaders().get("api-key") != null) {
      return FakeServer.json(401, "");
    }
    return allowed ? FakeServer.json(200, body) : FakeServer.json(403, FORBIDDEN);
  }

  /** A connector whose sign-in and search service are {@code fake}. */
  private Connector connector(FakeServer fake) {
    CloudCredentials cloud = new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null,
        Map.of("azure.login", URI.create(fake.url()), "azure.search", URI.create(fake.url()))), Clock.systemUTC());
    clouds.add(cloud);
    return new SearchConnector(new AzureSearchBackend(cloud, URI.create(fake.url())));
  }

  private static ConnectionConfig config(String json) {
    return TestCtx.config("azure-ai-search", json);
  }

  private static String servicePrincipal(String endpoint, String more) {
    return "{\"endpoint\": \"" + endpoint + "\", \"auth\": \"entraId\", \"tenantId\": \"" + TENANT + "\", "
        + "\"credentials\": {\"source\": \"clientSecret\", \"clientId\": \"" + CLIENT + "\"}" + more + "}";
  }

  private static JsonNode run(Connector c, ConnectionConfig cfg, TestSecrets s, String tool, String args)
      throws Exception {
    ToolCall call = TestCtx.call(tool, args);
    assertThat(c.classify(call, cfg, TestCtx.policy(cfg.type())).kind()).isEqualTo(Classification.Kind.READ);
    return c.execute(call, cfg, s, TestCtx.of()).structuredContent();
  }

  /** An unsigned JWT with {@code claims}: GateRook reads Entra ID's tokens, the service checks them. */
  static String jwt(Map<String, String> claims) {
    ObjectNode c = FakeServer.JSON.createObjectNode();
    claims.forEach(c::put);
    c.put("exp", Instant.now().plusSeconds(3600).getEpochSecond());
    Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
    return e.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + e.encodeToString(FakeServer.JSON.writeValueAsBytes(c)) + ".c2lnbmF0dXJl";
  }
}
