package com.gaterook.connectors.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.ToolCall;
import java.time.Duration;
import mockwebserver3.MockResponse;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the vector_* tools refuse and how a change runs, on a fake Qdrant: the classification without I/O, the exact
 * request an approver sees, the approved payload as the only thing that runs, a lost answer as an unknown outcome, and
 * an answer kept within the call's size.
 */
class VectorConnectorTest {

  private static Classification classify(Connector c, ConnectionConfig cfg, String tool, String args) {
    return c.classify(TestCtx.call(tool, args), cfg, TestCtx.policy(cfg.type()));
  }

  private static ConnectionConfig qdrant(String url) {
    return TestCtx.config("qdrant", "{\"url\": \"" + url + "\", \"auth\": {\"method\": \"apiKey\"}, \"collections\": "
        + "[\"docs\", \"kb_*\"]}");
  }

  private static final TestSecrets KEY = new TestSecrets().with(QdrantBackend.API_KEY, "qdrant-key-0001");

  @Test
  void theClassificationReadsEveryArgumentWithoutIo() {
    Connector c = Connectors.qdrant();
    ConnectionConfig cfg = qdrant("https://qdrant.example.com:6333");
    assertThat(classify(c, cfg, VectorTools.QUERY, "{\"collection\": \"docs\", \"vector\": [0.1, 0.2]}").kind())
        .isEqualTo(Classification.Kind.READ);
    for (String[] bad : new String[][] {
        {VectorTools.QUERY, "{\"collection\": \"payroll\", \"vector\": [1]}", "dev.qdrant.not-listed"},
        {VectorTools.QUERY, "{\"collection\": \"docs\", \"vector\": [1], \"id\": 3}", "dev.qdrant.bad-argument"},
        {VectorTools.QUERY, "{\"collection\": \"docs\"}", "dev.qdrant.bad-argument"},
        {VectorTools.QUERY, "{\"collection\": \"docs\", \"id\": \"not-a-uuid\"}", "dev.qdrant.bad-argument"},
        {VectorTools.QUERY, "{\"collection\": \"docs\", \"text\": \"x\"}", "dev.qdrant.unsupported"},
        {VectorTools.GET, "{\"collection\": \"docs\", \"ids\": [1], \"filter\": {\"a\": 1}}", "dev.qdrant.bad-argument"},
        {VectorTools.GET, "{\"collection\": \"docs\", \"ids\": [1, 1]}", "dev.qdrant.bad-argument"},
        {VectorTools.GET, "{\"collection\": \"docs\", \"filter\": {\"a\": {\"$regex\": \"x\"}}}", "dev.qdrant.bad-filter"},
        {VectorTools.GET, "{\"collection\": \"docs\", \"namespace\": \"x\"}", "dev.qdrant.bad-argument"},
        {VectorTools.UPSERT, "{\"collection\": \"docs\", \"records\": [{\"id\": 1, \"vector\": [\"x\"]}]}",
            "dev.qdrant.bad-argument"},
        {VectorTools.UPSERT, "{\"collection\": \"docs\", \"records\": [{\"id\": 1, \"payload\": {}}]}",
            "dev.qdrant.bad-argument"},
        {VectorTools.DELETE, "{\"collection\": \"docs\", \"ids\": []}", "dev.qdrant.bad-argument"}}) {
      Classification cl = classify(c, cfg, bad[0], bad[1]);
      assertThat(cl.kind()).as(bad[1]).isEqualTo(Classification.Kind.DENY);
      assertThat(cl.ruleId()).as(bad[1]).isEqualTo(bad[2]);
    }
    StringBuilder many = new StringBuilder("{\"collection\": \"docs\", \"records\": [");
    for (int i = 0; i < 101; i++) {
      many.append(i == 0 ? "" : ", ").append("{\"id\": ").append(i).append('}');
    }
    assertThat(classify(c, cfg, VectorTools.UPSERT, many + "]}").ruleId()).isEqualTo("dev.qdrant.too-many");
    String big = "x".repeat(20_000);
    StringBuilder large = new StringBuilder("{\"collection\": \"docs\", \"records\": [");
    for (int i = 0; i < 60; i++) {
      large.append(i == 0 ? "" : ", ").append("{\"id\": ").append(i).append(", \"metadata\": {\"t\": \"").append(big)
          .append("\"}}");
    }
    assertThat(classify(c, cfg, VectorTools.UPSERT, large + "]}").ruleId()).isEqualTo("dev.qdrant.too-large");
    Classification plain = classify(c, qdrant("http://qdrant.internal:6333"), VectorTools.LIST, "{}");
    assertThat(plain.ruleId()).isEqualTo("dev.qdrant.bad-config");
    assertThat(plain.reason()).contains("Plain http:// reaches only this computer");
    Classification write = classify(c, cfg, VectorTools.UPSERT, "{\"collection\": \"kb_eu\", \"records\": "
        + "[{\"id\": 1, \"vector\": [1, 0]}]}");
    assertThat(write.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(write.reason()).isEqualTo("Upserts 1 record into kb_eu");
  }

  @Test
  void anApprovedUpsertSendsExactlyThePreviewsRequest() throws Exception {
    try (FakeServer q = new FakeServer()) {
      q.on("POST /collections/docs/points", 200, "{\"result\": [{\"id\": 2, \"payload\": {}}], \"status\": \"ok\"}")
          .on("PUT /collections/docs/points", 200, "{\"result\": {\"operation_id\": 5, \"status\": \"completed\"}, "
              + "\"status\": \"ok\"}");
      Connector c = Connectors.qdrant();
      ConnectionConfig cfg = qdrant(q.url());
      ToolCall call = TestCtx.call(VectorTools.UPSERT, "{\"collection\": \"docs\", \"records\": [{\"id\": 1, \"vector\": "
          + "[0.5, 0.5], \"metadata\": {\"city\": \"Berlin\"}}, {\"id\": 2, \"vector\": [0.1, 0.9]}], \"note\": \"x\"}");
      PreparedAction p = c.prepare(call, cfg, KEY, TestCtx.of());
      ActionPreview preview = p.preview();
      assertThat(preview.text()).startsWith("PUT " + q.url() + "/collections/docs/points?wait=true\n")
          .contains("api-key: ‹secret›").doesNotContain("qdrant-key-0001")
          .endsWith("{\"points\":[{\"id\":1,\"vector\":[0.5,0.5],\"payload\":{\"city\":\"Berlin\"}},{\"id\":2,"
              + "\"vector\":[0.1,0.9],\"payload\":{}}]}");
      assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).startsWith("1 record already exists and will be "
          + "replaced: [2]"));
      assertThat(c.execute(call, cfg, KEY, TestCtx.of().approved(preview.payload())).structuredContent()
          .path("upserted").asInt()).isEqualTo(2);
      var sent = q.to("PUT /collections/docs/points").getLast();
      assertThat(FakeServer.body(sent)).isEqualTo(preview.text().substring(preview.text().indexOf("\n\n") + 2));
      assertThat(sent.getHeaders().get("api-key")).isEqualTo("qdrant-key-0001");

      // The approved payload runs, not the call: another collection or op in it is refused.
      ObjectNode other = ((ObjectNode) preview.payload().deepCopy()).put("target", "payroll");
      assertThatThrownBy(() -> c.execute(call, cfg, KEY, TestCtx.of().approved(other)))
          .hasMessageContaining("isn't one of the collections");
      ObjectNode delete = ((ObjectNode) preview.payload().deepCopy()).put("op", "delete");
      assertThatThrownBy(() -> c.execute(call, cfg, KEY, TestCtx.of().approved(delete)))
          .hasMessageContaining("another change");
      assertThatThrownBy(() -> c.execute(call, cfg, KEY, TestCtx.of())).hasMessageContaining("Not approved");
    }
  }

  @Test
  void aChangeWhoseAnswerIsLostIsAnUnknownOutcomeAndARefusalChangesNothing() throws Exception {
    try (FakeServer q = new FakeServer()) {
      q.on("POST /collections/docs/points/delete", r -> new MockResponse.Builder()
          .onResponseStart(SocketEffect.ShutdownConnection.INSTANCE).build());
      q.on("POST /collections/docs/points", 200, "{\"result\": [], \"status\": \"ok\"}");
      q.on("PUT /collections/docs/points", 403, "{\"status\": {\"error\": \"Forbidden: Global manage access is "
          + "required\"}}");
      Connector c = Connectors.qdrant();
      ConnectionConfig cfg = qdrant(q.url());
      ToolCall delete = TestCtx.call(VectorTools.DELETE, "{\"collection\": \"docs\", \"ids\": [1]}");
      PreparedAction p = c.prepare(delete, cfg, KEY, TestCtx.of());
      assertThat(p.preview().warnings()).anySatisfy(w -> assertThat(w).startsWith("1 of the 1 ids don't exist"));
      assertThatThrownBy(() -> c.execute(delete, cfg, KEY, TestCtx.of().approved(p.preview().payload())))
          .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
              .isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN))
          .hasMessageContaining("whether it changed anything is not known");
      assertThat(q.to("POST /collections/docs/points/delete")).hasSize(1);

      ToolCall upsert = TestCtx.call(VectorTools.UPSERT, "{\"collection\": \"docs\", \"records\": [{\"id\": 1}]}");
      PreparedAction u = c.prepare(upsert, cfg, KEY, TestCtx.of());
      assertThatThrownBy(() -> c.execute(upsert, cfg, KEY, TestCtx.of().approved(u.preview().payload())))
          .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
              .isEqualTo(ConnectorException.Kind.DENIED))
          .hasMessageContaining("Global manage access is required").hasMessageContaining("nothing changed");
    }
  }

  @Test
  void anAnswerStaysWithinTheCallsSizeAndAReadsLostAnswerChangesNothing() throws Exception {
    try (FakeServer q = new FakeServer()) {
      StringBuilder points = new StringBuilder();
      for (int i = 0; i < 40; i++) {
        points.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"score\":0.5,\"payload\":{\"t\":\"")
            .append("y".repeat(400)).append("\"}}");
      }
      q.on("POST /collections/docs/points/query", 200, "{\"result\": {\"points\": [" + points + "]}}");
      q.on("POST /collections/kb_x/points/query", r -> new MockResponse.Builder()
          .onResponseStart(SocketEffect.ShutdownConnection.INSTANCE).build());
      Connector c = Connectors.qdrant();
      ConnectionConfig cfg = qdrant(q.url());
      Limits small = new Limits(1_000, 1_000, 8 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024,
          Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofHours(1));
      JsonNode out = c.execute(TestCtx.call(VectorTools.QUERY, "{\"collection\": \"docs\", \"vector\": [1, 0], "
          + "\"topK\": 40}"), cfg, KEY, TestCtx.of().with(small)).structuredContent();
      assertThat(out.path("matches").size()).isLessThan(40).isGreaterThan(0);
      assertThat(out.path("truncated").asInt()).isEqualTo(40 - out.path("matches").size());
      assertThat(ServiceHttp.bytes(out).length).isLessThan(8 * 1024);
      assertThatThrownBy(() -> c.execute(TestCtx.call(VectorTools.QUERY, "{\"collection\": \"kb_x\", \"vector\": "
          + "[1, 0]}"), cfg, KEY, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(
              e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE)).hasMessageContaining("Nothing changed");
    }
  }

  @Test
  void pineconeSendsTheKeyOnlyToAnIndexHostOfPinecone() throws Exception {
    try (FakeServer control = new FakeServer()) {
      control.on("GET /indexes/docs", 200, "{\"name\": \"docs\", \"dimension\": 2, \"metric\": \"cosine\", \"host\": "
          + "\"evil.example.com\", \"status\": {\"ready\": true}}");
      Connector c = new VectorConnector(new PineconeBackend(java.net.URI.create(control.url())));
      ConnectionConfig cfg = TestCtx.config("pinecone", "{}");
      TestSecrets s = new TestSecrets().with(PineconeBackend.API_KEY, "pc-key-0001");
      assertThatThrownBy(() -> c.execute(TestCtx.call(VectorTools.QUERY, "{\"collection\": \"docs\", \"vector\": "
          + "[1, 0]}"), cfg, s, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(
              e.kind()).isEqualTo(ConnectorException.Kind.DENIED)).hasMessageContaining("doesn't send the key there");
      assertThat(control.requests).hasSize(1);
      assertThat(control.requests.getFirst().getHeaders().get("X-Pinecone-API-Version"))
          .isEqualTo(PineconeBackend.API_VERSION);
    }
  }
}
