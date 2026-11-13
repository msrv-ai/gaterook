package com.gaterook.core.mcpservers.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PkceTest {

  @Test
  void theS256ChallengeIsRfc7636sExample() {
    // RFC 7636, Appendix B.
    assertThat(Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  }

  @Test
  void aNewVerifierHas256BitsInBase64urlAndMatchesItsChallenge() {
    Pkce p = Pkce.create();
    assertThat(p.verifier()).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(p.challenge()).isEqualTo(Pkce.challenge(p.verifier()));
    assertThat(Pkce.create().verifier()).isNotEqualTo(p.verifier());
    assertThat(p.toString()).doesNotContain(p.verifier());
  }
}
