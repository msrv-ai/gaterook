package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.HmacKeys.Purpose;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.What;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Contracts of the platform SPI types that other modules implement or call. */
class PlatformSpiTest {

  @Test
  void callerNeedsAPrincipal() {
    Caller caller = new Caller(Caller.Kind.BRIDGE, "gr_Cx7q2LmA", "codex-laptop", "codex", Ingress.LOCAL, "127.0.0.1");

    assertThat(caller.principalId()).isEqualTo("gr_Cx7q2LmA");
    assertThatThrownBy(() -> new Caller(Caller.Kind.BRIDGE, " ", "x", null, Ingress.LOCAL, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Caller(null, "p", "x", null, Ingress.LOCAL, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void authenticationRequestNeverPrintsTheToken() {
    String token = "gwb_" + "Q".repeat(43);
    var request = new Authenticator.Request(Ingress.LOCAL, "dev", token, "127.0.0.1");

    assertThat(request.toString()).doesNotContain(token).doesNotContain("QQQQ").contains("dev", "‹redacted›");
    assertThat(request.bearerToken()).isEqualTo(token);
  }

  @Test
  void aPrincipalNobodyVouchesForIsRevoked() {
    PrincipalStatus tokens = new PrincipalStatus() {
      @Override
      public boolean supports(Caller.Kind kind) {
        return kind == Caller.Kind.LOCAL_TOKEN || kind == Caller.Kind.BRIDGE;
      }

      @Override
      public boolean revoked(Caller.Kind kind, String principalId) {
        return principalId.startsWith("revoked");
      }
    };
    java.util.List<PrincipalStatus> all = java.util.List.of(tokens);

    assertThat(PrincipalStatus.revoked(all, Caller.Kind.LOCAL_TOKEN, "tok_1")).isFalse();
    assertThat(PrincipalStatus.revoked(all, Caller.Kind.BRIDGE, "revoked-grant")).isTrue();
    // No bean answers for OAuth grants (before K3): fail closed. The playground is the person at this computer.
    assertThat(PrincipalStatus.revoked(all, Caller.Kind.OAUTH, "og_1")).isTrue();
    assertThat(PrincipalStatus.revoked(java.util.List.of(), Caller.Kind.PLAYGROUND, "playground")).isFalse();
  }

  @Test
  void setupChangeIdsMatchTheColumnValues() {
    assertThat(What.RISK_WARNING.id()).isEqualTo("risk-warning");
    assertThat(What.HOST_KEY.id()).isEqualTo("host-key");
    assertThat(What.MCP_SERVER.id()).isEqualTo("mcp-server");
    assertThat(What.TUNNEL.id()).isEqualTo("tunnel");
    assertThatThrownBy(() -> new SetupChange(What.MODE, "", "Approval", "Off", null, null, "dev", "dev-vm", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hmacPurposesHaveDistinctVersionedInfos() {
    Set<String> infos = new HashSet<>();
    for (Purpose p : Purpose.values()) {
      assertThat(p.info()).matches("[a-z-]+/v\\d+");
      assertThat(infos.add(p.info())).as(p.name()).isTrue();
    }
  }

  @Test
  void verifyComparesInConstantTimeAndRejectsMissingMacs() {
    HmacKeys keys = (purpose, data) -> {
      byte[] out = Arrays.copyOf(data, data.length + 1);
      out[data.length] = (byte) purpose.ordinal();
      return out;
    };
    byte[] data = "ap_3kq7m2x9vt4p".getBytes(StandardCharsets.UTF_8);

    assertThat(keys.verify(Purpose.REQUEST_STATE, data, keys.mac(Purpose.REQUEST_STATE, data))).isTrue();
    assertThat(keys.verify(Purpose.ROW_MAC, data, keys.mac(Purpose.REQUEST_STATE, data))).isFalse();
    assertThat(keys.verify(Purpose.ROW_MAC, data, null)).isFalse();
  }
}
