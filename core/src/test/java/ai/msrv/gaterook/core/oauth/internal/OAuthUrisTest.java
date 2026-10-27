package ai.msrv.gaterook.core.oauth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The URI and token rules of both authorization servers (report 02 §6–§7). */
class OAuthUrisTest {

  @Test
  void pkceIsS256ByTheRfc7636Vector() {
    String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    String challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    assertThat(OAuthUris.validChallenge(challenge)).isTrue();
    assertThat(OAuthUris.pkceS256(verifier, challenge)).isTrue();
    assertThat(OAuthUris.pkceS256(verifier + "x", challenge)).isFalse();
    // plain: the verifier itself as the challenge never passes.
    assertThat(OAuthUris.pkceS256(verifier, verifier)).isFalse();
    assertThat(OAuthUris.validChallenge(verifier + "A")).isFalse();
    assertThat(OAuthUris.validChallenge("short")).isFalse();
    assertThat(OAuthUris.validChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-c=")).isFalse();
    assertThat(OAuthUris.pkceS256("too-short", challenge)).isFalse();
    assertThat(OAuthUris.pkceS256(null, challenge)).isFalse();
    assertThat(OAuthUris.pkceS256("a".repeat(129), challenge)).isFalse();
  }

  @Test
  void resourcesAreCompareInCanonicalForm() {
    assertThat(OAuthUris.canonicalResource("https://GW-Smoke.trycloudflare.com:443/mcp/stage-readonly/"))
        .contains("https://gw-smoke.trycloudflare.com/mcp/stage-readonly");
    assertThat(OAuthUris.canonicalResource("http://127.0.0.1:7465/mcp/dev")).contains("http://127.0.0.1:7465/mcp/dev");
    assertThat(OAuthUris.canonicalResource("http://localhost:80/mcp/dev")).contains("http://localhost/mcp/dev");
    for (String bad : new String[] {"https://h/mcp/a?x=1", "https://h/mcp/a#f", "https://u@h/mcp/a", "ftp://h/x",
        "/mcp/a", "mcp", "", "https://" + "a".repeat(600)}) {
      assertThat(OAuthUris.canonicalResource(bad)).as(bad).isEmpty();
    }
    assertThat(OAuthUris.canonicalResource(null)).isEmpty();
  }

  @Test
  void clientIdsAreMetadataUrlsOnTheirOwnTerms() {
    assertThat(OAuthUris.clientIdUrl("https://claude.ai/oauth/mcp-oauth-client-metadata")).isPresent();
    for (String bad : new String[] {"http://claude.ai/oauth/mcp-oauth-client-metadata", "https://claude.ai/",
        "https://claude.ai", "https://claude.ai:8443/x", "https://u@claude.ai/x", "https://claude.ai/x?y=1",
        "https://claude.ai/x#y", "https://claude.ai/a/../b", "https://claude.ai/a/./b", "https://claude.ai/%2e%2e/b",
        "gwc_abc", "https://claude.ai/" + "a".repeat(600)}) {
      assertThat(OAuthUris.clientIdUrl(bad)).as(bad).isEmpty();
    }
    assertThat(OAuthUris.clientIdUrl("https://localhost:8443/meta", true)).as("a test's document server").isPresent();
  }

  @Test
  void redirectsMatchExactlyOrAsLoopbackOnAnyPort() {
    assertThat(OAuthUris.redirectMatches("https://claude.ai/api/mcp/auth_callback",
        "https://claude.ai/api/mcp/auth_callback")).isTrue();
    assertThat(OAuthUris.redirectMatches("https://claude.ai/api/mcp/auth_callback",
        "https://claude.ai/api/mcp/auth_callback/")).isFalse();
    assertThat(OAuthUris.redirectMatches("https://claude.ai/api/mcp/auth_callback",
        "https://claude.ai.evil.example/api/mcp/auth_callback")).isFalse();
    assertThat(OAuthUris.redirectMatches("http://127.0.0.1:33418/callback", "http://127.0.0.1:51004/callback"))
        .isTrue();
    assertThat(OAuthUris.redirectMatches("http://127.0.0.1/callback", "http://127.0.0.1:51004/callback")).isTrue();
    assertThat(OAuthUris.redirectMatches("http://127.0.0.1:33418/callback", "http://127.0.0.1:51004/other"))
        .isFalse();
    assertThat(OAuthUris.redirectMatches("http://127.0.0.1:33418/callback", "http://localhost:51004/callback"))
        .as("same host only").isFalse();
    assertThat(OAuthUris.redirectMatches("http://127.0.0.1:33418/callback", "http://10.0.0.1:33418/callback"))
        .isFalse();
    assertThat(OAuthUris.redirectMatches("cursor://anysphere.cursor-mcp/oauth/callback",
        "cursor://anysphere.cursor-mcp/oauth/callback")).isTrue();
    assertThat(OAuthUris.loopback("https://127.0.0.1/cb")).as("loopback is http").isFalse();
    assertThat(OAuthUris.loopback("http://[::1]:5000/cb")).isTrue();
  }

  @Test
  void queriesAreAppendedEncoded() {
    assertThat(OAuthUris.withQuery("https://claude.ai/api/mcp/auth_callback", "code", "gwa_a b", "state", null,
        "iss", "https://h")).isEqualTo("https://claude.ai/api/mcp/auth_callback?code=gwa_a+b&iss=https%3A%2F%2Fh");
    assertThat(OAuthUris.withQuery("http://127.0.0.1:5/cb?x=1", "code", "c"))
        .isEqualTo("http://127.0.0.1:5/cb?x=1&code=c");
    assertThat(OAuthUris.hostOf("cursor://anysphere.cursor-mcp/oauth/callback")).isEqualTo("anysphere.cursor-mcp");
    assertThat(OAuthUris.hostOf("https://Claude.AI/x")).isEqualTo("claude.ai");
  }

  @Test
  void tokensAreOpaqueAndOnlyTheirHashIsKept() {
    String access = OAuthTokens.random(OAuthTokens.ACCESS);
    assertThat(access).startsWith("gwo_").hasSize(4 + 43);
    assertThat(OAuthTokens.random(OAuthTokens.REFRESH)).startsWith("gwr_");
    assertThat(OAuthTokens.clientId()).startsWith("gwc_").hasSize(4 + 22);
    assertThat(OAuthTokens.hash(access)).hasSize(43).doesNotContain(access.substring(4, 12));
    assertThat(OAuthTokens.prefixOf(access)).hasSize(9).startsWith("gwo_");
    assertThat(OAuthTokens.constantTimeEquals("a", "a")).isTrue();
    assertThat(OAuthTokens.constantTimeEquals("a", null)).isFalse();
  }

  @Test
  void userCodesUseTheUnambiguousAlphabet() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 2000; i++) {
      String code = OAuthTokens.userCode();
      assertThat(code).matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{3}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{3}");
      seen.add(code);
    }
    assertThat(seen.size()).as("30 bits: no repeats in 2000").isGreaterThan(1990);
    assertThat(OAuthTokens.CODE_ALPHABET).doesNotContain("0", "1", "O", "I");
  }

  @Test
  void documentsComeOnlyFromGlobalAddresses() throws Exception {
    for (String bad : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
        "100.64.0.1", "0.0.0.0", "224.0.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "198.18.0.1", "::1",
        "fd00::1", "fe80::1", "2001:db8::1", "::ffff:127.0.0.1", "2002:7f00:1::1"}) {
      assertThat(SpecialUse.global(InetAddress.ofLiteral(bad))).as(bad).isFalse();
    }
    for (String good : new String[] {"160.79.104.10", "104.16.0.1", "2606:4700::6810:84e5"}) {
      assertThat(SpecialUse.global(InetAddress.ofLiteral(good))).as(good).isTrue();
    }
  }

  @Test
  void aCallerIsCountedByItsIpv4AddressOrItsIpv6Slash64() {
    assertThat(RateWindow.addressKey("203.0.113.24")).isEqualTo("203.0.113.24");
    assertThat(RateWindow.addressKey("2001:db8:1:2::1")).isEqualTo(RateWindow.addressKey("2001:db8:1:2:ffff::9"))
        .isEqualTo(RateWindow.addressKey("2001:0db8:0001:0002:0:0:0:abcd"));
    assertThat(RateWindow.addressKey("2001:db8:1:2::1")).isNotEqualTo(RateWindow.addressKey("2001:db8:1:3::1"));
    assertThat(RateWindow.addressKey(null)).isNull();
    assertThat(RateWindow.addressKey("not:an:address")).isEqualTo("not:an:address");
  }

  @Test
  void cacheControlIsBoundedToAMinuteAndADay() {
    assertThat(CimdResolver.cacheFor(null)).hasSeconds(60);
    assertThat(CimdResolver.cacheFor("no-store")).hasSeconds(60);
    assertThat(CimdResolver.cacheFor("public, max-age=5")).hasSeconds(60);
    assertThat(CimdResolver.cacheFor("public, max-age=3600")).hasSeconds(3600);
    assertThat(CimdResolver.cacheFor("max-age=31536000")).hasHours(24);
    assertThat(CimdResolver.cacheFor("s-maxage=10, max-age=600")).hasSeconds(600);
  }
}
