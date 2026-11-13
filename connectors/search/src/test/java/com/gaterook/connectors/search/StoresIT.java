package com.gaterook.connectors.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The connectors against real servers of the search Compose profile (scripts/test-services.sh up search, then
 * {@code -Pservices,search-it}): Qdrant, Milvus, Weaviate, Chroma and Pinecone's last local image behind the vector_*
 * tools, Meilisearch and Typesense behind the search_* tools. Each test makes its own collection or index through the
 * store's own API, runs the tools as the core does — classify, prepare, the approved payload, execute — and drops it.
 */
@Tag("search-it")
class StoresIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  static void servers() {
    assumeTrue(TestServices.enabled(), "the Compose services (-Pservices)");
  }

  // ── Direct calls, to set up and clean up ────────────────────────────────────────────────────────────────────

  private static JsonNode http(String method, String url, String body, String... headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() / 100 != 2) {
      throw new IllegalStateException(method + " " + url + " → " + r.statusCode() + " " + r.body());
    }
    return r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
  }

  /** Waits until {@code url} answers 2xx: images without a shell tool have no health check of their own. */
  private static void ready(String url, String... headers) throws Exception {
    long until = System.currentTimeMillis() + 90_000;
    Exception last = null;
    while (System.currentTimeMillis() < until) {
      try {
        http("GET", url, null, headers);
        return;
      } catch (Exception e) {
        last = e;
        Thread.sleep(500);
      }
    }
    throw new IllegalStateException(url + " didn't answer", last);
  }

  private static <T> T eventually(Supplier<T> attempt, java.util.function.Predicate<T> done) throws Exception {
    long until = System.currentTimeMillis() + 20_000;
    T value = attempt.get();
    while (!done.test(value) && System.currentTimeMillis() < until) {
      Thread.sleep(300);
      value = attempt.get();
    }
    return value;
  }

  // ── The core's path through a connector ─────────────────────────────────────────────────────────────────────

  private static JsonNode read(Connector c, ConnectionConfig cfg, TestSecrets secrets, String tool, String args)
      throws ConnectorException {
    ToolCall call = TestCtx.call(tool, args);
    Classification cl = c.classify(call, cfg, TestCtx.policy(cfg.type()));
    assertThat(cl.kind()).as(tool + " " + cl.reason()).isEqualTo(Classification.Kind.READ);
    ToolResult r = c.execute(call, cfg, secrets, TestCtx.of());
    assertThat(r.isError()).as(tool).isFalse();
    return r.structuredContent();
  }

  private static JsonNode readQuietly(Connector c, ConnectionConfig cfg, TestSecrets secrets, String tool,
      String args) {
    try {
      return read(c, cfg, secrets, tool, args);
    } catch (ConnectorException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Classify, prepare (the approver's preview), then execute the approved payload. */
  private static JsonNode change(Connector c, ConnectionConfig cfg, TestSecrets secrets, String tool, String args,
      List<String> warnings) throws ConnectorException {
    ToolCall call = TestCtx.call(tool, args);
    Classification cl = c.classify(call, cfg, TestCtx.policy(cfg.type()));
    assertThat(cl.kind()).as(tool + " " + cl.reason()).isEqualTo(Classification.Kind.MUTATION);
    PreparedAction prepared = c.prepare(call, cfg, secrets, TestCtx.of());
    assertThat(prepared.outcome()).as(String.valueOf(prepared.message())).isEqualTo(PreparedAction.Outcome.APPROVAL);
    ActionPreview preview = prepared.preview();
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(preview.text()).doesNotContain("test-key-0001").doesNotContain("root:Milvus");
    if (warnings != null) {
      warnings.addAll(preview.warnings());
    }
    assertThatThrownBy(() -> c.execute(call, cfg, secrets, TestCtx.of())).hasMessageContaining("Not approved");
    ToolResult r = c.execute(call, cfg, secrets, TestCtx.of().approved(preview.payload()));
    assertThat(r.isError()).as(tool).isFalse();
    return r.structuredContent();
  }

  private static List<String> ids(JsonNode records) {
    List<String> out = new ArrayList<>();
    records.forEach(r -> out.add(r.path("id").asString(r.path("id").toString())));
    return out;
  }

  // ── Vector databases ────────────────────────────────────────────────────────────────────────────────────────

  /** The same journey on every vector database: three records, reads by id, filter, vector and id, a delete. */
  private static void journey(Connector c, ConnectionConfig cfg, TestSecrets secrets, String collection,
      List<String> idJson, boolean filterGet) throws Exception {
    TestResult t = c.test(cfg, secrets, TestCtx.of());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();

    String records = "[{\"id\": " + idJson.get(0) + ", \"vector\": [0.1, 0.2, 0.3], \"metadata\": {\"city\": "
        + "\"Berlin\", \"year\": 2020}}, {\"id\": " + idJson.get(1) + ", \"vector\": [0.3, 0.2, 0.1], \"metadata\": "
        + "{\"city\": \"London\", \"year\": 2024}}, {\"id\": " + idJson.get(2) + ", \"vector\": [0.2, 0.2, 0.2], "
        + "\"metadata\": {\"city\": \"Berlin\", \"year\": 2025}}]";
    JsonNode upserted = change(c, cfg, secrets, VectorTools.UPSERT, "{\"collection\": \"" + collection
        + "\", \"records\": " + records + ", \"note\": \"IT\"}", null);
    assertThat(upserted.path("upserted").asInt()).isEqualTo(3);

    JsonNode listed = read(c, cfg, secrets, VectorTools.LIST, "{}");
    assertThat(listed.path("collections").toString()).contains("\"" + collection + "\"");

    JsonNode byId = eventually(() -> readQuietly(c, cfg, secrets, VectorTools.GET, "{\"collection\": \""
        + collection + "\", \"ids\": [" + idJson.get(0) + "]}"), r -> r.path("records").size() == 1);
    assertThat(byId.path("records").get(0).path("metadata").path("city").asString()).isEqualTo("Berlin");
    assertThat(byId.path("records").get(0).has("vector")).isFalse();

    if (filterGet) {
      JsonNode berlin = eventually(() -> readQuietly(c, cfg, secrets, VectorTools.GET, "{\"collection\": \""
          + collection + "\", \"filter\": {\"city\": \"Berlin\"}, \"limit\": 10}"),
          r -> r.path("records").size() == 2);
      assertThat(berlin.path("records")).hasSize(2);
    }

    JsonNode near = eventually(() -> readQuietly(c, cfg, secrets, VectorTools.QUERY, "{\"collection\": \""
        + collection + "\", \"vector\": [0.1, 0.2, 0.3], \"topK\": 2, \"filter\": {\"year\": {\"$gte\": 2021}}}"),
        r -> r.path("matches").size() == 2);
    assertThat(ids(near.path("matches"))).hasSize(2).allSatisfy(id -> assertThat(id).isNotEqualTo(
        idJson.get(0).replace("\"", "")));
    assertThat(near.path("matches").get(0).path("score").isNumber()).isTrue();

    JsonNode similar = read(c, cfg, secrets, VectorTools.QUERY, "{\"collection\": \"" + collection + "\", \"id\": "
        + idJson.get(0) + ", \"topK\": 3, \"includeVectors\": true}");
    assertThat(similar.path("matches").size()).isGreaterThanOrEqualTo(2);
    assertThat(similar.path("matches").get(0).has("vector")).isTrue();

    JsonNode described = read(c, cfg, secrets, VectorTools.DESCRIBE, "{\"collection\": \"" + collection + "\"}");
    assertThat(described.path("name").asString()).isEqualTo(collection);

    List<String> warnings = new ArrayList<>();
    JsonNode deleted = change(c, cfg, secrets, VectorTools.DELETE, "{\"collection\": \"" + collection
        + "\", \"ids\": [" + idJson.get(2) + ", " + idJson.get(3) + "]}", warnings);
    assertThat(warnings).anySatisfy(w -> assertThat(w).contains("1 of the 2 ids don't exist"));
    assertThat(deleted.toString()).contains("\"collection\":\"" + collection + "\"");
    JsonNode gone = eventually(() -> readQuietly(c, cfg, secrets, VectorTools.GET, "{\"collection\": \""
        + collection + "\", \"ids\": [" + idJson.get(2) + "]}"), r -> r.path("missing").size() == 1);
    assertThat(gone.path("missing")).hasSize(1);
  }

  @Test
  void qdrant() throws Exception {
    String base = "http://127.0.0.1:" + TestServices.port("GW_QDRANT_PORT");
    String key = TestServices.value("GW_QDRANT_API_KEY");
    ready(base + "/collections", "api-key", key);
    String collection = "gr_it_" + UUID.randomUUID().toString().substring(0, 8);
    http("PUT", base + "/collections/" + collection, "{\"vectors\": {\"size\": 3, \"distance\": \"Cosine\"}}",
        "api-key", key);
    try {
      Connector c = Connectors.qdrant();
      ConnectionConfig cfg = TestCtx.config("qdrant", "{\"url\": \"" + base + "\", \"auth\": {\"method\": "
          + "\"apiKey\"}, \"collections\": [\"gr_it_*\"]}");
      TestSecrets secrets = new TestSecrets().with(QdrantBackend.API_KEY, key);
      journey(c, cfg, secrets, collection, List.of("1", "2", "3", "99"), true);

      TestSecrets reader = new TestSecrets().with(QdrantBackend.API_KEY, TestServices.value("GW_QDRANT_READ_KEY"));
      ToolCall upsert = TestCtx.call(VectorTools.UPSERT, "{\"collection\": \"" + collection + "\", \"records\": "
          + "[{\"id\": 7, \"vector\": [1, 0, 0]}]}");
      PreparedAction p = c.prepare(upsert, cfg, reader, TestCtx.of());
      assertThatThrownBy(() -> c.execute(upsert, cfg, reader, TestCtx.of().approved(p.preview().payload())))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("may not change data");
      assertThatThrownBy(() -> read(c, cfg, secrets, VectorTools.DESCRIBE, "{\"collection\": \"nope_x\"}"))
          .hasMessageContaining("isn't one of the collections");
    } finally {
      http("DELETE", base + "/collections/" + collection, null, "api-key", key);
    }
  }

  @Test
  void milvus() throws Exception {
    String base = "http://127.0.0.1:" + TestServices.port("GW_MILVUS_PORT");
    String root = "root:Milvus";
    eventually(() -> {
      try {
        return http("POST", base + "/v2/vectordb/collections/list", "{}", "Authorization", "Bearer " + root);
      } catch (Exception e) {
        return null;
      }
    }, r -> r != null && r.path("code").asInt(-1) == 0);
    String collection = "gr_it_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
    http("POST", base + "/v2/vectordb/collections/create", "{\"collectionName\": \"" + collection + "\", "
        + "\"dimension\": 3, \"metricType\": \"COSINE\", \"primaryFieldName\": \"id\", \"idType\": \"Int64\", "
        + "\"vectorFieldName\": \"vec\", \"autoId\": false}", "Authorization", "Bearer " + root);
    try {
      Connector c = Connectors.milvus();
      ConnectionConfig cfg = TestCtx.config("milvus", "{\"url\": \"" + base + "\", \"auth\": {\"method\": "
          + "\"password\", \"username\": \"root\"}}");
      TestSecrets secrets = new TestSecrets().with(MilvusBackend.PASSWORD, "Milvus");
      journey(c, cfg, secrets, collection, List.of("1", "2", "3", "99"), true);
      TestResult t = c.test(cfg, secrets, TestCtx.of());
      assertThat(t.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
      assertThat(t.identity()).isEqualTo("root");
    } finally {
      http("POST", base + "/v2/vectordb/collections/drop", "{\"collectionName\": \"" + collection + "\"}",
          "Authorization", "Bearer " + root);
    }
  }

  @Test
  void weaviate() throws Exception {
    String base = "http://127.0.0.1:" + TestServices.port("GW_WEAVIATE_PORT");
    String key = TestServices.value("GW_WEAVIATE_API_KEY");
    ready(base + "/v1/meta", "Authorization", "Bearer " + key);
    String collection = "GrIt" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
    http("POST", base + "/v1/schema", "{\"class\": \"" + collection + "\", \"vectorizer\": \"none\", "
        + "\"properties\": [{\"name\": \"city\", \"dataType\": [\"text\"]}, {\"name\": \"year\", \"dataType\": "
        + "[\"int\"]}]}", "Authorization", "Bearer " + key);
    try {
      Connector c = Connectors.weaviate();
      ConnectionConfig cfg = TestCtx.config("weaviate", "{\"url\": \"" + base + "\", \"auth\": {\"method\": "
          + "\"apiKey\"}}");
      TestSecrets secrets = new TestSecrets().with(WeaviateBackend.API_KEY, key);
      List<String> ids = List.of("\"00000000-0000-4000-8000-000000000001\"", "\"00000000-0000-4000-8000-000000000002\"",
          "\"00000000-0000-4000-8000-000000000003\"", "\"00000000-0000-4000-8000-000000000099\"");
      journey(c, cfg, secrets, collection, ids, true);
      JsonNode keyword = read(c, cfg, secrets, VectorTools.QUERY, "{\"collection\": \"" + collection
          + "\", \"text\": \"london\", \"textMode\": \"keyword\"}");
      assertThat(keyword.path("matches")).hasSize(1);
      assertThatThrownBy(() -> read(c, cfg, secrets, VectorTools.QUERY, "{\"collection\": \"" + collection
          + "\", \"text\": \"london\"}")).hasMessageContaining("no vectorizer");
      TestResult t = c.test(cfg, secrets, TestCtx.of());
      assertThat(t.identity()).isEqualTo("admin");
    } finally {
      http("DELETE", base + "/v1/schema/" + collection, null, "Authorization", "Bearer " + key);
    }
  }

  @Test
  void chroma() throws Exception {
    String base = "http://127.0.0.1:" + TestServices.port("GW_CHROMA_PORT");
    ready(base + "/api/v2/heartbeat");
    String collection = "gr-it-" + UUID.randomUUID().toString().substring(0, 8);
    String coll = base + "/api/v2/tenants/default_tenant/databases/default_database/collections";
    http("POST", coll, "{\"name\": \"" + collection + "\", \"configuration\": {\"hnsw\": {\"space\": \"cosine\"}}}");
    try {
      Connector c = Connectors.chroma();
      ConnectionConfig cfg = TestCtx.config("chroma", "{\"url\": \"" + base + "\", \"auth\": {\"method\": "
          + "\"none\"}}");
      TestSecrets secrets = new TestSecrets();
      journey(c, cfg, secrets, collection, List.of("\"a\"", "\"b\"", "\"c\"", "\"zz\""), true);
      assertThatThrownBy(() -> read(c, cfg, secrets, VectorTools.QUERY, "{\"collection\": \"" + collection
          + "\", \"text\": \"x\"}")).hasMessageContaining("searches by embedding");
    } finally {
      http("DELETE", coll + "/" + collection, null);
    }
  }

  @Test
  void pineconeLocal() throws Exception {
    int port = TestServices.port("GW_PINECONE_PORT");
    String base = "http://localhost:" + port;
    ready(base + "/indexes", "Api-Key", "local", "X-Pinecone-API-Version", PineconeBackend.API_VERSION);
    String index = "gr-it-" + UUID.randomUUID().toString().substring(0, 8);
    http("POST", base + "/indexes", "{\"name\": \"" + index + "\", \"dimension\": 3, \"metric\": \"cosine\", "
        + "\"spec\": {\"serverless\": {\"cloud\": \"aws\", \"region\": \"us-east-1\"}}}", "Api-Key", "local",
        "X-Pinecone-API-Version", PineconeBackend.API_VERSION);
    try {
      Connector c = new VectorConnector(new PineconeBackend(URI.create(base)));
      ConnectionConfig cfg = TestCtx.config("pinecone", "{\"collections\": [\"gr-it-*\"]}");
      TestSecrets secrets = new TestSecrets().with(PineconeBackend.API_KEY, "local-key-0001");
      // Pinecone's local image has no fetch by metadata: the filtered page says so.
      journey(c, cfg, secrets, index, List.of("\"a\"", "\"b\"", "\"c\"", "\"zz\""), false);
      assertThatThrownBy(() -> read(c, cfg, secrets, VectorTools.GET, "{\"collection\": \"" + index
          + "\", \"filter\": {\"city\": \"Berlin\"}}")).hasMessageContaining("vector_query with the filter");
      JsonNode page = read(c, cfg, secrets, VectorTools.GET, "{\"collection\": \"" + index + "\", \"limit\": 1}");
      assertThat(page.path("records")).hasSize(1);
      assertThat(page.path("nextCursor").isString()).isTrue();
    } finally {
      http("DELETE", base + "/indexes/" + index, null, "Api-Key", "local", "X-Pinecone-API-Version",
          PineconeBackend.API_VERSION);
    }
  }

  // ── Search engines ──────────────────────────────────────────────────────────────────────────────────────────

  private static void searchJourney(Connector c, ConnectionConfig cfg, TestSecrets secrets, String index)
      throws Exception {
    TestResult t = c.test(cfg, secrets, TestCtx.of());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    JsonNode indexed = change(c, cfg, secrets, SearchTools.INDEX, "{\"index\": \"" + index + "\", \"documents\": ["
        + "{\"id\": \"1\", \"title\": \"Carol\", \"genre\": \"drama\", \"year\": 2015}, {\"id\": \"2\", \"title\": "
        + "\"Wonder Woman\", \"genre\": \"action\", \"year\": 2017}, {\"id\": \"3\", \"title\": \"Life of Pi\", "
        + "\"genre\": \"drama\", \"year\": 2012}]}", null);
    assertThat(indexed.path("applied").asBoolean()).isTrue();
    JsonNode hits = eventually(() -> readQuietly(c, cfg, secrets, SearchTools.QUERY, "{\"index\": \"" + index
        + "\", \"filter\": {\"genre\": \"drama\", \"year\": {\"$gte\": 2010}}, \"facets\": [\"genre\"], \"sort\": "
        + "[\"year:desc\"]}"), r -> r.path("hits").size() == 2);
    assertThat(ids(hits.path("hits"))).containsExactly("1", "3");
    assertThat(hits.path("facets").path("genre").path("drama").asInt()).isEqualTo(2);
    JsonNode words = read(c, cfg, secrets, SearchTools.QUERY, "{\"index\": \"" + index + "\", \"query\": \"woman\"}");
    assertThat(ids(words.path("hits"))).containsExactly("2");
    JsonNode docs = read(c, cfg, secrets, SearchTools.GET, "{\"index\": \"" + index + "\", \"ids\": [\"1\", \"9\"]}");
    assertThat(docs.path("documents")).hasSize(1);
    assertThat(docs.path("missing").toString()).isEqualTo("[\"9\"]");
    List<String> warnings = new ArrayList<>();
    JsonNode merged = change(c, cfg, secrets, SearchTools.INDEX, "{\"index\": \"" + index + "\", \"mode\": \"merge\", "
        + "\"documents\": [{\"id\": \"2\", \"title\": \"Wonder Woman 1984\", \"genre\": \"action\", \"year\": 2020}]}",
        warnings);
    assertThat(merged.path("applied").asBoolean()).isTrue();
    assertThat(warnings).anySatisfy(w -> assertThat(w).contains("already exists and will be updated"));
    JsonNode deleted = change(c, cfg, secrets, SearchTools.DELETE, "{\"index\": \"" + index + "\", \"ids\": [\"3\"]}",
        null);
    assertThat(deleted.path("applied").asBoolean()).isTrue();
    JsonNode after = eventually(() -> readQuietly(c, cfg, secrets, SearchTools.GET, "{\"index\": \"" + index
        + "\", \"ids\": [\"2\", \"3\"]}"), r -> r.path("missing").size() == 1);
    assertThat(after.path("documents").get(0).path("title").asString()).isEqualTo("Wonder Woman 1984");
  }

  @Test
  void meilisearch() throws Exception {
    String base = "http://127.0.0.1:" + TestServices.port("GW_MEILISEARCH_PORT");
    String master = TestServices.value("GW_MEILISEARCH_MASTER_KEY");
    ready(base + "/health");
    String index = "gr_it_" + UUID.randomUUID().toString().substring(0, 8);
    JsonNode task = http("POST", base + "/indexes", "{\"uid\": \"" + index + "\", \"primaryKey\": \"id\"}",
        "Authorization", "Bearer " + master);
    JsonNode settings = http("PATCH", base + "/indexes/" + index + "/settings", "{\"filterableAttributes\": "
        + "[\"genre\", \"year\"], \"sortableAttributes\": [\"year\"]}", "Authorization", "Bearer " + master);
    eventually(() -> {
      try {
        return http("GET", base + "/tasks/" + settings.path("taskUid").asLong(), null, "Authorization", "Bearer "
            + master);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }, r -> "succeeded".equals(r.path("status").asString()));
    assertThat(task.path("taskUid").isNumber()).isTrue();
    try {
      Connector c = Connectors.meilisearch();
      ConnectionConfig cfg = TestCtx.config("meilisearch", "{\"url\": \"" + base + "\", \"auth\": {\"method\": "
          + "\"apiKey\"}}");
      TestSecrets secrets = new TestSecrets().with(MeilisearchBackend.API_KEY, master);
      searchJourney(c, cfg, secrets, index);

      // The Default Search API Key: it may search the listed index, not list indexes nor write.
      String search = null;
      for (JsonNode k : http("GET", base + "/keys", null, "Authorization", "Bearer " + master).path("results")) {
        if ("Default Search API Key".equals(k.path("name").asString())) {
          search = k.path("key").asString();
        }
      }
      ConnectionConfig listed = TestCtx.config("meilisearch", "{\"url\": \"" + base + "\", \"auth\": {\"method\": "
          + "\"apiKey\"}, \"indexes\": [\"" + index + "\"]}");
      TestSecrets reader = new TestSecrets().with(MeilisearchBackend.API_KEY, search);
      assertThat(c.test(listed, reader, TestCtx.of()).ok()).isTrue();
      assertThat(read(c, listed, reader, SearchTools.LIST, "{}").path("indexes").get(0).has("listedBy")).isTrue();
      assertThat(read(c, listed, reader, SearchTools.QUERY, "{\"index\": \"" + index + "\", \"query\": \"carol\"}")
          .path("hits")).hasSize(1);
      TestSecrets wrong = new TestSecrets().with(MeilisearchBackend.API_KEY, "not-a-key-0001");
      assertThat(c.test(listed, wrong, TestCtx.of()).ok()).isFalse();
    } finally {
      http("DELETE", base + "/indexes/" + index, null, "Authorization", "Bearer " + master);
    }
  }

  @Test
  void typesense() throws Exception {
    String base = "http://127.0.0.1:" + TestServices.port("GW_TYPESENSE_PORT");
    String key = TestServices.value("GW_TYPESENSE_API_KEY");
    ready(base + "/health");
    String index = "gr_it_" + UUID.randomUUID().toString().substring(0, 8);
    http("POST", base + "/collections", "{\"name\": \"" + index + "\", \"fields\": [{\"name\": \"title\", \"type\": "
        + "\"string\"}, {\"name\": \"genre\", \"type\": \"string\", \"facet\": true}, {\"name\": \"year\", \"type\": "
        + "\"int32\", \"facet\": true, \"sort\": true}], \"default_sorting_field\": \"year\"}", "X-TYPESENSE-API-KEY",
        key);
    try {
      Connector c = Connectors.typesense();
      ConnectionConfig cfg = TestCtx.config("typesense", "{\"url\": \"" + base + "\"}");
      TestSecrets secrets = new TestSecrets().with(TypesenseBackend.API_KEY, key);
      searchJourney(c, cfg, secrets, index);
      assertThat(c.test(cfg, secrets, TestCtx.of()).permissions().level()).isEqualTo(TestResult.Level.ADMIN);

      String scoped = http("POST", base + "/keys", "{\"description\": \"IT search\", \"actions\": "
          + "[\"documents:search\"], \"collections\": [\"" + index + "\"]}", "X-TYPESENSE-API-KEY", key)
          .path("value").asString();
      ConnectionConfig listed = TestCtx.config("typesense", "{\"url\": \"" + base + "\", \"indexes\": [\"" + index
          + "\"]}");
      TestSecrets reader = new TestSecrets().with(TypesenseBackend.API_KEY, scoped);
      // A new key is a new connection version: nothing read with the admin key carries over.
      Connector scopedConnector = Connectors.typesense();
      TestResult t = scopedConnector.test(listed, reader, TestCtx.of());
      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.permissions().level()).isEqualTo(TestResult.Level.UNKNOWN);
      assertThatThrownBy(() -> read(scopedConnector, listed, reader, SearchTools.QUERY, "{\"index\": \"" + index
          + "\", \"query\": \"carol\"}")).hasMessageContaining("searchFields");
      assertThat(read(scopedConnector, listed, reader, SearchTools.QUERY, "{\"index\": \"" + index + "\", \"query\": \"carol\", "
          + "\"searchFields\": [\"title\"]}").path("hits")).hasSize(1);
    } finally {
      http("DELETE", base + "/collections/" + index, null, "X-TYPESENSE-API-KEY", key);
    }
  }
}
