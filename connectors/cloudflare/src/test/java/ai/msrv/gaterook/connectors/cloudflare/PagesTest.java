package ai.msrv.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code cloudflare_pages} and {@code cloudflare_pages_deployment}. */
class PagesTest extends CloudflareTestBase {

  private static final String P = "/accounts/" + ACCOUNT + "/pages/projects";
  private static final String OLD = "a1b2c3d4-0000-4000-8000-000000000001";
  private static final String NEW = "a1b2c3d4-0000-4000-8000-000000000002";

  private static String deployment(String id, String env, String status) {
    return "{\"id\":\"" + id + "\",\"short_id\":\"" + id.substring(0, 8) + "\",\"environment\":\"" + env + "\","
        + "\"url\":\"https://" + id.substring(0, 8) + ".site.pages.dev\",\"created_on\":\"2026-11-10T10:00:00Z\","
        + "\"latest_stage\":{\"name\":\"deploy\",\"status\":\"" + status + "\"},\"deployment_trigger\":{\"type\":"
        + "\"github:push\",\"metadata\":{\"branch\":\"main\",\"commit_hash\":\"9f86d081884c7d659a2feaa0c55ad015\","
        + "\"commit_message\":\"Fix checkout\"}},\"env_vars\":{\"API_TOKEN\":{\"type\":\"secret_text\",\"value\":\"\"},"
        + "\"PUBLIC_URL\":{\"type\":\"plain_text\",\"value\":\"https://example.com\"},\"DB_PASSWORD\":{\"type\":"
        + "\"plain_text\",\"value\":\"hunter2\"}}}";
  }

  @Test
  void projectsProjectDeploymentsAndBuildLogs() throws Exception {
    cf.ok("GET", P, "[{\"id\":\"p1\",\"name\":\"site\",\"subdomain\":\"site.pages.dev\",\"production_branch\":\"main\","
        + "\"latest_deployment\":" + deployment(NEW, "production", "success") + ",\"source\":{\"type\":\"github\","
        + "\"config\":{\"owner\":\"acme\",\"repo_name\":\"site\"}}}]");
    cf.ok("GET", P + "/site", "{\"name\":\"site\",\"build_config\":{\"build_command\":\"npm run build\","
        + "\"web_analytics_token\":\"7a3f0c2e9b\"},\"deployment_configs\":{\"production\":{\"env_vars\":{\"STRIPE_KEY\":"
        + "{\"type\":\"plain_text\",\"value\":\"sk_live_1\"},\"REGION\":{\"type\":\"plain_text\",\"value\":\"us\"}}}},"
        + "\"canonical_deployment\":" + deployment(NEW, "production", "success") + "}");
    cf.ok("GET", P + "/site/deployments", "[" + deployment(NEW, "production", "success") + "," + deployment(OLD,
        "production", "success") + "]");
    StringBuilder log = new StringBuilder("{\"total\":1500,\"includes_container_logs\":true,\"data\":[");
    for (int i = 0; i < 1500; i++) {
      log.append(i == 0 ? "" : ",").append("{\"ts\":\"2026-11-10T10:00:00Z\",\"line\":\"step ").append(i).append("\"}");
    }
    cf.ok("GET", P + "/site/deployments/" + NEW + "/history/logs", log.append("]}").toString());

    JsonNode projects = s(read(call(CloudflareTools.PAGES, "{}"), cfg(null)));
    assertThat(projects.at("/items/0/latest_deployment/id").asString()).isEqualTo(NEW);
    assertThat(projects.at("/items/0/latest_deployment/env_vars").isMissingNode()).isTrue();
    JsonNode project = s(read(call(CloudflareTools.PAGES, "{\"view\":\"project\",\"project\":\"site\"}"), cfg(null)));
    assertThat(project.at("/item/build_config/web_analytics_token").asString()).isEqualTo(Redactor.MASK);
    assertThat(project.at("/item/deployment_configs/production/env_vars/STRIPE_KEY/value").asString())
        .isEqualTo(Redactor.MASK);
    assertThat(project.at("/item/deployment_configs/production/env_vars/REGION/value").asString()).isEqualTo("us");
    assertThat(project.at("/item/canonical_deployment/env_vars/DB_PASSWORD/value").asString())
        .isEqualTo(Redactor.MASK);
    JsonNode deployments = s(read(call(CloudflareTools.PAGES, "{\"view\":\"deployments\",\"project\":\"site\","
        + "\"environment\":\"production\",\"perPage\":5}"), cfg(null)));
    assertThat(deployments.path("items")).hasSize(2);
    assertThat(deployments.at("/items/0/env_vars").isMissingNode()).isTrue();
    assertThat(cf.sent("GET", P + "/site/deployments").getFirst().getUrl().queryParameter("env"))
        .isEqualTo("production");
    JsonNode logs = s(read(call(CloudflareTools.PAGES, "{\"view\":\"logs\",\"project\":\"site\",\"deployment\":\""
        + NEW + "\"}"), cfg(null)));
    assertThat(logs.path("items")).hasSize(1_000);
    assertThat(logs.at("/items/999/line").asString()).isEqualTo("step 1499");
    assertThat(logs.path("totalCount").asInt()).isEqualTo(1500);
    assertThat(logs.path("notes").toString()).contains("the last 1000 are shown");
  }

