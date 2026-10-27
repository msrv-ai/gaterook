package ai.msrv.gaterook.core.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The per-launch secret shared by Electron's main process and the core, and the request signature built on it
 * (ARCHITECTURE.md A21, §6). It reaches the core only through stdin; the renderer never sees it. Each {@code /api/**}
 * and {@code /internal/**} request carries
 *
 * <pre>X-Gateway-Auth: &lt;ts&gt;.&lt;nonce&gt;.&lt;mac&gt;</pre>
 *
 * where {@code ts} is the client's clock in epoch milliseconds, {@code nonce} 16–64 base64url characters used once,
 * and {@code mac} = base64url(HMAC-SHA256(secret, {@code METHOD|target|ts|nonce})) without padding; {@code target} is
 * the raw request target, path plus {@code ?query} when there is one. A process that squats the port never receives
 * the secret, only signatures it can't reuse: they expire in {@link #WINDOW} and the nonce is single-use (the replay
 * cache is the filter's).
 */
public final class LaunchSecret {

  /** The request header. */
  public static final String HEADER = "X-Gateway-Auth";

  /** Accepted clock difference between the signer and the core, either way. */
  public static final Duration WINDOW = Duration.ofSeconds(30);

  /** Shortest accepted key: 32 random bytes from {@code crypto.randomBytes(32)}. */
  public static final int MIN_KEY_BYTES = 32;

  private static final String ALGORITHM = "HmacSHA256";
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
  private static final Pattern MAC = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,15}");

  private final SecretKeySpec key;

  private LaunchSecret(byte[] key) {
    if (key.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException("A launch secret needs at least " + MIN_KEY_BYTES + " bytes");
    }
    this.key = new SecretKeySpec(key, ALGORITHM);
  }

  /** The secret from its base64url text (padding optional), as Electron writes it into the bootstrap line. */
  public static LaunchSecret fromBase64Url(String text) {
    Objects.requireNonNull(text, "text");
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("The launch secret is not base64url", e);
    }
    try {
      return new LaunchSecret(raw);
    } finally {
      Arrays.fill(raw, (byte) 0);
    }
  }

  /** The outcome of {@link #verify}. Every value but {@code VALID} is answered with the same 401. */
  public enum Verdict { VALID, MISSING, MALFORMED, EXPIRED, BAD_SIGNATURE }

  /** A parsed, verified header: the nonce is what the caller must remember to refuse a replay. */
  public record Verification(Verdict verdict, String nonce, long timestamp) {

    public boolean valid() {
      return verdict == Verdict.VALID;
    }
  }

  /**
   * Checks a request signature. Constant-time comparison; the nonce's single use is up to the caller, which must
   * record it only after a {@code VALID} verdict (so that unauthenticated requests can't fill the replay cache).
   */
  public Verification verify(String method, String target, String header, Instant now) {
    if (header == null || header.isEmpty()) {
      return new Verification(Verdict.MISSING, null, 0);
    }
    String[] parts = header.split("\\.", -1);
    if (parts.length != 3 || !TIMESTAMP.matcher(parts[0]).matches() || !NONCE.matcher(parts[1]).matches()
        || !MAC.matcher(parts[2]).matches()) {
      return new Verification(Verdict.MALFORMED, null, 0);
    }
    long timestamp = Long.parseLong(parts[0]);
    long skew = Math.abs(now.toEpochMilli() - timestamp);
    if (skew > WINDOW.toMillis()) {
      return new Verification(Verdict.EXPIRED, null, timestamp);
    }
    byte[] expected = mac(method, target, timestamp, parts[1]);
    byte[] presented;
    try {
      presented = Base64.getUrlDecoder().decode(parts[2]);
    } catch (IllegalArgumentException e) {
      return new Verification(Verdict.MALFORMED, null, timestamp);
    }
    if (!MessageDigest.isEqual(expected, presented)) {
      return new Verification(Verdict.BAD_SIGNATURE, null, timestamp);
    }
    return new Verification(Verdict.VALID, parts[1], timestamp);
  }

  /** The header value for a request; what Electron's main process computes (and tests and dev tools). */
  public String sign(String method, String target, long timestampMillis, String nonce) {
    if (!NONCE.matcher(nonce).matches()) {
      throw new IllegalArgumentException("A nonce is 16-64 base64url characters");
    }
    String mac = Base64.getUrlEncoder().withoutPadding().encodeToString(mac(method, target, timestampMillis, nonce));
    return timestampMillis + "." + nonce + "." + mac;
  }

  private byte[] mac(String method, String target, long timestamp, String nonce) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      String message = method + "|" + target + "|" + timestamp + "|" + nonce;
      return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      // HmacSHA256 is mandatory in every JDK: this can't happen.
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  @Override
  public String toString() {
    return "LaunchSecret[‹redacted›]";
  }
}
