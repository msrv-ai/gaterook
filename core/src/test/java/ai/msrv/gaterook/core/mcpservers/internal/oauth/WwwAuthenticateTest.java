package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The 401 challenges real MCP servers send (RFC 7235 grammar: several challenges, quoting, token68). */
class WwwAuthenticateTest {

  @Test
  void theBearerChallengeOfAnMcpServerNamesItsResourceMetadataAndScope() {
    WwwAuthenticate w = WwwAuthenticate.parse("Bearer realm=\"OAuth\", resource_metadata=\"https://mcp.example.com/"
        + ".well-known/oauth-protected-resource/mcp\", scope=\"read write\", error=\"invalid_token\"");
    assertThat(w.bearer()).isTrue();
    assertThat(w.resourceMetadata()).isEqualTo("https://mcp.example.com/.well-known/oauth-protected-resource/mcp");
    assertThat(w.scope()).isEqualTo("read write");
    assertThat(w.error()).isEqualTo("invalid_token");
  }

  @Test
  void anotherSchemeBeforeBearerAndTokensWithoutQuotesAreRead() {
    WwwAuthenticate w = WwwAuthenticate.parse("Negotiate abc==, Basic realm=\"x\", BEARER Error=insufficient_scope, "
        + "Scope=\"files:read\"");
    assertThat(w.error()).isEqualTo("insufficient_scope");
    assertThat(w.scope()).isEqualTo("files:read");
  }

  @Test
  void theFirstBearerChallengeAmongSeveralHeadersCounts() {
    WwwAuthenticate w = WwwAuthenticate.parse(List.of("Basic realm=\"a\"", "Bearer resource_metadata=\"https://a/m\"",
        "Bearer resource_metadata=\"https://b/m\""));
    assertThat(w.resourceMetadata()).isEqualTo("https://a/m");
  }

  @Test
  void quotedPairsAreUndoneAndParametersOfAnotherChallengeDontLeakIn() {
    WwwAuthenticate w = WwwAuthenticate.parse("Bearer realm=\"say \\\"hi\\\", ok\", scope=\"a\", Basic realm=\"b\"");
    assertThat(w.params()).containsEntry("realm", "say \"hi\", ok").containsEntry("scope", "a").hasSize(2);
  }

  @Test
  void bearerAloneIsAChallengeWithoutParametersAndOtherSchemesAreNone() {
    assertThat(WwwAuthenticate.parse("Bearer").bearer()).isTrue();
    assertThat(WwwAuthenticate.parse("Bearer").resourceMetadata()).isNull();
    assertThat(WwwAuthenticate.parse("Basic realm=\"x\"").bearer()).isFalse();
    assertThat(WwwAuthenticate.parse((String) null).bearer()).isFalse();
    assertThat(WwwAuthenticate.parse("=,,= \"").bearer()).isFalse();
  }
}
