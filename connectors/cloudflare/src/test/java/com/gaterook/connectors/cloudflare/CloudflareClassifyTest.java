package com.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.Classification.Kind;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** {@code classify}: reads are READ, the four changes MUTATION, everything else is refused without I/O. */
class CloudflareClassifyTest extends CloudflareTestBase {

  private static final ConnectionConfig LISTED = cfg("\"zones\":[\"example.com\"]");

  @Test
  void readsAreReadAndChangesAreMutationsWithFacts() {
    for (String[] c : List.of(
        new String[] {CloudflareTools.ZONES, "{}"},
        new String[] {CloudflareTools.DNS_RECORDS, "{\"zone\":\"example.com\",\"type\":\"A\",\"name\":\"www\"}"},
        new String[] {CloudflareTools.WORKERS, "{\"view\":\"deployments\",\"script\":\"api\"}"},
        new String[] {CloudflareTools.WORKERS_LOGS, "{\"script\":\"api\",\"from\":\"-2h\"}"},
        new String[] {CloudflareTools.KV, "{\"view\":\"keys\",\"namespace\":\"" + RECORD + "\"}"},
        new String[] {CloudflareTools.PAGES, "{\"view\":\"deployments\",\"project\":\"site\"}"},
        new String[] {CloudflareTools.ANALYTICS, "{\"zone\":\"example.com\",\"dataset\":\"security_events\"}"},
        new String[] {CloudflareTools.TUNNELS, "{\"status\":\"down\"}"},
        new String[] {CloudflareTools.LOGPUSH, "{\"zone\":\"example.com\"}"})) {
      Classification cl = classify(call(c[0], c[1]), LISTED);
      assertThat(cl.kind()).as(c[0] + " " + cl.reason()).isEqualTo(Kind.READ);
    }
    Classification dns = classify(call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"create\","
        + "\"type\":\"A\",\"name\":\"www\",\"content\":\"192.0.2.10\"}"), LISTED);
    assertThat(dns.kind()).isEqualTo(Kind.MUTATION);
    assertThat(dns.reason()).isEqualTo("Creates DNS record www (A) in example.com");
    assertThat(dns.facts().path("cloudflare.action").asString()).isEqualTo("dns.create");
    Classification purge = classify(call(CloudflareTools.CACHE_PURGE, "{\"zone\":\"example.com\","
        + "\"everything\":true}"), LISTED);
    assertThat(purge.kind()).isEqualTo(Kind.MUTATION);
    assertThat(purge.reason()).isEqualTo("Purges everything cached for example.com");
    Classification deploy = classify(call(CloudflareTools.WORKERS_DEPLOY, "{\"script\":\"api\",\"versions\":[{"
        + "\"version\":\"bcf48806-b317-4351-9ee7-36e7d557d4de\",\"percentage\":100}]}"), LISTED);
    assertThat(deploy.kind()).isEqualTo(Kind.MUTATION);
    assertThat(deploy.facts().path("cloudflare.script").asString()).isEqualTo("api");
    Classification pages = classify(call(CloudflareTools.PAGES_DEPLOYMENT, "{\"project\":\"site\",\"deployment\":"
        + "\"bcf48806-b317-4351-9ee7-36e7d557d4de\",\"action\":\"rollback\"}"), LISTED);
    assertThat(pages.kind()).isEqualTo(Kind.MUTATION);
    assertThat(cf.requests).as("no I/O").isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      zone outside the list | cloudflare_dns_records | {"zone":"example.org"} | dev.cloudflare.zone_not_allowed
      purge of another zone | cloudflare_cache_purge | {"zone":"example.com","files":["https://cdn.example.org/a.js"]} | dev.cloudflare.purge_outside_zone
      purge prefix with a scheme | cloudflare_cache_purge | {"zone":"example.com","prefixes":["https://www.example.com/a/"]} | dev.cloudflare.bad_input
      two kinds of purge | cloudflare_cache_purge | {"zone":"example.com","hosts":["www.example.com"],"tags":["a"]} | dev.cloudflare.bad_input
      purge everything false | cloudflare_cache_purge | {"zone":"example.com","everything":false} | dev.cloudflare.bad_input
      tag with a comma | cloudflare_cache_purge | {"zone":"example.com","tags":["a,b"]} | dev.cloudflare.bad_input
      create without content | cloudflare_dns_change | {"zone":"example.com","action":"create","type":"A","name":"www"} | dev.cloudflare.bad_input
      A record with a host | cloudflare_dns_change | {"zone":"example.com","action":"create","type":"A","name":"www","content":"example.net"} | dev.cloudflare.bad_input
      CAA with content | cloudflare_dns_change | {"zone":"example.com","action":"create","type":"CAA","name":"@","content":"0 issue x"} | dev.cloudflare.bad_input
      CAA with a foreign field | cloudflare_dns_change | {"zone":"example.com","action":"create","type":"CAA","name":"@","data":{"flags":0,"port":1}} | dev.cloudflare.bad_input
      proxied TXT | cloudflare_dns_change | {"zone":"example.com","action":"create","type":"TXT","name":"x","content":"v","proxied":true} | dev.cloudflare.bad_input
      ttl below 30 | cloudflare_dns_change | {"zone":"example.com","action":"create","type":"A","name":"www","content":"192.0.2.1","ttl":5} | dev.cloudflare.bad_input
      update without fields | cloudflare_dns_change | {"zone":"example.com","action":"update","record":"372e67954025e0ba6aaa6d586b9e0b59"} | dev.cloudflare.bad_input
      delete with fields | cloudflare_dns_change | {"zone":"example.com","action":"delete","record":"372e67954025e0ba6aaa6d586b9e0b59","content":"x"} | dev.cloudflare.bad_input
      create with a record ID | cloudflare_dns_change | {"zone":"example.com","action":"create","record":"372e67954025e0ba6aaa6d586b9e0b59","type":"A","name":"www","content":"192.0.2.1"} | dev.cloudflare.bad_input
      bad record name | cloudflare_dns_change | {"zone":"example.com","action":"create","type":"A","name":"bad name","content":"192.0.2.1"} | dev.cloudflare.bad_input
      percentages under 100 | cloudflare_workers_deploy | {"script":"api","versions":[{"version":"bcf48806-b317-4351-9ee7-36e7d557d4de","percentage":60},{"version":"0f5bd2bd-7d6d-4a31-8d43-2b2ca6c2e6e1","percentage":30}]} | dev.cloudflare.bad_input
      a version twice | cloudflare_workers_deploy | {"script":"api","versions":[{"version":"bcf48806-b317-4351-9ee7-36e7d557d4de","percentage":50},{"version":"BCF48806-B317-4351-9EE7-36E7D557D4DE","percentage":50}]} | dev.cloudflare.bad_input
      three versions | cloudflare_workers_deploy | {"script":"api","versions":[{"version":"bcf48806-b317-4351-9ee7-36e7d557d4de","percentage":100},{"version":"0f5bd2bd-7d6d-4a31-8d43-2b2ca6c2e6e1","percentage":0.01},{"version":"1f5bd2bd-7d6d-4a31-8d43-2b2ca6c2e6e1","percentage":0.01}]} | dev.cloudflare.bad_input
      pages action missing | cloudflare_pages_deployment | {"project":"site","deployment":"bcf48806-b317-4351-9ee7-36e7d557d4de"} | dev.cloudflare.bad_input
      kv key named like a token | cloudflare_kv | {"view":"value","namespace":"372e67954025e0ba6aaa6d586b9e0b59","key":"github_token"} | dev.cloudflare.kv_credential
      kv dot key | cloudflare_kv | {"view":"value","namespace":"372e67954025e0ba6aaa6d586b9e0b59","key":".."} | dev.cloudflare.bad_input
      logs range too long | cloudflare_workers_logs | {"from":"-8d"} | dev.cloudflare.range
      logs bare number | cloudflare_workers_logs | {"from":"1731000000"} | dev.cloudflare.range
      logs groupBy on events | cloudflare_workers_logs | {"groupBy":["$metadata.level"]} | dev.cloudflare.bad_input
      logs filter without value | cloudflare_workers_logs | {"filters":[{"key":"$metadata.level","operation":"eq"}]} | dev.cloudflare.bad_input
      analytics dimension of the other dataset | cloudflare_analytics | {"zone":"example.com","dataset":"security_summary","groupBy":["cacheStatus"]} | dev.cloudflare.bad_input
      analytics filter of the other dataset | cloudflare_analytics | {"zone":"example.com","dataset":"http_requests","filter":{"action":"block"}} | dev.cloudflare.bad_input
      analytics bad path | cloudflare_analytics | {"zone":"example.com","dataset":"security_events","filter":{"path":"admin"}} | dev.cloudflare.bad_input
      workers view needs script | cloudflare_workers | {"view":"settings"} | dev.cloudflare.bad_input
      workers argument of another view | cloudflare_workers | {"view":"scripts","version":"bcf48806-b317-4351-9ee7-36e7d557d4de"} | dev.cloudflare.bad_input
      unknown argument | cloudflare_zones | {"url":"https://evil.example/"} | dev.cloudflare.bad_input
      pages view needs project | cloudflare_pages | {"view":"logs"} | dev.cloudflare.bad_input
      tunnel view needs tunnel | cloudflare_tunnels | {"view":"configuration"} | dev.cloudflare.bad_input
      not a zone | cloudflare_zones | {"zone":"not a zone"} | dev.cloudflare.bad_input
      """)
  void refusalsNeedNoRequest(String what, String tool, String args, String rule) {
    Classification cl = classify(call(tool, args), LISTED);
    assertThat(cl.kind()).as(what + ": " + cl.reason()).isEqualTo(Kind.DENY);
    assertThat(cl.ruleId()).as(what + ": " + cl.reason()).isEqualTo(rule);
    assertThat(cf.requests).isEmpty();
  }

  @Test
  void aZoneByIdIsCheckedAgainstTheLastTestsNamesWhenItKnowsThem() {
    ConnectionConfig tested = cfg(Env.DEV, "\"zones\":[\"example.com\"]", json("{\"zones\":[{\"id\":\"" + ZONE
        + "\",\"name\":\"example.com\"},{\"id\":\"" + ZONE2 + "\",\"name\":\"example.org\"}]}"));
    assertThat(classify(call(CloudflareTools.DNS_RECORDS, "{\"zone\":\"" + ZONE + "\"}"), tested).kind())
        .isEqualTo(Kind.READ);
    assertThat(classify(call(CloudflareTools.DNS_RECORDS, "{\"zone\":\"" + ZONE2 + "\"}"), tested).ruleId())
        .isEqualTo("dev.cloudflare.zone_not_allowed");
    // Unknown IDs are resolved, and checked, before any request about them (ZonesAndDnsTest).
    assertThat(classify(call(CloudflareTools.DNS_RECORDS, "{\"zone\":\"" + RECORD + "\"}"), tested).kind())
        .isEqualTo(Kind.READ);
  }

  @Test
  void badSettingsAndUnknownToolsAreDenied() {
    ConnectionConfig bad = new ConnectionConfig("c2", "cf-bad", "cloudflare", Env.DEV, 1,
        json("{\"accountId\":\"not-an-account\",\"credentials\":{}}"), java.util.Set.of(), null);
    assertThat(classify(call(CloudflareTools.ZONES, "{}"), bad).ruleId()).isEqualTo("dev.cloudflare.config_invalid");
    assertThat(classify(call("cloudflare_execute", "{}"), LISTED).ruleId()).isEqualTo("dev.cloudflare.unknown_tool");
    ConnectionConfig badZone = cfg("\"zones\":[\"not a zone\"]");
    assertThat(classify(call(CloudflareTools.ZONES, "{}"), badZone).ruleId())
        .isEqualTo("dev.cloudflare.config_invalid");
  }

  @Test
  void prodLimitsTheLogsRangeToItsPolicy() {
    ConnectionConfig prod = cfg(Env.PROD, null, null);
    assertThat(classify(call(CloudflareTools.WORKERS_LOGS, "{\"from\":\"-2d\"}"), prod).ruleId())
        .isEqualTo("prod.cloudflare.range");
    assertThat(classify(call(CloudflareTools.WORKERS_LOGS, "{\"from\":\"-12h\"}"), prod).kind()).isEqualTo(Kind.READ);
  }

  @Test
  void toolsFollowTheConnectorRules() {
    for (ToolDefinition t : connector.manifest().tools()) {
      assertThat(t.name()).startsWith("cloudflare_");
      if (CloudflareTools.CHANGES.contains(t.name())) {
        assertThat(t.nature()).isEqualTo(ToolDefinition.Nature.MUTATION);
        assertThat(t.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
        assertThat(t.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
        assertThat(t.annotations().readOnlyHint()).isFalse();
        assertThat(t.outputSchema()).isNull();
      } else {
        assertThat(t.nature()).isEqualTo(ToolDefinition.Nature.READ);
        assertThat(t.defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
        assertThat(t.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
        assertThat(t.annotations().readOnlyHint()).isTrue();
        assertThat(t.outputSchema()).isNotNull();
      }
      assertThat(t.inputSchema().path("properties").has("url")).as(t.name() + " takes no URL").isFalse();
    }
    assertThat(connector.manifest().tools()).hasSize(13);
  }
}
