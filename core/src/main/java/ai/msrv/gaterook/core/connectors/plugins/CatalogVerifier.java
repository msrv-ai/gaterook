package ai.msrv.gaterook.core.connectors.plugins;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;

import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plugin catalog's Ed25519 signature (ARCHITECTURE.md §9 "Plugin and catalogue signatures", report 11 §4.4): the
 * signature is over the <em>raw bytes</em> of {@code index.json}, so the index is verified before it is parsed. The
 * trusted keys are built in (a release ships them); a key id the index names but we don't trust is rejected. After the
 * signature checks out, the index must not go backwards ({@code sequence} ≥ the last good one) and must not be expired
 * ({@code expiresAt} in the future).
 *
 * <p>The catalog content itself is step K6; this verifies it and gates install-from-catalog.
 */
public final class CatalogVerifier {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The X.509/SPKI prefix of a 32-byte Ed25519 public key ({@code AlgorithmIdentifier} + bit-string header). */
  private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

  /** What a verified index is. */
  public record Verified(long sequence, Instant generatedAt, Instant expiresAt, JsonNode index, List<Revoked> revoked) {

    /** Whether {@code sha256} (a plugin JAR's) was revoked. */
    public boolean isRevoked(String sha256) {
      return revoked.stream().anyMatch(r -> r.sha256().equalsIgnoreCase(sha256));
    }
  }

  /** A revoked artifact. */
  public record Revoked(String sha256, String reason) {}

  /** The signature file {@code index.json.sig}. */
  public record Signature(String keyId, String alg, byte[] sig) {}

  /** Why an index was rejected (never trusted). */
  public static final class RejectedException extends Exception {
    private static final long serialVersionUID = 1L;

    public RejectedException(String message) {
      super(message);
    }
  }

  private final Map<String, byte[]> trustedKeys;
  private final Clock clock;

  /**
   * @param trustedKeys the raw 32-byte Ed25519 public keys by key id (a release ships them; tests pass their own)
   */
  public CatalogVerifier(Map<String, byte[]> trustedKeys, Clock clock) {
    this.trustedKeys = Map.copyOf(trustedKeys);
    this.clock = clock;
  }

  /** Parses {@code index.json.sig}. */
  public static Signature signature(byte[] sigJson) throws RejectedException {
    try {
      JsonNode n = JSON.readTree(sigJson);
      String keyId = n.path("keyId").asString(null);
      String alg = n.path("alg").asString("Ed25519");
      String sig = n.path("sig").asString(null);
      if (keyId == null || sig == null) {
        throw new RejectedException("The signature file has no keyId or sig.");
      }
      return new Signature(keyId, alg, Base64.getDecoder().decode(sig));
    } catch (RuntimeException e) {
      throw new RejectedException("The signature file isn't valid: " + e.getMessage());
    }
  }

  /**
   * Verifies {@code indexJson} against {@code sig} and checks the sequence and expiry.
   *
   * @param lastGoodSequence the sequence of the last index we accepted, or {@code -1}
   * @throws RejectedException on a wrong key, a bad signature, a rollback or an expired index
   */
  public Verified verify(byte[] indexJson, Signature sig, long lastGoodSequence) throws RejectedException {
    return verify(indexJson, sig, lastGoodSequence, false);
  }

  /**
   * As {@link #verify(byte[], Signature, long)}; with {@code allowExpired} an index past its {@code expiresAt} is
   * returned instead of refused — the cached catalog stays visible offline, but nothing installs from it (step K6).
   */
  public Verified verify(byte[] indexJson, Signature sig, long lastGoodSequence, boolean allowExpired)
      throws RejectedException {
    if (!"Ed25519".equalsIgnoreCase(sig.alg())) {
      throw new RejectedException("Unsupported catalog signature algorithm " + sig.alg() + ".");
    }
    byte[] raw = trustedKeys.get(sig.keyId());
    if (raw == null) {
      throw new RejectedException("The catalog is signed with an unknown key (" + sig.keyId() + ").");
    }
    if (!signatureHolds(indexJson, raw, sig.sig())) {
      throw new RejectedException("The catalog signature doesn't match its contents.");
    }
    JsonNode index;
    try {
      index = JSON.readTree(indexJson);
    } catch (RuntimeException e) {
      throw new RejectedException("The catalog isn't valid JSON: " + e.getMessage());
    }
    long sequence = index.path("sequence").asLong(-1);
    if (sequence < 0) {
      throw new RejectedException("The catalog has no sequence.");
    }
    if (sequence < lastGoodSequence) {
      throw new RejectedException("The catalog is older than the last one seen (sequence " + sequence + " < "
          + lastGoodSequence + "): it may be a rollback.");
    }
    Instant expiresAt = instant(index.path("expiresAt").asString(null));
    if (expiresAt == null) {
      throw new RejectedException("The catalog has no expiry.");
    }
    if (expiresAt.isBefore(clock.instant()) && !allowExpired) {
      throw new RejectedException("The catalog signature expired on " + expiresAt + ".");
    }
    List<Revoked> revoked = new java.util.ArrayList<>();
    for (JsonNode r : index.path("revoked")) {
      String sha = r.path("sha256").asString(null);
      if (sha != null) {
        revoked.add(new Revoked(sha, r.path("reason").asString("")));
      }
    }
    return new Verified(sequence, instant(index.path("generatedAt").asString(null)), expiresAt, index,
        List.copyOf(revoked));
  }

  /** Whether {@code sig} is a valid Ed25519 signature of {@code message} by the 32-byte key {@code raw}. */
  static boolean signatureHolds(byte[] message, byte[] raw, byte[] sig) {
    try {
      byte[] spki = new byte[SPKI_PREFIX.length + raw.length];
      System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
      System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, raw.length);
      PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
      java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(message);
      return verifier.verify(sig);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  private static Instant instant(String text) {
    try {
      return text == null ? null : Instant.parse(text);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
