package ai.msrv.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code cloudflare_tunnels} and {@code cloudflare_logpush_jobs}. */
class TunnelsAndLogpushTest extends CloudflareTestBase {

  private static final String T = "/accounts/" + ACCOUNT + "/cfd_tunnel";
  private static final String TUNNEL = "f70ff985-a4ef-4643-bbbc-4a0ed4fc8415";

  @Test
  void tunnelsWithTheirStatusAndConnections() throws Exception {
    cf.ok("GET", T, "[{\"id\":\"" + TUNNEL + "\",\"name\":\"office\",\"status\":\"degraded\",\"tun_type\":"
        + "\"cfd_tunnel\",\"remote_config\":true,\"config_src\":\"cloudflare\",\"conns_active_at\":"
        + "\"2026-11-11T08:00:00Z\",\"tunnel_secret\":\"bm90LWEtcmVhbC1zZWNyZXQ=\",\"connections\":[{\"id\":\"c1\","
        + "\"colo_name\":\"iad08\",\"client_version\":\"2026.10.0\",\"origin_ip\":\"203.0.113.9\",\"opened_at\":"
        + "\"2026-11-11T08:00:00Z\"}]}]", "{\"page\":1,\"per_page\":50,\"count\":1,\"total_count\":1,"
        + "\"total_pages\":1}");
    JsonNode s = s(read(call(CloudflareTools.TUNNELS, "{\"status\":\"degraded\"}"), cfg(null)));
    assertThat(s.at("/items/0/status").asString()).isEqualTo("degraded");
    assertThat(s.at("/items/0/connections/0/colo_name").asString()).isEqualTo("iad08");
    assertThat(s.at("/items/0/connections/0/origin_ip").isMissingNode()).isTrue();
    assertThat(s.toString()).doesNotContain("bm90LWEtcmVhbC1zZWNyZXQ");
    var req = cf.sent("GET", T).getFirst();
    assertThat(req.getUrl().queryParameter("is_deleted")).isEqualTo("false");
    assertThat(req.getUrl().queryParameter("status")).isEqualTo("degraded");
    assertThat(cf.requests).noneMatch(r -> r.getUrl().encodedPath().endsWith("/token"));
  }

  @Test
  void oneTunnelWithItsConnectorsAndAConfiguration() throws Exception {
    cf.ok("GET", T + "/" + TUNNEL, "{\"id\":\"" + TUNNEL + "\",\"name\":\"office\",\"status\":\"healthy\","
        + "\"credentials_file\":{\"AccountTag\":\"" + ACCOUNT + "\",\"TunnelSecret\":\"c2VjcmV0\"}}");
    cf.ok("GET", T + "/" + TUNNEL + "/connections", "[{\"id\":\"k1\",\"version\":\"2026.10.0\",\"arch\":"
        + "\"linux_amd64\",\"conns\":[{\"colo_name\":\"iad08\",\"origin_ip\":\"203.0.113.9\"}]}]");
    cf.ok("GET", T + "/" + TUNNEL + "/configurations", "{\"tunnel_id\":\"" + TUNNEL + "\",\"version\":3,\"config\":{"
        + "\"ingress\":[{\"hostname\":\"app.example.com\",\"service\":\"http://localhost:8080\"},{\"service\":"
        + "\"http_status:404\"}]}}");
    JsonNode one = s(read(call(CloudflareTools.TUNNELS, "{\"view\":\"tunnel\",\"tunnel\":\"" + TUNNEL + "\"}"),
        cfg(null)));
    assertThat(one.at("/item/connectors/0/arch").asString()).isEqualTo("linux_amd64");
    assertThat(one.at("/item/credentials_file").isMissingNode()).isTrue();
    assertThat(one.toString()).doesNotContain("c2VjcmV0");
    JsonNode config = s(read(call(CloudflareTools.TUNNELS, "{\"view\":\"configuration\",\"tunnel\":\"" + TUNNEL
        + "\"}"), cfg(null)));
    assertThat(config.at("/item/config/ingress/0/hostname").asString()).isEqualTo("app.example.com");
  }

  @Test
  void logpushDestinationsKeepTheirPlaceButNotTheirCredentials() throws Exception {
    cf.zones(ACCOUNT, ZONE, "example.com");
    cf.ok("GET", "/zones/" + ZONE + "/logpush/jobs", "[{\"id\":7,\"name\":\"http-to-s3\",\"dataset\":\"http_requests\","
        + "\"enabled\":true,\"destination_conf\":\"s3://acme-logs/cloudflare/{DATE}?region=us-east-1&access-key-id="
        + "AKIAIOSFODNN7EXAMPLE&secret-access-key=wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY\",\"last_complete\":"
        + "\"2026-11-11T11:55:00Z\",\"last_error\":null},{\"id\":8,\"name\":\"to-datadog\",\"dataset\":"
        + "\"firewall_events\",\"destination_conf\":\"datadog://http-intake.logs.datadoghq.com/api/v2/logs?"
        + "header_DD-API-KEY=0123456789abcdef&ddsource=cloudflare\"},{\"id\":9,\"name\":\"sumo\",\"dataset\":"
        + "\"dns_logs\",\"destination_conf\":\"sumo://endpoint1.collection.sumologic.com/receiver/v1/http/"
        + "ZaVnC4dhaV2-DmMyKWkn7P-xGkCdGvOP8bhA0aUw0mSVyAw==\"}]");
    JsonNode s = s(read(call(CloudflareTools.LOGPUSH, "{\"zone\":\"example.com\"}"), cfg(null)));
    assertThat(s.at("/items/0/destination_conf").asString()).isEqualTo("s3://acme-logs/cloudflare/{DATE}?region="
        + "us-east-1&access-key-id=‹masked›&secret-access-key=‹masked›");
    assertThat(s.at("/items/1/destination_conf").asString()).isEqualTo("datadog://http-intake.logs.datadoghq.com/api"
        + "/v2/logs?header_DD-API-KEY=‹masked›&ddsource=cloudflare");
    assertThat(s.at("/items/2/destination_conf").asString()).isEqualTo("sumo://endpoint1.collection.sumologic.com/"
        + "receiver/v1/http/‹masked›");
    assertThat(s.path("masked").asInt()).isEqualTo(4);
  }

  @Test
  void accountJobsAndOneJob() throws Exception {
    cf.ok("GET", "/accounts/" + ACCOUNT + "/logpush/jobs", "[]");
    cf.ok("GET", "/accounts/" + ACCOUNT + "/logpush/jobs/7", "{\"id\":7,\"dataset\":\"workers_trace_events\","
        + "\"destination_conf\":\"r2://logs/workers?account-id=" + ACCOUNT + "&access-key-id=abc&secret-access-key="
        + "def\",\"error_message\":\"bucket not found\",\"last_error\":\"2026-11-11T11:00:00Z\"}");
    assertThat(s(read(call(CloudflareTools.LOGPUSH, "{}"), cfg(null))).path("items")).isEmpty();
    JsonNode job = s(read(call(CloudflareTools.LOGPUSH, "{\"job\":7}"), cfg(null)));
    assertThat(job.at("/item/destination_conf").asString()).isEqualTo("r2://logs/workers?account-id=" + ACCOUNT
        + "&access-key-id=‹masked›&secret-access-key=‹masked›");
    assertThat(job.at("/item/error_message").asString()).isEqualTo("bucket not found");
  }
}
