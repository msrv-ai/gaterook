package com.gaterook.connectors.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help for the forms of the vector databases and search engines: a server on its usual port of this computer offered
 * as its address, the Azure CLI's tenants, and, signed in, the collections or indexes the credentials see, Milvus's
 * databases and Chroma's tenant and databases, against the module's fakes.
 */
class StoreSuggestionsTest {

  private static final String TENANT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  private final List<CloudCredentials> clouds = new ArrayList<>();

  @TempDir
  Path home;

  @AfterEach
  void close() {
    clouds.forEach(CloudCredentials::close);
  }

  private static Suggestions service(Connector c, ConnectionConfig cfg, TestSecrets s) throws Exception {
    return c.suggest(cfg, s, TestCtx.of(), Suggestions.Scope.SERVICE);
  }

  private static List<String> values(Suggestions s, String pointer) {
    return s.fields().get(pointer).options().stream().map(Option::value).toList();
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aServerOnThisComputerIsOfferedAsItsAddressNeverFilledIn() throws IOException {
    try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Suggestions.Builder out = Suggestions.builder();
      StoreSuggestions.localUrl(out, "Qdrant", listening.getLocalPort());
      Suggestions.Field url = out.build().fields().get("/url");

      assertThat(url.value()).isNull();
      assertThat(url.source()).isEqualTo("this computer");
      assertThat(url.options()).singleElement().satisfies(o -> {
        assertThat(o.value()).isEqualTo("http://localhost:" + listening.getLocalPort());
        assertThat(o.detail()).isEqualTo("Qdrant's usual port " + listening.getLocalPort() + " answers here");
      });
    }
    int closed;
    try (ServerSocket gone = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closed = gone.getLocalPort();
    }
    Suggestions.Builder none = Suggestions.builder();
    StoreSuggestions.localUrl(none, "Qdrant", closed);
    assertThat(none.build().isEmpty()).isTrue();
  }

  @Test
  void localSuggestionsSendNothingToTheServer() throws Exception {
    try (FakeServer q = new FakeServer()) {
      q.on("GET /collections", 200, "{\"result\": {\"collections\": []}}");
      ConnectionConfig cfg = TestCtx.config("qdrant", "{\"url\": \"" + q.url() + "\", \"auth\": {\"method\": "
          + "\"apiKey\"}}");

      Suggestions s = Connectors.qdrant().suggest(cfg, new TestSecrets().with(QdrantBackend.API_KEY, "k-1"),
          TestCtx.of(), Suggestions.Scope.LOCAL);

      assertThat(s.notes()).isEmpty();
      assertThat(s.fields()).doesNotContainKey("/collections");
      assertThat(q.requests).isEmpty();
    }
  }