  @Test
  void aRollbackRunsOnlyWhileProductionIsTheOneTheApprovalShowed() throws Exception {
    AtomicReference<String> live = new AtomicReference<>(NEW);
    cf.on("GET", P + "/site", r -> FakeCloudflare.envelope(200, "{\"name\":\"site\",\"canonical_deployment\":"
        + deployment(live.get(), "production", "success") + "}", null));
    cf.ok("GET", P + "/site/deployments/" + OLD, deployment(OLD, "production", "success"));
    cf.ok("POST", P + "/site/deployments/" + OLD + "/rollback", deployment(OLD, "production", "success"));
    ToolCall call = call(CloudflareTools.PAGES_DEPLOYMENT, "{\"project\":\"site\",\"deployment\":\"" + OLD + "\","
        + "\"action\":\"rollback\"}");
    PreparedAction p = prepare(call, cfg(null));
    assertThat(p.preview().text()).isEqualTo("POST " + cf.base() + P + "/site/deployments/" + OLD + "/rollback\n"
        + "Authorization: Bearer ‹masked›\n");
    assertThat(p.preview().impact().get(0).value()).startsWith("live: a1b2c3d4 · production · branch main");
    assertThat(p.preview().impact().get(1).value()).contains("commit 9f86d081884c").contains("Fix checkout");
    assertThat(p.preparedState().path("production").asString()).isEqualTo(NEW);

    live.set("a1b2c3d4-0000-4000-8000-000000000003");
    assertThatThrownBy(() -> approve(call, cfg(null), p))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("changed after the approval");
    assertThat(cf.changes()).isEmpty();
    live.set(NEW);
    approve(call, cfg(null), p);
    assertThat(cf.sent("POST", P + "/site/deployments/" + OLD + "/rollback")).hasSize(1);
    assertThat(cf.sent("POST", P + "/site/deployments/" + OLD + "/rollback").getFirst().getBodySize()).isZero();
  }

  @Test
  void onlyASuccessfulProductionDeploymentIsRolledBackTo() throws Exception {
    cf.ok("GET", P + "/site", "{\"name\":\"site\",\"canonical_deployment\":" + deployment(NEW, "production",
        "success") + "}");
    cf.ok("GET", P + "/site/deployments/" + OLD, deployment(OLD, "preview", "success"));
    cf.ok("GET", P + "/site/deployments/" + NEW, deployment(NEW, "production", "failure"));
    PreparedAction preview = prepare(call(CloudflareTools.PAGES_DEPLOYMENT, "{\"project\":\"site\",\"deployment\":\""
        + OLD + "\",\"action\":\"rollback\"}"), cfg(null));
    assertThat(preview.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(preview.message()).contains("only production deployments can be rolled back to");
    PreparedAction failed = prepare(call(CloudflareTools.PAGES_DEPLOYMENT, "{\"project\":\"site\",\"deployment\":\""
        + NEW + "\",\"action\":\"rollback\"}"), cfg(null));
    assertThat(failed.message()).contains("didn't finish successfully");
  }

  @Test
  void aRetryNeedsNoStateAndSaysWhenItGoesLive() throws Exception {
    cf.ok("GET", P + "/site/deployments/" + NEW, deployment(NEW, "production", "failure"));
    cf.ok("POST", P + "/site/deployments/" + NEW + "/retry", deployment(NEW, "production", "queued"));
    ToolCall call = call(CloudflareTools.PAGES_DEPLOYMENT, "{\"project\":\"site\",\"deployment\":\"" + NEW + "\","
        + "\"action\":\"retry\"}");
    PreparedAction p = prepare(call, cfg(null));
    assertThat(p.preview().warnings()).anySatisfy(w -> assertThat(w).contains("once it builds, it becomes live"));
    assertThat(p.preparedState()).isNull();
    JsonNode s = s(approve(call, cfg(null), p));
    assertThat(s.at("/item/env_vars").isMissingNode()).isTrue();
    assertThat(s.path("request").asString()).isEqualTo("POST " + P + "/site/deployments/" + NEW + "/retry");
  }
}
