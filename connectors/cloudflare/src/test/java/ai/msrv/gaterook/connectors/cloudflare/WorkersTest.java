package ai.msrv.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code cloudflare_workers} and {@code cloudflare_workers_deploy}. */
class WorkersTest extends CloudflareTestBase {

  private static final String W = "/accounts/" + ACCOUNT + "/workers/scripts";
  private static final String V1 = "bcf48806-b317-4351-9ee7-36e7d557d4de";
  private static final String V2 = "0f5bd2bd-7d6d-4a31-8d43-2b2ca6c2e6e1";

  @Test
  void listsWorkersWithoutTheirCode() throws Exception {
    cf.ok("GET", W, "[{\"id\":\"api\",\"created_on\":\"2026-01-01T00:00:00Z\",\"modified_on\":\"2026-11-10T00:00:00Z\","
        + "\"compatibility_date\":\"2026-10-01\",\"handlers\":[\"fetch\"],\"etag\":\"abc\",\"script\":\"export default"
        + "{}\"}]");
    JsonNode s = s(read(call(CloudflareTools.WORKERS, "{}"), cfg(null)));
    assertThat(s.path("view").asString()).isEqualTo("scripts");
    assertThat(s.at("/items/0/id").asString()).isEqualTo("api");
    assertThat(s.at("/items/0/script").isMissingNode()).isTrue();
    assertThat(cf.requests).noneMatch(r -> r.getUrl().encodedPath().endsWith("/content/v2")
        || r.getUrl().encodedPath().endsWith("/secrets"));
  }

  @Test
  void settingsMaskBindingsThatHoldSecrets() throws Exception {
    cf.ok("GET", W + "/api/settings", "{\"compatibility_date\":\"2026-10-01\",\"observability\":{\"enabled\":true,"
        + "\"logs\":{\"enabled\":true,\"invocation_logs\":true}},\"bindings\":["
        + "{\"name\":\"ENVIRONMENT\",\"type\":\"plain_text\",\"text\":\"production\"},"
        + "{\"name\":\"STRIPE_API_KEY\",\"type\":\"plain_text\",\"text\":\"sk_live_51H8x\"},"
        + "{\"name\":\"DATABASE_URL\",\"type\":\"plain_text\",\"text\":\"postgres://u:p@db.internal/app\"},"
        + "{\"name\":\"SESSION_SECRET\",\"type\":\"secret_text\"},"
        + "{\"name\":\"SIGNING\",\"type\":\"secret_key\",\"key_base64\":\"MC4CAQAwBQYDK2VwBCIEIA\",\"format\":\"raw\"},"
        + "{\"name\":\"CONFIG\",\"type\":\"json\",\"json\":{\"region\":\"us\",\"password\":\"hunter2\"}},"
        + "{\"name\":\"CACHE\",\"type\":\"kv_namespace\",\"namespace_id\":\"" + RECORD + "\"}]}");
    JsonNode s = s(read(call(CloudflareTools.WORKERS, "{\"view\":\"settings\",\"script\":\"api\"}"), cfg(null)));
    JsonNode b = s.at("/item/bindings");
    assertThat(b.get(0).path("text").asString()).isEqualTo("production");
    assertThat(b.get(1).path("text").asString()).isEqualTo(Redactor.MASK);
    assertThat(b.get(2).path("text").asString()).isEqualTo(Redactor.MASK);
    assertThat(b.get(4).path("key_base64").asString()).isEqualTo(Redactor.MASK);
    assertThat(b.get(5).at("/json/region").asString()).isEqualTo("us");
    assertThat(b.get(5).at("/json/password").asString()).isEqualTo(Redactor.MASK);
    assertThat(b.get(6).path("namespace_id").asString()).isEqualTo(RECORD);
    assertThat(s.path("masked").asInt()).isEqualTo(4);
    assertThat(s.toString()).doesNotContain("sk_live", "hunter2", "MC4CAQAw", "u:p@");
  }

  @Test
  void versionsDeploymentsRoutesAndDomains() throws Exception {
    cf.ok("GET", W + "/api/versions", "{\"items\":[{\"id\":\"" + V1 + "\",\"number\":12,\"metadata\":{"
        + "\"created_on\":\"2026-11-10T10:00:00Z\",\"source\":\"wrangler\"}}]}");
    cf.ok("GET", W + "/api/deployments", "{\"deployments\":[{\"id\":\"d1\",\"source\":\"api\",\"strategy\":"
        + "\"percentage\",\"versions\":[{\"version_id\":\"" + V1 + "\",\"percentage\":100}]}]}");
    cf.zones(ACCOUNT, ZONE, "example.com");
    cf.ok("GET", "/zones/" + ZONE + "/workers/routes", "[{\"id\":\"r1\",\"pattern\":\"example.com/api/*\",\"script\":"
        + "\"api\"},{\"id\":\"r2\",\"pattern\":\"example.com/*\",\"script\":\"site\"}]");
    cf.ok("GET", "/accounts/" + ACCOUNT + "/workers/domains", "[{\"id\":\"dom1\",\"hostname\":\"api.example.com\","
        + "\"service\":\"api\",\"zone_name\":\"example.com\"},{\"id\":\"dom2\",\"hostname\":\"api.example.org\","
        + "\"service\":\"api\",\"zone_name\":\"example.org\"}]");
    JsonNode versions = s(read(call(CloudflareTools.WORKERS, "{\"view\":\"versions\",\"script\":\"api\","
        + "\"perPage\":5}"), cfg(null)));
    assertThat(versions.at("/items/0/number").asInt()).isEqualTo(12);
    assertThat(cf.sent("GET", W + "/api/versions").getFirst().getUrl().queryParameter("per_page")).isEqualTo("5");
    JsonNode deployments = s(read(call(CloudflareTools.WORKERS, "{\"view\":\"deployments\",\"script\":\"api\"}"),
        cfg(null)));
    assertThat(deployments.at("/items/0/versions/0/percentage").asInt()).isEqualTo(100);
    JsonNode routes = s(read(call(CloudflareTools.WORKERS, "{\"view\":\"routes\",\"zone\":\"example.com\","
        + "\"script\":\"api\"}"), cfg(null)));
    assertThat(routes.path("items")).hasSize(1);
    JsonNode domains = s(read(call(CloudflareTools.WORKERS, "{\"view\":\"domains\",\"script\":\"api\"}"),
        cfg("\"zones\":[\"example.com\"]")));
    assertThat(domains.path("items")).hasSize(1);
    assertThat(domains.path("hiddenByConnection").asInt()).isEqualTo(1);
    assertThat(cf.sent("GET", "/accounts/" + ACCOUNT + "/workers/domains").getFirst().getUrl()
        .queryParameter("service")).isEqualTo("api");
  }