  @Test
  void theAlgoliaCliDefaultProfileFillsInTheApplicationId() throws Exception {
    Path dir = Files.createDirectories(home.resolve(".config").resolve("algolia"));
    Files.writeString(dir.resolve("config.toml"), """
        [staging]
          application_id = "STG0001XYZ"
          api_key = "cnry-algolia-staging-key"

        [prod]
          application_id = "ABCD1234EF"
          admin_api_key = "cnry-algolia-admin-key"
          search_hosts = ["abcd1234ef-dsn.algolia.net"]
          default = true
        """);
    Connector c = new SearchConnector(new AlgoliaBackend().machine(Map.<String, String>of()::get, home));

    Suggestions s = c.suggest(TestCtx.config("algolia", "{}"), new TestSecrets(), TestCtx.of(),
        Suggestions.Scope.LOCAL);

    Suggestions.Field app = s.fields().get("/applicationId");
    assertThat(app.value()).isEqualTo("ABCD1234EF");
    assertThat(app.source()).isEqualTo("~/.config/algolia/config.toml");
    assertThat(app.options()).extracting(Option::value, Option::detail).containsExactly(
        tuple("STG0001XYZ", "profile staging"), tuple("ABCD1234EF", "profile prod (default)"));
    assertThat(FakeServer.JSON.writeValueAsString(s)).doesNotContain("cnry-algolia");

    // XDG_CONFIG_HOME moves the folder; without the file there is nothing to offer.
    Connector moved = new SearchConnector(new AlgoliaBackend().machine(Map.of("XDG_CONFIG_HOME",
        home.resolve("elsewhere").toString())::get, home));
    assertThat(moved.suggest(TestCtx.config("algolia", "{}"), new TestSecrets(), TestCtx.of(),
        Suggestions.Scope.LOCAL).isEmpty()).isTrue();
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void qdrantOffersItsCollectionsAndNamesTheListedOnesItLacks() throws Exception {
    try (FakeServer q = new FakeServer()) {
      q.on("GET /collections", 200, "{\"result\": {\"collections\": [{\"name\": \"docs\"}, {\"name\": \"kb_eu\"}]}, "
          + "\"status\": \"ok\"}");
      ConnectionConfig cfg = TestCtx.config("qdrant", "{\"url\": \"" + q.url() + "\", \"auth\": {\"method\": "
          + "\"apiKey\"}, \"collections\": [\"kb_*\", \"dcos\"]}");

      Suggestions s = service(Connectors.qdrant(), cfg, new TestSecrets().with(QdrantBackend.API_KEY, "qk-1"));

      Suggestions.Field list = s.fields().get("/collections");
      assertThat(list.source()).isEqualTo("Qdrant");
      assertThat(list.value()).as("empty means every collection: nothing is filled in").isNull();
      assertThat(values(s, "/collections")).containsExactly("docs", "kb_eu");
      assertThat(s.notes()).containsExactly("Signed in to Qdrant at " + q.url().substring("http://".length())
          + ". 2 collections: choose the ones agents may use.", "Qdrant has no collection named dcos: check the "
          + "name.");
      assertThat(q.to("GET /collections").getFirst().getHeaders().get("api-key")).isEqualTo("qk-1");
    }
  }

  @Test
  void credentialsTheServerRefusesAreANoteNotAnError() throws Exception {
    try (FakeServer w = new FakeServer()) {
      w.on("GET /v1/schema", 401, "{\"error\": [{\"message\": \"unauthorized: invalid api key\"}]}");
      ConnectionConfig cfg = TestCtx.config("weaviate", "{\"url\": \"" + w.url() + "\", \"auth\": {\"method\": "
          + "\"apiKey\"}}");

      Suggestions s = service(Connectors.weaviate(), cfg, new TestSecrets().with(WeaviateBackend.API_KEY, "w-1"));

      assertThat(s.fields()).isEmpty();
      assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).startsWith("Weaviate refused the "
          + "credentials").contains("invalid api key"));
    }
  }

  @Test
  void milvusOffersItsDatabasesAndTheCollectionsOfTheOneTheConnectionUses() throws Exception {
    try (FakeServer m = new FakeServer()) {
      m.on("POST /v2/vectordb/databases/list", 200, "{\"code\": 0, \"data\": [\"default\", \"kb\"]}")
          .on("POST /v2/vectordb/collections/list", r -> FakeServer.json(200, FakeServer.jsonBody(r).path("dbName")
              .asString().equals("kb") ? "{\"code\": 0, \"data\": [\"articles\"]}"
              : "{\"code\": 0, \"data\": [\"items\", \"orders\"]}"));
      TestSecrets token = new TestSecrets().with(MilvusBackend.TOKEN, "user:pw");

      Suggestions s = service(Connectors.milvus(), TestCtx.config("milvus", "{\"url\": \"" + m.url() + "\", "
          + "\"database\": \"kb\", \"auth\": {\"method\": \"token\"}}"), token);

      assertThat(values(s, "/database")).containsExactly("default", "kb");
      assertThat(s.fields().get("/database").value()).as("two databases: none is obviously right").isNull();
      assertThat(values(s, "/collections")).containsExactly("articles");
      assertThat(s.notes()).contains("The collections offered are those of database kb (Milvus has 2 databases).");
      assertThat(m.to("POST /v2/vectordb/collections/list").getFirst().getHeaders().get("Authorization"))
          .isEqualTo("Bearer user:pw");

      // A server with one database: it is filled in, and its collections are offered whatever the field said.
      m.on("POST /v2/vectordb/databases/list", 200, "{\"code\": 0, \"data\": [\"kb\"]}");
      Suggestions one = service(Connectors.milvus(), TestCtx.config("milvus", "{\"url\": \"" + m.url() + "\", "
          + "\"auth\": {\"method\": \"token\"}}"), token);
      assertThat(one.fields().get("/database").value()).isEqualTo("kb");
      assertThat(values(one, "/collections")).containsExactly("articles");
    }
  }

  @Test
  void milvusWithoutADatabaseListStillOffersTheCollections() throws Exception {
    try (FakeServer m = new FakeServer()) {
      m.on("POST /v2/vectordb/databases/list", 200, "{\"code\": 1100, \"message\": \"not supported\"}")
          .on("POST /v2/vectordb/collections/list", 200, "{\"code\": 0, \"data\": [\"items\"]}");

      Suggestions s = service(Connectors.milvus(), TestCtx.config("milvus", "{\"url\": \"" + m.url() + "\", "
          + "\"auth\": {\"method\": \"none\"}}"), new TestSecrets());

      assertThat(s.fields()).doesNotContainKey("/database");
      assertThat(values(s, "/collections")).containsExactly("items");
    }
  }

  @Test
  void chromaFillsInTheTenantAndTheOnlyDatabaseOfTheCredentials() throws Exception {
    try (FakeServer ch = new FakeServer()) {
      ch.on("GET /api/v2/auth/identity", 200, "{\"user_id\": \"u-1\", \"tenant\": \"t-123\", \"databases\": "
              + "[\"prod\"]}")
          .on("GET /api/v2/tenants/t-123/databases/prod/collections", 200, "[{\"id\": "
              + "\"0d6a0c1e-3a4b-4c5d-8e9f-a0b1c2d3e4f5\", \"name\": \"notes\", \"dimension\": 384, "
              + "\"configuration_json\": {\"hnsw\": {\"space\": \"cosine\"}}}]");
      ConnectionConfig cfg = TestCtx.config("chroma", "{\"url\": \"" + ch.url() + "\", \"auth\": {\"method\": "
          + "\"token\"}}");

      Suggestions s = service(Connectors.chroma(), cfg, new TestSecrets().with(ChromaBackend.TOKEN, "ck-1"));

      assertThat(s.fields().get("/tenant").value()).isEqualTo("t-123");
      assertThat(s.fields().get("/database").value()).isEqualTo("prod");
      assertThat(s.fields().get("/collections").options()).singleElement().satisfies(o -> {
        assertThat(o.value()).isEqualTo("notes");
        assertThat(o.detail()).isEqualTo("384 dimensions, cosine");
      });
      assertThat(ch.to("GET /api/v2/tenants/default_tenant").isEmpty()).as("listed where it signs in").isTrue();
    }
  }

  @Test
  void chromaListsTheTenantsDatabasesWhenTheCredentialsMayUseAny() throws Exception {
    try (FakeServer ch = new FakeServer()) {
      ch.on("GET /api/v2/auth/identity", 200, "{\"user_id\": \"\", \"tenant\": \"default_tenant\", \"databases\": "
              + "[\"*\"]}")
          .on("GET /api/v2/tenants/default_tenant/databases", 200, "[{\"id\": \"x\", \"name\": "
              + "\"default_database\", \"tenant\": \"default_tenant\"}, {\"id\": \"y\", \"name\": \"archive\", "
              + "\"tenant\": \"default_tenant\"}]")
          .on("GET /api/v2/tenants/default_tenant/databases/default_database/collections", 200, "[]");

      Suggestions s = service(Connectors.chroma(), TestCtx.config("chroma", "{\"url\": \"" + ch.url() + "\", "
          + "\"auth\": {\"method\": \"none\"}}"), new TestSecrets());

      assertThat(values(s, "/database")).containsExactly("default_database", "archive");
      assertThat(s.fields().get("/database").value()).isNull();
      assertThat(s.notes()).anyMatch(n -> n.endsWith("No collections yet."));
    }
  }

  @Test
  void pineconeOffersItsIndexesWithTheirVectors() throws Exception {
    try (FakeServer p = new FakeServer()) {
      p.on("GET /indexes", 200, "{\"indexes\": [{\"name\": \"docs\", \"dimension\": 1536, \"metric\": \"cosine\", "
          + "\"status\": {\"ready\": true}}, {\"name\": \"new\", \"dimension\": 8, \"metric\": \"dotproduct\", "
          + "\"status\": {\"ready\": false}}]}");
      Connector c = new VectorConnector(new PineconeBackend(URI.create(p.url())));

      Suggestions s = service(c, TestCtx.config("pinecone", "{}"), new TestSecrets().with(PineconeBackend.API_KEY,
          "pk-1"));

      assertThat(s.fields().get("/collections").options()).extracting(Option::detail)
          .containsExactly("1536 dimensions, cosine", "8 dimensions, dotproduct, not ready");
      assertThat(s.notes()).containsExactly("Signed in to Pinecone. 2 indexes: choose the ones agents may use.");
    }
  }

  @Test
  void aKeyThatMayNotListIndexesOffersNoneAndSaysWhy() throws Exception {
    try (FakeServer ms = new FakeServer()) {
      ms.on("GET /indexes", 403, "{\"message\": \"The provided API key is invalid.\", \"code\": "
          + "\"invalid_api_key\"}");
      ConnectionConfig cfg = TestCtx.config("meilisearch", "{\"url\": \"" + ms.url() + "\", \"auth\": {\"method\": "
          + "\"apiKey\"}, \"indexes\": [\"movies\"]}");

      Suggestions s = service(Connectors.meilisearch(), cfg, new TestSecrets().with(MeilisearchBackend.API_KEY,
          "search-key"));

      assertThat(s.fields()).isEmpty();
      assertThat(s.notes()).containsExactly("These credentials may not list indexes, so none can be offered: name "
          + "the ones agents may use.");
    }
  }

  @Test
  void algoliaOffersItsIndicesWithTheirSizes() throws Exception {
    try (FakeServer a = new FakeServer()) {
      a.on("GET /1/indexes", 200, "{\"items\": [{\"name\": \"products\", \"entries\": 1200}, {\"name\": "
          + "\"products_price_asc\", \"entries\": 1200, \"primary\": \"products\"}], \"nbPages\": 1}");
      Connector c = new SearchConnector(new AlgoliaBackend(URI.create(a.url())));

      Suggestions s = service(c, TestCtx.config("algolia", "{\"applicationId\": \"ABCD1234EF\"}"),
          new TestSecrets().with(AlgoliaBackend.API_KEY, "algolia-key"));

      assertThat(s.fields().get("/indexes").options()).extracting(Option::detail)
          .containsExactly("1,200 documents", "1,200 documents, replica of products");
      assertThat(s.notes()).containsExactly("Signed in to Algolia application ABCD1234EF. 2 indices: choose the ones "
          + "agents may use.");
    }
  }

  @Test
  void typesenseNamesItsCollections() throws Exception {
    try (FakeServer t = new FakeServer()) {
      t.on("GET /collections", 200, "[{\"name\": \"books\", \"num_documents\": 1}]");

      Suggestions s = service(Connectors.typesense(), TestCtx.config("typesense", "{\"url\": \"" + t.url() + "\"}"),
          new TestSecrets().with(TypesenseBackend.API_KEY, "ts-1"));

      assertThat(s.fields().get("/indexes").options()).singleElement()
          .satisfies(o -> assertThat(o.detail()).isEqualTo("1 document"));
      assertThat(s.notes().getFirst()).endsWith(". 1 collection: books.");
    }
  }

  // ── Azure AI Search ─────────────────────────────────────────────────────────────────────────────────────────

  private AzureSearchBackend azure(FakeServer fake) throws IOException {
    CloudCredentials cloud = new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null,
        Map.of("azure.login", URI.create(fake.url()), "azure.search", URI.create(fake.url()))), Clock.systemUTC());
    clouds.add(cloud);
    Path azureDir = Files.createDirectories(home.resolve(".azure"));
    Files.writeString(azureDir.resolve("azureProfile.json"), "{\"subscriptions\": [{\"id\": "
        + "\"11111111-1111-1111-1111-111111111111\", \"name\": \"Search\", \"state\": \"Enabled\", \"isDefault\": "
        + "true, \"tenantId\": \"" + TENANT + "\", \"environmentName\": \"AzureCloud\"}]}");
    return new AzureSearchBackend(cloud, URI.create(fake.url()))
        .local(new CloudLocal(cloud.endpoints(), Map.<String, String>of()::get, home));
  }

  @Test
  void azureOffersTheCliTenantAndFillsItInOnlyWhereOneIsNeeded() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      Connector c = new SearchConnector(azure(fake));
      String base = "{\"endpoint\": \"https://contoso.search.windows.net\", \"auth\": \"entraId\", \"credentials\": ";

      Suggestions device = c.suggest(TestCtx.config("azure-ai-search", base + "{\"source\": \"deviceCode\"}}"),
          new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);
      Suggestions cli = c.suggest(TestCtx.config("azure-ai-search", base + "{\"source\": \"cli\"}}"),
          new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);
      Suggestions key = c.suggest(TestCtx.config("azure-ai-search", "{\"endpoint\": "
          + "\"https://contoso.search.windows.net\"}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);

      assertThat(device.fields().get("/tenantId").value()).isEqualTo(TENANT);
      assertThat(device.fields().get("/tenantId").source()).isEqualTo("the Azure CLI's sign-in");
      // Empty uses the Azure CLI's default tenant, and a tenant would change the command the person allowed.
      assertThat(cli.fields().get("/tenantId").value()).isNull();
      assertThat(values(cli, "/tenantId")).containsExactly(TENANT);
      assertThat(key.fields()).doesNotContainKey("/tenantId");
      assertThat(fake.requests).isEmpty();
    }
  }

  @Test
  void azureNeverRunsTheCliForASuggestionBeforeThePersonAllowedIt() throws Exception {
    Path az = home.resolve("az");
    Files.writeString(az, "#!/bin/sh\necho ran > " + home.resolve("ran") + "\n");
    az.toFile().setExecutable(true);
    try (FakeServer fake = new FakeServer()) {
      CloudCredentials cloud = new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null,
          Map.of("azure.login", URI.create(fake.url()), "azure.search", URI.create(fake.url())), az),
          Clock.systemUTC());
      clouds.add(cloud);
      Connector c = new SearchConnector(new AzureSearchBackend(cloud, URI.create(fake.url())));

      Suggestions s = service(c, TestCtx.config("azure-ai-search", "{\"endpoint\": "
          + "\"https://contoso.search.windows.net\", \"auth\": \"entraId\", \"credentials\": {\"source\": "
          + "\"cli\"}}"), new TestSecrets());

      assertThat(s.notes()).containsExactly("Indexes appear after Test connection: it asks you to allow the Azure "
          + "CLI command first.");
      assertThat(s.fields()).isEmpty();
      assertThat(home.resolve("ran")).doesNotExist();
      assertThat(fake.requests).isEmpty();
    }
  }

  @Test
  void azureWithoutADeviceSignInWaitsForTestConnection() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      Connector c = new SearchConnector(azure(fake));

      Suggestions s = service(c, TestCtx.config("azure-ai-search", "{\"endpoint\": "
          + "\"https://contoso.search.windows.net\", \"auth\": \"entraId\", \"tenantId\": \"" + TENANT + "\", "
          + "\"credentials\": {\"source\": \"deviceCode\"}}"), new TestSecrets());

      assertThat(s.notes()).containsExactly("Indexes appear after Test connection signs you in with a code.");
      assertThat(fake.requests).as("no device sign-in is started for a suggestion").isEmpty();
    }
  }

  @Test
  void azureOffersTheIndexesOfAnAdminKeyAndExplainsAQueryKey() throws Exception {
    try (FakeServer fake = new FakeServer()) {
      fake.on("GET /indexes", r -> "admin-key".equals(r.getHeaders().get("api-key"))
          ? FakeServer.json(200, "{\"value\": [{\"name\": \"hotels\"}]}") : FakeServer.json(403, "{\"error\": "
          + "{\"message\": \"Forbidden\"}}"));
      Connector c = new SearchConnector(azure(fake));

      Suggestions admin = service(c, TestCtx.config("azure-ai-search", "{\"endpoint\": "
          + "\"https://contoso.search.windows.net\"}"), new TestSecrets().with(AzureSearchBackend.API_KEY,
          "admin-key"));
      Suggestions query = service(c, TestCtx.config("azure-ai-search", "{\"endpoint\": "
          + "\"https://contoso.search.windows.net\", \"indexes\": [\"hotels\"]}"),
          new TestSecrets().with(AzureSearchBackend.API_KEY, "query-key"));

      assertThat(values(admin, "/indexes")).containsExactly("hotels");
      assertThat(admin.notes()).containsExactly("Signed in to contoso.search.windows.net. 1 index: hotels.");
      assertThat(query.fields()).isEmpty();
      assertThat(query.notes()).containsExactly("A query key may not list indexes, so none can be offered: name the "
          + "ones agents may use, or test an admin key to see them here.");
    }
  }
}
