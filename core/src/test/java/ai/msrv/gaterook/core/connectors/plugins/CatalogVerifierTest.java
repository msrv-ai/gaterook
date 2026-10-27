package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The catalog's Ed25519 signature (ARCHITECTURE.md §9 "Plugin and catalogue signatures", report 11 §8.2): a signed index is
 * accepted, a flipped byte, an unknown key, a rollback (sequence going down), an expired index and a revoked artifact
 * are all refused. A golden file signed by {@code openssl pkeyutl -sign -rawin} proves the core reads real catalog
 * signatures, not only its own.
 */
class CatalogVerifierTest {

  private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void aSignedIndexIsAcceptedAndASequenceCantGoBackwards() throws Exception {
    KeyPair pair = ed25519();
    byte[] index = index(412);
    byte[] sig = sign(pair, index);
    CatalogVerifier verifier = new CatalogVerifier(Map.of("test-key", raw(pair)), NOW);

    CatalogVerifier.Verified verified = verifier.verify(index, sig("test-key", sig), -1);
    assertThat(verified.sequence()).isEqualTo(412);
    assertThat(verified.isRevoked("dead00000000000000000000000000000000000000000000000000000000beef")).isTrue();
    assertThat(verified.isRevoked("c4e19a07")).isFalse();

    // The same key, an older sequence than the last good one: a rollback.
    byte[] older = index(300);
    assertThatThrownBy(() -> verifier.verify(older, sig("test-key", sign(pair, older)), 412))
        .isInstanceOf(CatalogVerifier.RejectedException.class).hasMessageContaining("rollback");
  }

  @Test
  void aFlippedByteAWrongKeyAnExpiredIndexAreRefused() throws Exception {
    KeyPair pair = ed25519();
    byte[] index = index(412);
    byte[] sig = sign(pair, index);
    CatalogVerifier verifier = new CatalogVerifier(Map.of("test-key", raw(pair)), NOW);

    byte[] tampered = index.clone();
    tampered[tampered.length / 2] ^= 0x01;
    assertThatThrownBy(() -> verifier.verify(tampered, sig("test-key", sig), -1))
        .isInstanceOf(CatalogVerifier.RejectedException.class).hasMessageContaining("doesn't match");

    assertThatThrownBy(() -> verifier.verify(index, sig("another-key", sig), -1))
        .isInstanceOf(CatalogVerifier.RejectedException.class).hasMessageContaining("unknown key");

    CatalogVerifier future = new CatalogVerifier(Map.of("test-key", raw(pair)),
        Clock.fixed(Instant.parse("2127-01-01T00:00:00Z"), ZoneOffset.UTC));
    assertThatThrownBy(() -> future.verify(index, sig("test-key", sig), -1))
        .isInstanceOf(CatalogVerifier.RejectedException.class).hasMessageContaining("expired");
  }

  @Test
  void anIndexSignedByOpensslWithRawinIsAccepted() throws Exception {
    // The raw 32-byte public key of the committed golden key (its openssl private key is not in the repo).
    byte[] key = Base64.getDecoder().decode("BIg8BwxCm0xFZfmuovO/seAYfX2ID9dzmASzWiOt1EA=");
    byte[] sig = Base64.getDecoder().decode(
        "nWCeBQibvbSk542+zOl1cMHA3XjAFFu9WZdL3yuCI8CGjy7GKwcUvmRkA3qd/0lehjS2bJ8UVjLtc6t7ayqiAg==");
    byte[] index = Files.readAllBytes(resource("openssl-index.json"));
    CatalogVerifier verifier = new CatalogVerifier(Map.of("catalog-2026-1", key), NOW);

    CatalogVerifier.Verified verified = verifier.verify(index, sig("catalog-2026-1", sig), -1);
    assertThat(verified.sequence()).isEqualTo(412);
    assertThat(verified.index().path("entries").get(0).path("id").asString()).isEqualTo("jira-service-desk");
  }

  /**
   * The catalog's own signing script (step K6): {@code catalog/sign-index.mjs} signed this golden copy of the
   * catalog's source index with a throwaway key (its private half was never stored). The core reads the script's
   * signature file, the key as {@code gateway.plugins.catalog-keys} takes it (base64url), and every entry of the index.
   */
  @Test
  void anIndexSignedByTheCatalogsScriptIsAccepted() throws Exception {
    byte[] index = Files.readAllBytes(resource("sign-index/index.json"));
    byte[] sigFile = Files.readAllBytes(resource("sign-index/index.json.sig"));
    byte[] key = Base64.getUrlDecoder().decode(Files.readString(resource("sign-index/public-key.txt")).strip());
    CatalogVerifier verifier = new CatalogVerifier(Map.of("catalog-golden", key), NOW);

    CatalogVerifier.Verified verified = verifier.verify(index, CatalogVerifier.signature(sigFile), -1);
    assertThat(verified.sequence()).isEqualTo(412);
    assertThat(verified.expiresAt()).isEqualTo(Instant.parse("2027-01-24T00:00:00Z"));
    assertThat(verified.index().path("entries")).extracting(e -> e.path("id").asString()).containsExactly(
        "jira-service-desk", "clickhouse", "rabbitmq", "pg-slow-queries", "github-mcp");

    byte[] flipped = index.clone();
    flipped[100] ^= 0x01;
    assertThatThrownBy(() -> verifier.verify(flipped, CatalogVerifier.signature(sigFile), -1))
        .isInstanceOf(CatalogVerifier.RejectedException.class).hasMessageContaining("doesn't match");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static KeyPair ed25519() throws Exception {
    return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  /** The 32 raw bytes of an Ed25519 public key: the tail of its X.509/SPKI encoding (12-byte prefix + 32 bytes). */
  private static byte[] raw(KeyPair pair) {
    byte[] spki = pair.getPublic().getEncoded();
    return java.util.Arrays.copyOfRange(spki, spki.length - 32, spki.length);
  }

  private static byte[] sign(KeyPair pair, byte[] message) throws Exception {
    Signature s = Signature.getInstance("Ed25519");
    s.initSign(pair.getPrivate());
    s.update(message);
    return s.sign();
  }

  private static CatalogVerifier.Signature sig(String keyId, byte[] sig) {
    return new CatalogVerifier.Signature(keyId, "Ed25519", sig);
  }

  private static byte[] index(int sequence) {
    return ("{\"schema\":1,\"sequence\":" + sequence + ",\"generatedAt\":\"2026-10-22T09:30:00Z\","
        + "\"expiresAt\":\"2126-11-21T09:30:00Z\",\"entries\":[],"
        + "\"revoked\":[{\"sha256\":\"dead00000000000000000000000000000000000000000000000000000000beef\"}]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static Path resource(String name) throws Exception {
    return Path.of(CatalogVerifierTest.class.getResource("/plugins/catalog/" + name).toURI());
  }
}
