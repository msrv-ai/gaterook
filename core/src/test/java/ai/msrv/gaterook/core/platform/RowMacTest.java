package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Canonical JSON, row MACs and sealed handles: what integrity of rows and agent cursors rests on. */
class RowMacTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** HMAC with one key per purpose, like the master keyset's HKDF keys; {@code locked} acts as locked mode. */
  static final class TestKeys implements HmacKeys {

    volatile boolean locked;
    private final String seed;

    TestKeys(String seed) {
      this.seed = seed;
    }

    @Override
    public byte[] mac(Purpose purpose, byte[] data) {
      if (locked) {
        throw new IllegalStateException("locked");
      }
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((seed + purpose.info()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data);
      } catch (java.security.GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  @Test
  void canonicalTextIgnoresKeyOrderAndNumberTyping() {
    JsonNode a = JSON.readTree("{\"b\":[1,2.0,{\"y\":true,\"x\":null}],\"a\":\"é\\\"\",\"c\":1.50}");
    JsonNode b = JSON.readTree("{\"c\":1.5,\"a\":\"é\\\"\",\"b\":[1.0,2,{\"x\":null,\"y\":true}]}");
    assertThat(CanonicalJson.text(a)).isEqualTo("{\"a\":\"é\\\"\",\"b\":[1,2,{\"x\":null,\"y\":true}],\"c\":1.5}")
        .isEqualTo(CanonicalJson.text(b));
    assertThat(CanonicalJson.sha256Hex(a)).hasSize(64).isEqualTo(CanonicalJson.sha256Hex(b));
    assertThat(CanonicalJson.text(JSON.readTree("1e3"))).isEqualTo("1000");
    assertThat(CanonicalJson.text(JSON.readTree("[1,2]"))).isNotEqualTo(CanonicalJson.text(JSON.readTree("[2,1]")));
  }

  @Test
  void aRowMacCoversTableIdVersionAndFields() {
    TestKeys keys = new TestKeys("k1");
    RowMac rows = new RowMac(keys, JSON);
    ObjectNode fields = rows.fields();
    fields.put("name", "dev-db");
    fields.set("config", rows.tree(Map.of("connection", Map.of("host", "db.dev", "port", 5432))));
    byte[] mac = rows.sign("connection", "cn_1", 3, fields);

    assertThat(rows.verify("connection", "cn_1", 3, fields, mac)).isTrue();
    // The same content read back from a JSON column: the port comes back as another number type.
    ObjectNode reread = rows.fields();
    reread.set("config", JSON.readTree("{\"connection\":{\"port\":5432.0,\"host\":\"db.dev\"}}"));
    reread.put("name", "dev-db");
    assertThat(rows.verify("connection", "cn_1", 3, reread, mac)).isTrue();

    assertThat(rows.verify("connection", "cn_1", 4, fields, mac)).as("version").isFalse();
    assertThat(rows.verify("connection", "cn_2", 3, fields, mac)).as("id").isFalse();
    assertThat(rows.verify("endpoint", "cn_1", 3, fields, mac)).as("table").isFalse();
    ObjectNode edited = fields.deepCopy();
    ((ObjectNode) edited.get("config").get("connection")).put("host", "evil.example");
    assertThat(rows.verify("connection", "cn_1", 3, edited, mac)).as("content").isFalse();
    assertThat(rows.verify("connection", "cn_1", 3, fields, null)).as("no mac").isFalse();
    assertThat(new RowMac(new TestKeys("k2"), JSON).verify("connection", "cn_1", 3, fields, mac))
        .as("another master key").isFalse();

    keys.locked = true;
    assertThat(rows.verify("connection", "cn_1", 3, fields, mac)).as("locked fails closed").isFalse();
    assertThatThrownBy(() -> rows.sign("connection", "cn_1", 3, fields)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void treeReadsAColumnValueAsItReloads() {
    RowMac rows = new RowMac(new TestKeys("k"), JSON);
    assertThat(rows.tree(null).isNull()).isTrue();
    assertThat(rows.tree(List.of("a", 1)).toString()).isEqualTo("[\"a\",1]");
    assertThat(rows.tree(Map.of("n", 2L)).path("n").isIntegralNumber()).isTrue();
  }

  @Test
  void sealedHandlesOpenOnlyForTheirPurposeAndUntilTheyExpire() {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-24T10:00:00Z"));
    SealedHandles handles = new SealedHandles(new TestKeys("k"), JSON, clock);
    JsonNode payload = JSON.readTree("{\"offset\":200,\"query\":\"select 1\"}");
    String handle = handles.seal("cursor", payload, Duration.ofMinutes(10));

    assertThat(handle).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    assertThat(handles.open("cursor", handle)).contains(payload);
    assertThat(handles.open("logs.page", handle)).as("another purpose").isEmpty();
    assertThat(new SealedHandles(new TestKeys("other"), JSON, clock).open("cursor", handle)).as("another key")
        .isEmpty();
    String[] parts = handle.split("\\.");
    String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"p\":\"cursor\",\"x\":9999999999,\"d\":{\"offset\":0}}".getBytes(StandardCharsets.UTF_8)) + "." + parts[1];
    assertThat(handles.open("cursor", forged)).as("edited body").isEmpty();
    assertThat(handles.open("cursor", "not-a-handle")).isEmpty();
    assertThat(handles.open("cursor", "a.b.c")).isEmpty();
    assertThat(handles.open("cursor", null)).isEmpty();

    clock.now = clock.now.plus(Duration.ofMinutes(11));
    assertThat(handles.open("cursor", handle)).as("expired").isEmpty();

    String capped = handles.seal("cursor", payload, Duration.ofDays(30));
    clock.now = clock.now.plus(Duration.ofHours(25));
    assertThat(handles.open("cursor", capped)).as("a day at most").isEmpty();
    assertThatThrownBy(() -> handles.seal("Bad Purpose", payload, null)).isInstanceOf(IllegalArgumentException.class);
  }

  static final class MutableClock extends Clock {

    volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
