package ai.msrv.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code cloudflare_analytics}: GateRook's own GraphQL queries, values only as literals. */
class AnalyticsTest extends CloudflareTestBase {

  @BeforeEach
  void zone() {
    cf.zones(ACCOUNT, ZONE, "example.com");
  }

  private String query() {
    return FakeCloudflare.body(cf.sent("POST", "/graphql").getLast()).path("query").asString();
  }

  @Test
  void securityEventsNewestFirstWithTheFilterAsLiterals() throws Exception {
    cf.on("POST", "/graphql", r -> FakeCloudflare.body(200, "{\"data\":{\"viewer\":{\"zones\":[{\"events\":[{\"datetime\":"
        + "\"2026-11-11T11:59:00Z\",\"action\":\"block\",\"source\":\"firewallManaged\",\"ruleId\":\"6179ae15\","
        + "\"clientIP\":\"198.51.100.7\",\"clientRequestPath\":\"/login\",\"clientRequestQuery\":"
        + "\"?redirect=/home&session=abcdef123\",\"rayName\":\"8e1c2f\"}]}]}},\"errors\":null}"));
    JsonNode s = s(read(call(CloudflareTools.ANALYTICS, "{\"zone\":\"example.com\",\"dataset\":\"security_events\","
        + "\"from\":\"-6h\",\"filter\":{\"action\":\"block\",\"path\":\"/login\\\"} } evil { x\",\"status\":403},"
        + "\"limit\":10}"), cfg(null)));
    String q = query();
    assertThat(q).startsWith("query GateRookAnalytics { viewer { zones(filter: {zoneTag: \"" + ZONE + "\"}) { "
        + "events: firewallEventsAdaptive(filter: {datetime_geq: \"2026-11-11T06:00:00Z\", datetime_leq: "
        + "\"2026-11-11T12:00:00Z\", action: \"block\", clientRequestPath: \"/login\\\"} } evil { x\", "
        + "edgeResponseStatus: 403}, limit: 10, orderBy: [datetime_DESC]) { datetime action source ruleId");
    assertThat(s.at("/items/0/action").asString()).isEqualTo("block");
    assertThat(s.at("/items/0/clientRequestQuery").asString()).isEqualTo("?redirect=/home&session=‹masked›");
    assertThat(s.path("from").asString()).isEqualTo("2026-11-11T06:00:00Z");
  }

  @Test
  void aSummaryAndHttpRequestsCountByDimension() throws Exception {
    cf.on("POST", "/graphql", r -> {
      String q = FakeCloudflare.body(r).path("query").asString();
      String data = q.contains("httpRequestsAdaptiveGroups")
          ? "{\"viewer\":{\"zones\":[{\"totals\":[{\"count\":1000,\"sum\":{\"edgeResponseBytes\":5000,\"visits\":"
              + "300}}],\"groups\":[{\"count\":900,\"sum\":{\"edgeResponseBytes\":4000,\"visits\":280},\"dimensions\":"
              + "{\"edgeResponseStatus\":200,\"cacheStatus\":\"hit\"}},{\"count\":100,\"dimensions\":{"
              + "\"edgeResponseStatus\":502,\"cacheStatus\":\"miss\"}}]}]}}"
          : "{\"viewer\":{\"zones\":[{\"groups\":[{\"count\":12,\"dimensions\":{\"action\":\"block\",\"source\":"
              + "\"waf\"}}]}]}}";
      return FakeCloudflare.body(200, "{\"data\":" + data + "}");
    });
    JsonNode summary = s(read(call(CloudflareTools.ANALYTICS, "{\"zone\":\"example.com\",\"dataset\":"
        + "\"security_summary\"}"), cfg(null)));
    assertThat(query()).contains("groups: firewallEventsAdaptiveGroups(filter: {datetime_geq: \"2026-11-11T11:00:00Z\""
        + ", datetime_leq: \"2026-11-11T12:00:00Z\"}, limit: 25, orderBy: [count_DESC]) { count dimensions { action "
        + "source } }");
    assertThat(summary.at("/items/0/count").asInt()).isEqualTo(12);
    JsonNode http = s(read(call(CloudflareTools.ANALYTICS, "{\"zone\":\"example.com\",\"dataset\":\"http_requests\","
        + "\"groupBy\":[\"edgeResponseStatus\",\"cacheStatus\"],\"filter\":{\"host\":\"www.example.com\"}}"),
        cfg(null)));
    assertThat(query()).contains("totals: httpRequestsAdaptiveGroups(filter: {datetime_geq: \"2026-11-11T11:00:00Z\", "
        + "datetime_leq: \"2026-11-11T12:00:00Z\", clientRequestHTTPHost: \"www.example.com\"}, limit: 1)")
        .contains("dimensions { edgeResponseStatus cacheStatus } }");
    assertThat(http.at("/item/count").asInt()).isEqualTo(1000);
    assertThat(http.at("/items/1/dimensions/edgeResponseStatus").asInt()).isEqualTo(502);
  }

  @Test
  void graphqlErrorsAreRefusalsWithThePermission() {
    cf.on("POST", "/graphql", r -> FakeCloudflare.body(200, "{\"data\":null,"
        + "\"errors\":[{\"message\":\"not authorized for that account\",\"path\":[\"viewer\"]}]}"));
    assertThatThrownBy(() -> connector.execute(call(CloudflareTools.ANALYTICS, "{\"zone\":\"example.com\","
        + "\"dataset\":\"security_events\"}"), cfg(null), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("refused the query: not authorized for that account")
        .hasMessageContaining("Zone · Analytics · Read");
  }

  @Test
  void aLiteralCantCloseTheQuery() {
    assertThat(Analytics.literal("a\"b\\c\n")).isEqualTo("\"a\\\"b\\\\c\\n\"");
  }
}
