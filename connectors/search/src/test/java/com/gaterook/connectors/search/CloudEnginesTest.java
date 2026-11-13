package com.gaterook.connectors.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import java.net.URI;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Algolia and Azure AI Search, which run only in their clouds, against fakes of their documented REST APIs: the exact
 * requests GateRook sends (hosts, headers, bodies, filters) and how it reads the answers, keys of each kind included.
 */
class CloudEnginesTest {

  private static JsonNode run(Connector c, ConnectionConfig cfg, TestSecrets s, String tool, String args)
      throws Exception {
    ToolCall call = TestCtx.call(tool, args);
    assertThat(c.classify(call, cfg, TestCtx.policy(cfg.type())).kind()).isEqualTo(Classification.Kind.READ);
    return c.execute(call, cfg, s, TestCtx.of()).structuredContent();
  }

  private static JsonNode approve(Connector c, ConnectionConfig cfg, TestSecrets s, String tool, String args)
      throws Exception {
    ToolCall call = TestCtx.call(tool, args);
    assertThat(c.classify(call, cfg, TestCtx.policy(cfg.type())).kind()).isEqualTo(Classification.Kind.MUTATION);
    PreparedAction p = c.prepare(call, cfg, s, TestCtx.of());
    assertThat(p.outcome()).as(String.valueOf(p.message())).isEqualTo(PreparedAction.Outcome.APPROVAL);
    return c.execute(call, cfg, s, TestCtx.of().approved(p.preview().payload())).structuredContent();
  }

