package ai.msrv.gaterook.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.audit.export.FakeCloud;
import ai.msrv.gaterook.core.audit.export.FakeSyslog;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The contract of {@code /api/audit-export} (ARCHITECTURE.md §6 settings, the UI's {@code AuditExportApi}) and of
 * Activity's Send to SIEM ({@code POST /api/activity/export} with {@code target: "siem"}) through MockMvc and the core's
 * own filters: the fields Settings reads, 201/204/404/409/422 where they belong, Test and Export now, and no answer
 * with a secret's value.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditExportApiIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SECRET = "cnry-export-wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

  private static FakeCloud cloud;
  private static FakeSyslog siem;
  private static TestCore core;
  private static AdminMvc api;
  private static String s3;
  private static String soc;

  @BeforeAll
  static void start() throws Exception {
    cloud = new FakeCloud();
    siem = new FakeSyslog(false);
    core = TestCore.start("audit-export-api", "--gateway.audit.export.tick=1h",
        "--gateway.backup.catch-up-delay=1h");
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
    calls(3, "sql_query");
    calls(2, "sql_execute");
  }

  @AfterAll
  static void stop() throws Exception {
    if (core != null) {
      core.close();
    }
    siem.close();
    cloud.close();
  }

  @Test
  @Order(1)
  void anEmptyListBeforeAnyDestination() {
    assertThat(api.ok("/api/audit-export").isArray()).isTrue();
    assertThat(api.ok("/api/audit-export").isEmpty()).isTrue();
    assertThat(api.get("/api/audit-export/nope").status()).isEqualTo(404);
  }

  @Test
  @Order(2)
  void aDestinationIsCreatedWithItsSecretWriteOnly() {
    JsonNode created = api.expect(201, HttpMethod.POST, "/api/audit-export", s3Body("audit-s3", true));
    s3 = created.path("id").asString();
    for (String field : List.of("id", "kind", "name", "enabled", "config", "schedule", "state", "failingSince",
        "lastAttemptAt", "lastError", "target", "secrets", "verified", "demo", "version")) {
      assertThat(created.has(field)).as(field).isTrue();
    }
    assertThat(created.path("kind").asString()).isEqualTo("s3");
    assertThat(created.path("schedule").asString()).isEqualTo("daily@02:00");
    assertThat(created.path("target").asString()).isEqualTo("s3://acme-audit/mcp-gateway/");
    assertThat(created.path("config").path("prefix").asString()).isEqualTo("mcp-gateway/");
    assertThat(created.path("state").isNull()).as("never ran").isTrue();
    assertThat(created.path("secrets").get(0).path("field").asString()).isEqualTo("/credentials/secretAccessKey");
    assertThat(created.path("secrets").get(0).path("version").asInt()).isEqualTo(1);

    JsonNode noSecret = api.expect(422, HttpMethod.POST, "/api/audit-export", s3Body("audit-s3-2", false));
    assertThat(noSecret.path("field").asString()).isEqualTo("/secrets/~1credentials~1secretAccessKey");
    ObjectNode badBucket = (ObjectNode) JSON.readTree(s3Body("bad", true));
    ((ObjectNode) badBucket.path("config")).put("bucket", "Not_A_Bucket");
    assertThat(api.expect(422, HttpMethod.POST, "/api/audit-export", badBucket).path("field").asString())
        .isEqualTo("/config/bucket");
    assertThat(api.expect(422, HttpMethod.POST, "/api/audit-export", s3Body("audit-s3", true)).path("field")
        .asString()).as("the name is taken").isEqualTo("/name");

    ObjectNode syslog = JsonNodeFactory.instance.objectNode();
    syslog.put("kind", "siem");
    syslog.put("name", "SOC");
    syslog.put("schedule", "hourly");
    syslog.putObject("config").put("host", "127.0.0.1").put("port", siem.port()).put("caCertificate", siem.caPem());
    soc = api.expect(201, HttpMethod.POST, "/api/audit-export", syslog).path("id").asString();
    assertThat(api.ok("/api/audit-export")).hasSize(2);
  }

  @Test
  @Order(3)
  void aChangeNeedsTheVersionTheFormRead() {
    long version = api.ok("/api/audit-export/" + s3).path("version").asLong();
    JsonNode changed = api.expect(200, HttpMethod.PUT, "/api/audit-export/" + s3, """
        {"schedule":"hourly","version":%d,"reason":"the SOC reads hourly"}""".formatted(version));
    assertThat(changed.path("schedule").asString()).isEqualTo("hourly");
    assertThat(api.expect(409, HttpMethod.PUT, "/api/audit-export/" + s3, """
        {"schedule":"daily@03:00","version":%d}""".formatted(version)).path("title").asString())
        .isEqualTo("Changed meanwhile");
    assertThat(api.expect(422, HttpMethod.PUT, "/api/audit-export/" + s3, """
        {"schedule":"weekly"}""").path("field").asString()).isEqualTo("/schedule");
    assertThat(api.send(HttpMethod.PUT, "/api/audit-export/nope", "{}").status()).isEqualTo(404);
  }

  @Test
  @Order(4)
  void testAndExportNowAnswerWithTheDestination() {
    JsonNode tested = api.expect(200, HttpMethod.POST, "/api/audit-export/" + s3 + "/test", null);
    assertThat(tested.path("id").asString()).isEqualTo(s3);
    assertThat(tested.path("test").path("ok").asBoolean()).isTrue();
    assertThat(tested.path("test").path("message").asString()).contains("mcp-gateway-connection-test.json");

    JsonNode run = api.expect(200, HttpMethod.POST, "/api/audit-export/" + s3 + "/run", null);
    assertThat(run.path("state").asString()).isEqualTo("ok");
    assertThat(run.path("lastAttemptAt").isNull()).isFalse();
    assertThat(run.path("lastExportedId").asLong()).isEqualTo(10);
    assertThat(cloud.objects()).hasSize(2);

    cloud.s3Fails(403, "AccessDenied", "Access Denied");
    calls(1, "sql_query");
    JsonNode failing = api.expect(200, HttpMethod.POST, "/api/audit-export/" + s3 + "/run", null);
    assertThat(failing.path("state").asString()).isEqualTo("failing");
    assertThat(failing.path("lastError").asString()).isEqualTo("403 AccessDenied: Access Denied");
    assertThat(failing.path("failingSince").isNull()).isFalse();
    cloud.s3Fails(0, null, null);
    JsonNode retried = api.expect(200, HttpMethod.POST, "/api/audit-export/" + s3 + "/run", null);
    assertThat(retried.path("state").asString()).isEqualTo("ok");
    assertThat(retried.path("failingSince").isNull()).isTrue();
    assertThat(api.send(HttpMethod.POST, "/api/audit-export/nope/run", null).status()).isEqualTo(404);
  }

  @Test
  @Order(5)
  void activitySendsToADestinationOfAnyKind() {
    JsonNode sent = api.expect(200, HttpMethod.POST, "/api/activity/export", """
        {"q":"tool:sql_execute","target":"siem","format":"jsonl","destinationId":"%s"}""".formatted(soc));
    assertThat(sent.path("rows").asLong()).isEqualTo(2);
    assertThat(sent.path("path").asString()).isEqualTo("127.0.0.1:" + siem.port());
    assertThat(sent.path("format").asString()).isEqualTo("jsonl");
    assertThat(sent.path("bytes").asLong()).isPositive();

    assertThat(api.expect(422, HttpMethod.POST, "/api/activity/export", """
        {"target":"siem","format":"cef","destinationId":"%s"}""".formatted(soc)).path("field").asString())
        .isEqualTo("/format");
    assertThat(api.expect(422, HttpMethod.POST, "/api/activity/export", """
        {"target":"siem","format":"jsonl"}""").path("field").asString()).isEqualTo("/destinationId");
    assertThat(api.send(HttpMethod.POST, "/api/activity/export", """
        {"target":"siem","format":"jsonl","destinationId":"nope"}""").status()).isEqualTo(404);

    // The SIEM is down: the core says what happened, as a 502.
    ObjectNode down = JsonNodeFactory.instance.objectNode();
    down.put("kind", "siem");
    down.put("name", "down");
    down.put("schedule", "hourly");
    down.putObject("config").put("host", "127.0.0.1").put("port", 9).put("caCertificate", siem.caPem());
    String gone = api.expect(201, HttpMethod.POST, "/api/audit-export", down).path("id").asString();
    AdminMvc.Answer refused = api.post("/api/activity/export", """
        {"target":"siem","format":"jsonl","destinationId":"%s"}""".formatted(gone));
    assertThat(refused.status()).isEqualTo(502);
    assertThat(refused.json().path("detail").asString()).contains("Couldn't send to 127.0.0.1:9");
    api.expect(204, HttpMethod.DELETE, "/api/audit-export/" + gone, null);
  }

  @Test
  @Order(6)
  void aDeletedDestinationIsGoneWithItsSecret() {
    api.expect(204, HttpMethod.DELETE, "/api/audit-export/" + s3, "{\"reason\":\"moved to BigQuery\"}");
    assertThat(api.get("/api/audit-export/" + s3).status()).isEqualTo(404);
    assertThat(api.send(HttpMethod.DELETE, "/api/audit-export/" + s3, null).status()).isEqualTo(404);
    assertThat(api.ok("/api/activity/changes?q=Audit%20export").path("changes").findValuesAsString("subject"))
        .contains("Audit export audit-s3", "Audit export SOC");
    assertThat(core.jdbc().queryForObject("select count(*) from secret where owner_kind = 'audit-export'",
        Integer.class)).isZero();
  }

  @Test
  @Order(100)
  void noAnswerCarriesTheSecret() {
    for (AdminMvc.Answer a : api.answers()) {
      assertThat(a.body()).as(a.method() + " " + a.target()).doesNotContain(SECRET);
      if (a.contentType() != null && a.contentType().contains("json") && !a.body().isEmpty()) {
        assertThat(AdminMvc.secretLookingValues(a.json())).as(a.method() + " " + a.target()).isEmpty();
      }
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static String s3Body(String name, boolean withSecret) {
    return """
        {"kind":"s3","name":"%s","schedule":"daily@02:00","reason":"SOC wants a copy",
         "config":{"bucket":"acme-audit","prefix":"mcp-gateway","region":"eu-central-1","endpoint":"%s",
           "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}%s}""".formatted(name,
        cloud.url(), withSecret ? ",\"secrets\":{\"/credentials/secretAccessKey\":{\"value\":\"" + SECRET + "\"}}"
            : "");
  }

  private static List<Long> calls(int n, String tool) {
    AuditWriter writer = core.bean(AuditWriter.class);
    Caller caller = new Caller(Caller.Kind.LOCAL_TOKEN, "tok_test", "codex-laptop", "codex", Ingress.LOCAL,
        "127.0.0.1");
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ObjectNode args = JsonNodeFactory.instance.objectNode().put("sql", "SELECT 1");
      AuditWriter.Call call = new AuditWriter.Call(Ids.next("tc_"), "dev", tool, tool, "dev-db", Env.DEV, caller,
          "codex", null, "2025-11-25", "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", false, args,
          CanonicalJson.sha256Hex(args), null);
      writer.received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, Classification.Kind.READ, null));
      writer.completed(call, new AuditWriter.Completion(AuditWriter.Verdict.ALLOWED, null, null, null, null, 12L, 2L,
          10L, null, 4L, "0".repeat(64), "1 row", 0, false, null, null, null, null, null));
    }
    return ids;
  }
}
