package ai.msrv.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code cloudflare_workers_logs}: GateRook's own query of Workers Logs, sent as a dry run. */
class WorkersLogsTest extends CloudflareTestBase {

  private static final String Q = "/accounts/" + ACCOUNT + "/workers/observability/telemetry/query";

  @Test
  void eventsOfOneWorkerWithFiltersAndASearch() throws Exception {
    cf.ok("POST", Q, "{\"events\":{\"count\":1,\"events\":[{\"timestamp\":1762862400000,\"dataset\":"
        + "\"cloudflare-workers\",\"$metadata\":{\"id\":\"evt-1\",\"level\":\"error\",\"message\":\"TypeError: x is "
        + "undefined\",\"service\":\"api\",\"statusCode\":500,\"url\":\"https://api.example.com/v1/orders?token="
        + "abc123&page=2\",\"requestId\":\"req-1\",\"fingerprint\":\"f\"},\"$workers\":{\"outcome\":\"exception\","
        + "\"scriptName\":\"api\",\"cpuTimeMs\":3,\"event\":{\"request\":{\"method\":\"GET\",\"url\":"
        + "\"https://api.example.com/v1/orders?token=abc123\",\"headers\":{\"authorization\":\"Bearer xyz\"}}}},"
        + "\"source\":{\"msg\":\"failed\",\"api_key\":\"k-123\"}}]}}");
    JsonNode s = s(read(call(CloudflareTools.WORKERS_LOGS, "{\"script\":\"api\",\"from\":\"-30m\",\"search\":"
        + "\"TypeError\",\"filters\":[{\"key\":\"$metadata.statusCode\",\"operation\":\"gte\",\"value\":500},"
        + "{\"key\":\"$metadata.level\",\"operation\":\"exists\"}],\"limit\":1}"), cfg(null)));
    RecordedRequest req = cf.sent("POST", Q).getFirst();
    JsonNode body = FakeCloudflare.body(req);
    assertThat(body.path("dry").asBoolean()).as("not saved as a query").isTrue();
    assertThat(body.path("view").asString()).isEqualTo("events");
    assertThat(body.at("/timeframe/to").asLong()).isEqualTo(TestCtx.NOW.toEpochMilli());
    assertThat(body.at("/timeframe/from").asLong()).isEqualTo(TestCtx.NOW.minusSeconds(1800).toEpochMilli());
    assertThat(body.at("/parameters/filters/0")).isEqualTo(json("{\"key\":\"$metadata.service\",\"operation\":\"eq\","
        + "\"type\":\"string\",\"value\":\"api\"}"));
    assertThat(body.at("/parameters/filters/1")).isEqualTo(json("{\"key\":\"$metadata.statusCode\",\"operation\":"
        + "\"gte\",\"type\":\"number\",\"value\":500}"));
    assertThat(body.at("/parameters/filters/2/value").isMissingNode()).isTrue();
    assertThat(body.at("/parameters/needle")).isEqualTo(json("{\"value\":\"TypeError\",\"isRegex\":false,"
        + "\"matchCase\":false}"));
    assertThat(body.path("limit").asInt()).isEqualTo(1);

    assertThat(s.path("from").asString()).isEqualTo(Instant.parse("2026-11-11T11:30:00Z").toString());
    JsonNode e = s.at("/items/0");
    assertThat(e.path("time").asString()).isEqualTo("2025-11-11T12:00:00Z");
    assertThat(e.path("message").asString()).contains("TypeError");
    assertThat(e.path("url").asString()).isEqualTo("https://api.example.com/v1/orders?token=‹masked›&page=2");
    assertThat(e.at("/request/url").asString()).endsWith("?token=‹masked›");
    assertThat(e.at("/request/headers").isMissingNode()).as("no request headers").isTrue();
    assertThat(e.at("/source/api_key").asString()).isEqualTo(Redactor.MASK);
    assertThat(e.path("fingerprint").isMissingNode()).isTrue();
    assertThat(s.path("nextCursor").asString()).isEqualTo("evt-1");
  }

  @Test
  void theNextPageContinuesFromTheCursor() throws Exception {
    cf.ok("POST", Q, "{\"events\":{\"events\":[]}}");
    JsonNode s = s(read(call(CloudflareTools.WORKERS_LOGS, "{\"cursor\":\"evt-1\"}"), cfg(null)));
    JsonNode body = FakeCloudflare.body(cf.sent("POST", Q).getFirst());
    assertThat(body.path("offset").asString()).isEqualTo("evt-1");
    assertThat(body.path("offsetDirection").asString()).isEqualTo("next");
    assertThat(body.at("/parameters/filters")).isEmpty();
    assertThat(s.path("notes").toString()).contains("Workers Logs is turned on");
  }

  @Test
  void aCountGroupsByTheGivenFields() throws Exception {
    cf.ok("POST", Q, "{\"calculations\":[{\"alias\":\"count\",\"aggregates\":[{\"groups\":[{\"key\":"
        + "\"$metadata.statusCode\",\"value\":500}],\"count\":42,\"value\":42},{\"groups\":[{\"key\":"
        + "\"$metadata.statusCode\",\"value\":502}],\"count\":7,\"value\":7}]}]}");
    JsonNode s = s(read(call(CloudflareTools.WORKERS_LOGS, "{\"view\":\"count\",\"groupBy\":[\"$metadata.statusCode\"],"
        + "\"from\":\"-1d\"}"), cfg(null)));
    JsonNode body = FakeCloudflare.body(cf.sent("POST", Q).getFirst());
    assertThat(body.path("view").asString()).isEqualTo("calculations");
    assertThat(body.at("/parameters/calculations/0/operator").asString()).isEqualTo("count");
    assertThat(body.at("/parameters/groupBys/0")).isEqualTo(json("{\"type\":\"number\",\"value\":"
        + "\"$metadata.statusCode\"}"));
    assertThat(s.at("/items/0/groups/$metadata.statusCode").asInt()).isEqualTo(500);
    assertThat(s.at("/items/0/count").asInt()).isEqualTo(42);
  }

  @Test
  void theLimitFollowsTheEnvironmentsLineLimit() throws Exception {
    cf.ok("POST", Q, "{\"events\":{\"events\":[]}}");
    connector.execute(call(CloudflareTools.WORKERS_LOGS, "{\"limit\":500}"), cfg(null), secrets,
        TestCtx.of().with(TestCtx.prod()));
    assertThat(FakeCloudflare.body(cf.sent("POST", Q).getFirst()).path("limit").asInt()).isEqualTo(200);
  }

  @Test
  void aMissingPermissionIsNamed() {
    cf.fail("POST", Q, 403, 10000, "Authentication error");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> connector.execute(call(CloudflareTools.WORKERS_LOGS,
        "{}"), cfg(null), secrets, TestCtx.of()))
        .hasMessageContaining("Workers Observability · Edit");
  }
}