  @Test
  void algoliaSearchesWithItsFiltersAndWritesThroughTasks() throws Exception {
    try (FakeServer algolia = new FakeServer()) {
      algolia.on("GET /1/indexes", 200, "{\"items\": [{\"name\": \"products\", \"entries\": 3, \"updatedAt\": "
              + "\"2026-11-01T00:00:00Z\", \"replicas\": [\"products_price_asc\"]}, {\"name\": \"payroll\", "
              + "\"entries\": 9}], \"nbPages\": 1}")
          .on("GET /1/keys/", 200, "{\"value\": \"x\", \"acl\": [\"search\", \"browse\", \"listIndexes\"]}")
          .on("POST /1/indexes/products/query", 200, "{\"hits\": [{\"objectID\": \"sku-1\", \"name\": \"Shoe\", "
              + "\"_highlightResult\": {\"name\": {}}}], \"nbHits\": 41, \"exhaustive\": {\"nbHits\": true}, "
              + "\"facets\": {\"brand\": {\"Acme\": 12}}}")
          .on("POST /1/indexes/*/objects", 200, "{\"results\": [{\"objectID\": \"sku-1\", \"name\": \"Shoe\"}, null]}")
          .on("POST /1/indexes/products/batch", 200, "{\"taskID\": 77, \"objectIDs\": [\"sku-1\"]}")
          .on("GET /1/indexes/products/task/77", 200, "{\"status\": \"published\"}");
      Connector c = new SearchConnector(new AlgoliaBackend(URI.create(algolia.url())));
      ConnectionConfig cfg = TestCtx.config("algolia", "{\"applicationId\": \"ABCD1234EF\", \"indexes\": "
          + "[\"products*\"]}");
      TestSecrets s = new TestSecrets().with(AlgoliaBackend.API_KEY, "algolia-search-key-0001");

      TestResult t = c.test(cfg, s, TestCtx.of());
      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.permissions().level()).isEqualTo(TestResult.Level.READ);
      RecordedRequest list = algolia.to("GET /1/indexes").getFirst();
      assertThat(list.getHeaders().get("X-Algolia-Application-Id")).isEqualTo("ABCD1234EF");
      assertThat(list.getHeaders().get("X-Algolia-API-Key")).isEqualTo("algolia-search-key-0001");

      JsonNode indexes = run(c, cfg, s, SearchTools.LIST, "{}");
      assertThat(indexes.path("indexes")).hasSize(1);
      assertThat(indexes.path("notListed").asInt()).isEqualTo(1);

      JsonNode hits = run(c, cfg, s, SearchTools.QUERY, "{\"index\": \"products\", \"query\": \"shoe\", \"filter\": "
          + "{\"brand\": \"Acme \\\"Pro\\\"\", \"price\": {\"$lt\": 100}, \"color\": {\"$in\": [\"red\", \"blue\"]}}, "
          + "\"facets\": [\"brand\"], \"limit\": 1, \"offset\": 10}");
      JsonNode body = FakeServer.jsonBody(algolia.to("POST /1/indexes/products/query").getLast());
      assertThat(body.path("filters").asString()).isEqualTo("brand:\"Acme \\\"Pro\\\"\" AND price < 100 AND "
          + "(color:\"red\" OR color:\"blue\")");
      assertThat(body.path("offset").asInt()).isEqualTo(10);
      assertThat(body.path("length").asInt()).isEqualTo(1);
      assertThat(hits.path("hits").get(0).path("id").asString()).isEqualTo("sku-1");
      assertThat(hits.path("hits").get(0).path("document").has("_highlightResult")).isFalse();
      assertThat(hits.path("total").asLong()).isEqualTo(41);
      assertThat(hits.path("facets").path("brand").path("Acme").asInt()).isEqualTo(12);
      assertThat(hits.path("nextOffset").asLong()).isEqualTo(11);

      ToolCall sorted = TestCtx.call(SearchTools.QUERY, "{\"index\": \"products\", \"sort\": [\"price:asc\"]}");
      assertThat(c.classify(sorted, cfg, TestCtx.policy("algolia")).reason()).contains("replica indices");

      JsonNode docs = run(c, cfg, s, SearchTools.GET, "{\"index\": \"products\", \"ids\": [\"sku-1\", \"sku-9\"]}");
      assertThat(docs.path("documents")).hasSize(1);
      assertThat(docs.path("missing").toString()).isEqualTo("[\"sku-9\"]");

      JsonNode written = approve(c, cfg, s, SearchTools.INDEX, "{\"index\": \"products\", \"mode\": \"merge\", "
          + "\"documents\": [{\"objectID\": \"sku-1\", \"price\": 79}]}");
      JsonNode batch = FakeServer.jsonBody(algolia.to("POST /1/indexes/products/batch").getLast());
      assertThat(batch.toString()).isEqualTo("{\"requests\":[{\"action\":\"partialUpdateObject\",\"body\":"
          + "{\"objectID\":\"sku-1\",\"price\":79}}]}");
      assertThat(written.path("applied").asBoolean()).isTrue();
      assertThat(written.path("task").asLong()).isEqualTo(77);

      approve(c, cfg, s, SearchTools.DELETE, "{\"index\": \"products\", \"ids\": [\"sku-1\"]}");
      assertThat(FakeServer.jsonBody(algolia.to("POST /1/indexes/products/batch").getLast()).toString())
          .isEqualTo("{\"requests\":[{\"action\":\"deleteObject\",\"body\":{\"objectID\":\"sku-1\"}}]}");

      ToolCall notListed = TestCtx.call(SearchTools.QUERY, "{\"index\": \"payroll\"}");
      assertThat(c.classify(notListed, cfg, TestCtx.policy("algolia")).kind()).isEqualTo(Classification.Kind.DENY);
      ToolCall noId = TestCtx.call(SearchTools.INDEX, "{\"index\": \"products\", \"documents\": [{\"name\": \"x\"}]}");
      assertThat(c.classify(noId, cfg, TestCtx.policy("algolia")).reason()).contains("objectID");
    }
  }

  @Test
  void azureTellsAQueryKeyFromAnAdminKeyAndWritesODataFilters() throws Exception {
    String definition = "{\"name\": \"hotels\", \"fields\": [{\"name\": \"hotelId\", \"type\": \"Edm.String\", "
        + "\"key\": true}, {\"name\": \"name\", \"type\": \"Edm.String\", \"searchable\": true}, {\"name\": "
        + "\"rating\", \"type\": \"Edm.Double\", \"filterable\": true, \"sortable\": true}, {\"name\": \"tags\", "
        + "\"type\": \"Collection(Edm.String)\", \"filterable\": true, \"facetable\": true}, {\"name\": "
        + "\"embedding\", \"type\": \"Collection(Edm.Single)\", \"dimensions\": 3, \"vectorSearchProfile\": \"p\"}]}";
    try (FakeServer azure = new FakeServer()) {
      azure.on("GET /indexes", r -> "admin-key-0001".equals(r.getHeaders().get("api-key"))
              ? FakeServer.json(200, "{\"value\": [{\"name\": \"hotels\"}]}")
              : FakeServer.json(403, "{\"error\": {\"code\": \"\", \"message\": \"Forbidden\"}}"))
          .on("GET /indexes/hotels", r -> "admin-key-0001".equals(r.getHeaders().get("api-key"))
              ? FakeServer.json(200, definition) : FakeServer.json(403, "{\"error\": {\"message\": \"Forbidden\"}}"))
          .on("GET /indexes/hotels/stats", 200, "{\"documentCount\": 3, \"storageSize\": 900}")
          .on("POST /indexes/hotels/docs/search", 200, "{\"@odata.count\": 2, \"@search.facets\": {\"tags\": "
              + "[{\"value\": \"wifi\", \"count\": 2}]}, \"value\": [{\"@search.score\": 1.5, \"hotelId\": \"1\", "
              + "\"name\": \"Fancy\"}, {\"@search.score\": 0.9, \"hotelId\": \"2\", \"name\": \"Cozy\"}]}")
          .on("POST /indexes/hotels/docs/index", 207, "{\"value\": [{\"key\": \"1\", \"status\": true, \"statusCode\": "
              + "200}, {\"key\": \"2\", \"status\": false, \"statusCode\": 422, \"errorMessage\": \"bad field\"}]}");
      Connector c = new SearchConnector(new AzureSearchBackend(URI.create(azure.url())));
      ConnectionConfig cfg = TestCtx.config("azure-ai-search", "{\"endpoint\": \"https://contoso.search.windows.net\","
          + " \"indexes\": [\"hotels\"]}");

      TestSecrets query = new TestSecrets().with(AzureSearchBackend.API_KEY, "query-key-0001");
      TestResult t = c.test(cfg, query, TestCtx.of());
      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.permissions().level()).isEqualTo(TestResult.Level.READ);
      assertThat(t.identity()).isEqualTo("query key");
      assertThat(azure.to("GET /indexes").getFirst().getUrl().queryParameter("api-version"))
          .isEqualTo(AzureSearchBackend.API_VERSION);

      Connector admin = new SearchConnector(new AzureSearchBackend(URI.create(azure.url())));
      TestSecrets adminKey = new TestSecrets().with(AzureSearchBackend.API_KEY, "admin-key-0001");
      assertThat(admin.test(cfg, adminKey, TestCtx.of()).permissions().level()).isEqualTo(TestResult.Level.ADMIN);
      JsonNode hits = run(admin, cfg, adminKey, SearchTools.QUERY, "{\"index\": \"hotels\", \"query\": \"fancy\", "
          + "\"filter\": {\"tags\": \"wifi\", \"rating\": {\"$gte\": 4}, \"name\": {\"$ne\": \"O'Hare\"}}, \"sort\": "
          + "[\"rating:desc\"], \"facets\": [\"tags\"], \"vector\": [0.1, 0.2, 0.3], \"vectorField\": \"embedding\"}");
      JsonNode body = FakeServer.jsonBody(azure.to("POST /indexes/hotels/docs/search").getLast());
      assertThat(body.path("filter").asString()).isEqualTo("(tags/any(t: t eq 'wifi')) and (rating ge 4) and "
          + "(name ne 'O''Hare')");
      assertThat(body.path("orderby").asString()).isEqualTo("rating desc");
      assertThat(body.path("vectorQueries").toString()).isEqualTo("[{\"kind\":\"vector\",\"vector\":[0.1,0.2,0.3],"
          + "\"fields\":\"embedding\",\"k\":10}]");
      assertThat(hits.path("hits").get(0).path("id").asString()).isEqualTo("1");
      assertThat(hits.path("hits").get(0).path("score").asDouble()).isEqualTo(1.5);
      assertThat(hits.path("facets").path("tags").path("wifi").asInt()).isEqualTo(2);

      JsonNode written = approve(admin, cfg, adminKey, SearchTools.INDEX, "{\"index\": \"hotels\", \"documents\": "
          + "[{\"hotelId\": \"1\", \"name\": \"Fancy\"}, {\"hotelId\": \"2\", \"rating\": \"x\"}]}");
      JsonNode batch = FakeServer.jsonBody(azure.to("POST /indexes/hotels/docs/index").getLast());
      assertThat(batch.path("value").get(0).path("@search.action").asString()).isEqualTo("upload");
      assertThat(written.path("indexed").asInt()).isEqualTo(1);
      assertThat(written.path("failed").get(0).path("error").asString()).isEqualTo("bad field");

      ToolCall upper = TestCtx.call(SearchTools.DELETE, "{\"index\": \"Hotels\", \"ids\": [\"1\"]}");
      assertThat(c.classify(upper, cfg, TestCtx.policy("azure-ai-search")).kind())
          .isEqualTo(Classification.Kind.DENY);
      ToolCall badKey = TestCtx.call(SearchTools.GET, "{\"index\": \"hotels\", \"ids\": [\"_x\"]}");
      assertThat(c.classify(badKey, cfg, TestCtx.policy("azure-ai-search")).kind())
          .isEqualTo(Classification.Kind.DENY);
      ToolCall noField = TestCtx.call(SearchTools.QUERY, "{\"index\": \"hotels\", \"vector\": [0.1]}");
      assertThat(c.classify(noField, cfg, TestCtx.policy("azure-ai-search")).reason()).contains("vectorField");
      // A write needs the index's key field, which only an admin key may read: refused before any approval.
      PreparedAction refused = c.prepare(TestCtx.call(SearchTools.INDEX, "{\"index\": \"hotels\", \"documents\": "
          + "[{\"hotelId\": \"1\"}]}"), cfg, query, TestCtx.of());
      assertThat(refused.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
      assertThat(refused.message()).contains("refused").contains("403");
    }
  }
}
