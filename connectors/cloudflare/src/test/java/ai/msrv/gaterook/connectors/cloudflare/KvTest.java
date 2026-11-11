package ai.msrv.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code cloudflare_kv}: namespaces, keys and one value, masked. */
class KvTest extends CloudflareTestBase {

  private static final String NS = "/accounts/" + ACCOUNT + "/storage/kv/namespaces";

  @Test
  void namespacesAndKeysWithTheirCursor() throws Exception {
    cf.ok("GET", NS, "[{\"id\":\"" + RECORD + "\",\"title\":\"CACHE\",\"supports_url_encoding\":true}]",
        "{\"page\":1,\"per_page\":100,\"count\":1,\"total_count\":1,\"total_pages\":1}");
    cf.ok("GET", NS + "/" + RECORD + "/keys", "[{\"name\":\"flags/checkout\",\"expiration\":1893456000,"
        + "\"metadata\":{\"owner\":\"growth\"}}]", "{\"count\":1,\"cursor\":\"6Ck1la0VxJ0djhidm1MdX2FyDGxLKVeeHZZmORS\"}");
    JsonNode ns = s(read(call(CloudflareTools.KV, "{}"), cfg(null)));
    assertThat(ns.at("/items/0/title").asString()).isEqualTo("CACHE");
    JsonNode keys = s(read(call(CloudflareTools.KV, "{\"view\":\"keys\",\"namespace\":\"" + RECORD + "\",\"prefix\":"
        + "\"flags/\",\"limit\":50}"), cfg(null)));
    assertThat(keys.at("/items/0/name").asString()).isEqualTo("flags/checkout");
    assertThat(keys.path("nextCursor").asString()).isEqualTo("6Ck1la0VxJ0djhidm1MdX2FyDGxLKVeeHZZmORS");
    var req = cf.sent("GET", NS + "/" + RECORD + "/keys").getFirst();
    assertThat(req.getUrl().queryParameter("prefix")).isEqualTo("flags/");
    assertThat(req.getUrl().queryParameter("limit")).isEqualTo("50");
  }

  @Test
  void aJsonValueIsMaskedByItsKeys() throws Exception {
    cf.on("GET", NS + "/" + RECORD + "/values/flags%2Fcheckout", r -> FakeCloudflare.raw("application/octet-stream",
        "{\"enabled\":true,\"webhook_secret\":\"whsec_123\",\"rollout\":25}".getBytes(StandardCharsets.UTF_8)));
    JsonNode s = s(read(call(CloudflareTools.KV, "{\"view\":\"value\",\"namespace\":\"" + RECORD + "\",\"key\":"
        + "\"flags/checkout\"}"), cfg(null)));
    assertThat(s.at("/item/json/enabled").asBoolean()).isTrue();
    assertThat(s.at("/item/json/webhook_secret").asString()).isEqualTo(Redactor.MASK);
    assertThat(s.path("masked").asInt()).isEqualTo(1);
    assertThat(s.toString()).doesNotContain("whsec_123");
  }

  @Test
  void textAndBinaryValues() throws Exception {
    cf.on("GET", NS + "/" + RECORD + "/values/greeting", r -> FakeCloudflare.raw("text/plain",
        "hello world".getBytes(StandardCharsets.UTF_8)));
    cf.on("GET", NS + "/" + RECORD + "/values/logo.png", r -> FakeCloudflare.raw("image/png",
        new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, (byte) 0xff}));
    JsonNode text = s(read(call(CloudflareTools.KV, "{\"view\":\"value\",\"namespace\":\"" + RECORD + "\",\"key\":"
        + "\"greeting\"}"), cfg(null)));
    assertThat(text.at("/item/text").asString()).isEqualTo("hello world");
    JsonNode binary = s(read(call(CloudflareTools.KV, "{\"view\":\"value\",\"namespace\":\"" + RECORD + "\",\"key\":"
        + "\"logo.png\"}"), cfg(null)));
    assertThat(binary.at("/item/binary").asBoolean()).isTrue();
    assertThat(binary.at("/item/text").isMissingNode()).isTrue();
    assertThat(binary.at("/item/bytes").asInt()).isEqualTo(7);
  }

  @Test
  void aLargeValueIsCutAtSixtyFourKilobytes() throws Exception {
    byte[] big = "a".repeat(KvRead.MAX_VALUE + 500).getBytes(StandardCharsets.UTF_8);
    cf.on("GET", NS + "/" + RECORD + "/values/big", r -> FakeCloudflare.raw("text/plain", big));
    JsonNode s = s(read(call(CloudflareTools.KV, "{\"view\":\"value\",\"namespace\":\"" + RECORD + "\",\"key\":"
        + "\"big\"}"), cfg(null)));
    assertThat(s.at("/item/bytes").asInt()).isEqualTo(KvRead.MAX_VALUE);
    assertThat(s.at("/item/cut").asBoolean()).isTrue();
  }

  @Test
  void keysAreEncodedAsOneSegment() throws Exception {
    cf.on("GET", NS + "/" + RECORD + "/values/a%20b%3F%23%2F..", r -> FakeCloudflare.raw("text/plain",
        "x".getBytes(StandardCharsets.UTF_8)));
    read(call(CloudflareTools.KV, "{\"view\":\"value\",\"namespace\":\"" + RECORD + "\",\"key\":\"a b?#/..\"}"),
        cfg(null));
    assertThat(cf.sent("GET", NS + "/" + RECORD + "/values/a%20b%3F%23%2F..")).hasSize(1);
  }
}