  @Test
  void aRollbackShowsTheLiveDeploymentAndRunsOnlyIfItIsStillLive() throws Exception {
    AtomicReference<String> live = new AtomicReference<>("d1");
    cf.on("GET", W + "/api/deployments", r -> FakeCloudflare.envelope(200, "{\"deployments\":[{\"id\":\""
        + live.get() + "\",\"created_on\":\"2026-11-11T09:00:00Z\",\"versions\":[{\"version_id\":\"" + V2
        + "\",\"percentage\":100}]}]}", null));
    cf.ok("GET", W + "/api/versions/" + V1, "{\"id\":\"" + V1 + "\",\"number\":11,\"metadata\":{\"created_on\":"
        + "\"2026-11-09T10:00:00Z\"},\"annotations\":{\"workers/message\":\"last good build\"}}");
    cf.ok("POST", W + "/api/deployments", "{\"id\":\"d2\",\"versions\":[{\"version_id\":\"" + V1
        + "\",\"percentage\":100}]}");
    ToolCall call = call(CloudflareTools.WORKERS_DEPLOY, "{\"script\":\"api\",\"versions\":[{\"version\":\"" + V1
        + "\",\"percentage\":100}],\"message\":\"Roll back the 5xx spike\"}");
    PreparedAction p = prepare(call, cfg(null));
    assertThat(p.preview().text()).startsWith("POST " + cf.base() + W + "/api/deployments\n");
    assertThat(p.preview().impact().get(0).value()).contains("version " + V2 + " at 100%").contains("deployment d1");
    assertThat(p.preview().impact().get(1).value()).contains("version #11 " + V1 + " at 100%")
        .contains("last good build");
    assertThat(p.preparedState().path("deployment").asString()).isEqualTo("d1");
    assertThat(cf.changes()).isEmpty();

    live.set("d9");
    assertThatThrownBy(() -> approve(call, cfg(null), p))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("was deployed again after the approval");
    assertThat(cf.changes()).isEmpty();

    live.set("d1");
    approve(call, cfg(null), p);
    assertThat(FakeCloudflare.body(cf.sent("POST", W + "/api/deployments").getFirst())).isEqualTo(json(
        "{\"strategy\":\"percentage\",\"versions\":[{\"version_id\":\"" + V1 + "\",\"percentage\":100}],"
            + "\"annotations\":{\"workers/message\":\"Roll back the 5xx spike\"}}"));
  }

  @Test
  void aGradualRolloutSplitsTraffic() throws Exception {
    cf.ok("GET", W + "/api/deployments", "{\"deployments\":[]}");
    cf.ok("GET", W + "/api/versions/" + V1, "{\"id\":\"" + V1 + "\",\"number\":1}");
    cf.ok("GET", W + "/api/versions/" + V2, "{\"id\":\"" + V2 + "\",\"number\":2}");
    cf.ok("POST", W + "/api/deployments", "{\"id\":\"d1\"}");
    ToolCall call = call(CloudflareTools.WORKERS_DEPLOY, "{\"script\":\"api\",\"versions\":[{\"version\":\"" + V1
        + "\",\"percentage\":90.5},{\"version\":\"" + V2 + "\",\"percentage\":9.5}]}");
    PreparedAction p = prepare(call, cfg(null));
    assertThat(p.preview().warnings()).anySatisfy(w -> assertThat(w).contains("Splits traffic"));
    assertThat(p.preparedState().path("deployment").isNull()).isTrue();
    approve(call, cfg(null), p);
    JsonNode body = FakeCloudflare.body(cf.sent("POST", W + "/api/deployments").getFirst());
    assertThat(body.at("/versions/0/percentage").asDouble()).isEqualTo(90.5);
    assertThat(body.at("/versions/1/percentage").asDouble()).isEqualTo(9.5);
  }

  @Test
  void aVersionOfAnotherWorkerIsRejected() throws Exception {
    cf.ok("GET", W + "/api/deployments", "{\"deployments\":[]}");
    cf.fail("GET", W + "/api/versions/" + V1, 404, 10007, "version not found");
    PreparedAction p = prepare(call(CloudflareTools.WORKERS_DEPLOY, "{\"script\":\"api\",\"versions\":[{\"version\":\""
        + V1 + "\",\"percentage\":100}]}"), cfg(null));
    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(p.message()).contains("isn't a version of Worker api");
  }
}
