package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.HmacKeys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@code requestState} is attacker-controlled: every change of what it binds makes it absent (report 01 §8.1). */
class RequestStateCodecTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Instant T0 = Instant.parse("2026-10-22T12:00:00Z");

  final MutableClock clock = new MutableClock(T0);
  final RequestStateCodec codec = new RequestStateCodec(keys("k1"), JSON, clock);
  final String digest = RequestStateCodec.argumentsDigest("sql_execute", JSON.readTree(
      "{\"connection\":\"dev-db\",\"sql\":\"UPDATE t SET x = 1\"}"));

  @Test
  void aSealedStateOpensForTheSameCallerEndpointToolAndArguments() {
    String s = seal(2, T0.plusSeconds(600));
    assertThat(s).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    RequestStateCodec.State state = open(s, "pr_1", "ep_1", "tools/call", "sql_execute", digest);
    assertThat(state).isNotNull();
    assertThat(state.approvalId()).isEqualTo("ap_1");
    assertThat(state.round()).isEqualTo(2);
    assertThat(state.t0()).isEqualTo(T0);
  }

  @Test
  void aChangedMacIsNoState() {
    String s = seal(1, T0.plusSeconds(600));
    String mac = s.substring(s.indexOf('.') + 1);
    String flipped = s.substring(0, s.indexOf('.') + 1) + (mac.charAt(0) == 'A' ? 'B' : 'A') + mac.substring(1);
    assertThat(open(flipped, "pr_1", "ep_1", "tools/call", "sql_execute", digest)).isNull();
    assertThat(open(s.substring(0, s.indexOf('.') + 1), "pr_1", "ep_1", "tools/call", "sql_execute", digest)).isNull();
  }

  @Test
  void aChangedPayloadIsNoState() {
    String s = seal(1, T0.plusSeconds(600));
    ObjectNode body = (ObjectNode) JSON.readTree(new String(Base64.getUrlDecoder().decode(s.substring(0,
        s.indexOf('.'))), StandardCharsets.UTF_8));
    body.put("ap", "ap_other");
    String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(body))
        + s.substring(s.indexOf('.'));
    assertThat(open(forged, "pr_1", "ep_1", "tools/call", "sql_execute", digest)).isNull();
    // The round and the start can't be moved back to win more time either.
    body = (ObjectNode) JSON.readTree(new String(Base64.getUrlDecoder().decode(s.substring(0, s.indexOf('.'))),
        StandardCharsets.UTF_8));
    body.put("r", 0);
    forged = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(body))
        + s.substring(s.indexOf('.'));
    assertThat(open(forged, "pr_1", "ep_1", "tools/call", "sql_execute", digest)).isNull();
  }

  @Test
  void aStateOfAnotherKeyIsNoState() {
    RequestStateCodec other = new RequestStateCodec(keys("k2"), JSON, clock);
    String s = other.seal("ap_1", "pr_1", "ep_1", "tools/call", "sql_execute", digest, 1, T0, T0.plusSeconds(600));
    assertThat(open(s, "pr_1", "ep_1", "tools/call", "sql_execute", digest)).isNull();
  }

  @Test
  void anotherPrincipalEndpointMethodToolOrArgumentsIsNoState() {
    String s = seal(1, T0.plusSeconds(600));
    assertThat(open(s, "pr_2", "ep_1", "tools/call", "sql_execute", digest)).as("principal").isNull();
    assertThat(open(s, "pr_1", "ep_2", "tools/call", "sql_execute", digest)).as("endpoint").isNull();
    assertThat(open(s, "pr_1", "ep_1", "prompts/get", "sql_execute", digest)).as("method").isNull();
    assertThat(open(s, "pr_1", "ep_1", "tools/call", "sql_query", digest)).as("tool").isNull();
    String otherArgs = RequestStateCodec.argumentsDigest("sql_execute", JSON.readTree(
        "{\"connection\":\"dev-db\",\"sql\":\"DROP TABLE t\"}"));
    assertThat(open(s, "pr_1", "ep_1", "tools/call", "sql_execute", otherArgs)).as("arguments").isNull();
  }

  @Test
  void anExpiredStateIsNoState() {
    String s = seal(1, T0.plusSeconds(60));
    clock.advance(Duration.ofSeconds(60));
    assertThat(open(s, "pr_1", "ep_1", "tools/call", "sql_execute", digest)).isNotNull();
    clock.advance(Duration.ofMillis(1));
    assertThat(open(s, "pr_1", "ep_1", "tools/call", "sql_execute", digest)).isNull();
  }

  @Test
  void garbageIsNoStateAndNeverAnException() {
    for (String g : new String[] {null, "", ".", "abc", "a.b.c", "!!!.???", "e30.", "e30.AAAA",
        "x".repeat(5_000) + ".y"}) {
      assertThat(open(g, "pr_1", "ep_1", "tools/call", "sql_execute", digest)).as(String.valueOf(g)).isNull();
    }
    // A MAC-valid body that isn't the codec's shape (v != 1, no approval) is no state either.
    HmacKeys k = keys("k1");
    byte[] body = "{\"v\":2}".getBytes(StandardCharsets.UTF_8);
    String wrongVersion = Base64.getUrlEncoder().withoutPadding().encodeToString(body) + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(k.mac(HmacKeys.Purpose.REQUEST_STATE, body));
    assertThat(open(wrongVersion, "pr_1", "ep_1", "tools/call", "sql_execute", digest)).isNull();
    byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);
    String notJsonState = Base64.getUrlEncoder().withoutPadding().encodeToString(notJson) + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(k.mac(HmacKeys.Purpose.REQUEST_STATE, notJson));
    assertThat(open(notJsonState, "pr_1", "ep_1", "tools/call", "sql_execute", digest)).isNull();
  }

  @Test
  void theArgumentsDigestIgnoresKeyOrderButNotValues() {
    String a = RequestStateCodec.argumentsDigest("t", JSON.readTree("{\"a\":1,\"b\":{\"c\":2,\"d\":3}}"));
    String b = RequestStateCodec.argumentsDigest("t", JSON.readTree("{\"b\":{\"d\":3,\"c\":2},\"a\":1}"));
    assertThat(a).isEqualTo(b).hasSize(64);
    assertThat(RequestStateCodec.argumentsDigest("t", JSON.readTree("{\"a\":2,\"b\":{\"c\":2,\"d\":3}}")))
        .isNotEqualTo(a);
    assertThat(RequestStateCodec.argumentsDigest("u", JSON.readTree("{\"a\":1,\"b\":{\"c\":2,\"d\":3}}")))
        .isNotEqualTo(a);
    assertThat(RequestStateCodec.argumentsDigest("t", null))
        .isEqualTo(RequestStateCodec.argumentsDigest("t", JSON.readTree("{}")));
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private String seal(int round, Instant expires) {
    return codec.seal("ap_1", "pr_1", "ep_1", "tools/call", "sql_execute", digest, round, T0, expires);
  }

  private RequestStateCodec.State open(String s, String principal, String endpoint, String method, String tool,
      String d) {
    return codec.open(s, principal, endpoint, method, tool, d).orElse(null);
  }

  /** HMAC-SHA256 under a key derived from a name and the purpose, as the secrets module does from the keyset. */
  static HmacKeys keys(String name) {
    return (purpose, data) -> {
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((name + "/" + purpose.info()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data);
      } catch (java.security.GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    };
  }

  /** A clock the test moves. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    void set(Instant at) {
      now = at;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
