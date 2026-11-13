package com.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.EndpointView;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The connection's settings, the tools an endpoint publishes and describe_connection's part. */
class SettingsAndEndpointTest extends CloudflareTestBase {

  @Test
  void settingsNormalizeZoneNamesAndRefuseBadOnes() throws Exception {
    CloudflareSettings s = CloudflareSettings.parse(cfg("\"zones\":[\"Example.COM.\",\"example.org\"]"));
    assertThat(s.accountId()).isEqualTo(ACCOUNT);
    assertThat(s.zones()).containsExactly("example.com", "example.org");
    assertThat(s.zoneAllowed("example.com")).isTrue();
    assertThat(s.zoneAllowed("example.net")).isFalse();
    assertThat(CloudflareSettings.parse(cfg(null)).zoneAllowed("anything.example")).isTrue();
    assertThatThrownBy(() -> CloudflareSettings.parse(new ConnectionConfig("c", "n", "cloudflare", Env.DEV, 1,
        json("{\"accountId\":\"0123\"}"), java.util.Set.of(), null)))
        .hasMessageContaining("32-character ID");
    assertThat(CloudflareSettings.normalize("xn--bcher-kva.example")).isEqualTo("xn--bcher-kva.example");
    assertThat(CloudflareSettings.normalize("-bad.example")).isNull();
    assertThat(CloudflareSettings.normalize("localhost")).isNull();
  }

  @Test
  void anEndpointNamesEachConnectionsZonesInTheZoneTools() {
    ConnectionConfig prod = new ConnectionConfig("c2", "cf-prod", "cloudflare", Env.PROD, 1, json("{\"accountId\":\""
        + ACCOUNT + "\",\"zones\":[\"example.com\"],\"credentials\":{}}"), java.util.Set.of(), null);
    List<ToolDefinition> tools = connector.tools(new EndpointView("prod", Env.PROD, List.of(prod), Map.of(),
        List.of()));
    ToolDefinition dns = tools.stream().filter(t -> t.name().equals(CloudflareTools.DNS_RECORDS)).findFirst()
        .orElseThrow();
    assertThat(dns.description()).endsWith(" Zones: cf-prod: example.com.");
    ToolDefinition workers = tools.stream().filter(t -> t.name().equals(CloudflareTools.WORKERS)).findFirst()
        .orElseThrow();
    assertThat(workers.description()).isEqualTo(CloudflareTools.WORKERS_TEXT);
    assertThat(connector.tools(new EndpointView("e", Env.DEV, List.of(), Map.of(), List.of())))
        .isEqualTo(connector.manifest().tools());
  }

  @Test
  void describeConnectionNamesTheAccountZonesAndToolsWithoutRequests() throws Exception {
    ConnectionConfig tested = cfg(Env.DEV, null, json("{\"account\":{\"id\":\"" + ACCOUNT + "\",\"name\":\"Acme\"},"
        + "\"zones\":[{\"id\":\"" + ZONE + "\",\"name\":\"example.com\"}]}"));
    JsonNode d = connector.describe(tested, secrets, TestCtx.of(), null).orElseThrow();
    assertThat(d.path("accountName").asString()).isEqualTo("Acme");
    assertThat(d.path("zones").get(0).asString()).isEqualTo("example.com");
    assertThat(d.path("tools").path("change").asString()).contains("cloudflare_dns_change");
    assertThat(d.path("examples").get(0).path("zone").asString()).isEqualTo("example.com");
    assertThat(cf.requests).isEmpty();
  }

  @Test
  void aReadInApprovalRunsTheApprovedArgumentsOnly() throws Exception {
    cf.ok("GET", "/accounts/" + ACCOUNT + "/cfd_tunnel", "[]");
    var call = call(CloudflareTools.TUNNELS, "{\"status\":\"down\"}");
    var preview = connector.prepare(call, cfg(null), secrets, TestCtx.of()).preview();
    assertThat(preview.payload().path("arguments")).isEqualTo(call.arguments());
    assertThat(connector.execute(call, cfg(null), secrets, TestCtx.of().approved(preview.payload(), null))
        .isError()).isFalse();
    var other = call(CloudflareTools.TUNNELS, "{\"status\":\"healthy\"}");
    assertThatThrownBy(() -> connector.execute(other, cfg(null), secrets, TestCtx.of().approved(preview.payload(),
        null))).hasMessageContaining("doesn't match the request that was approved");
  }
}
