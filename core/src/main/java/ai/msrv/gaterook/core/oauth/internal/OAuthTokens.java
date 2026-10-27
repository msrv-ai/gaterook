package ai.msrv.gaterook.core.oauth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Opaque secrets of the authorization servers (ARCHITECTURE.md A4): 256 random bits, base64url, with a prefix that
 * names their kind — {@code gwo_} access, {@code gwr_} refresh, {@code gwa_} authorization code, {@code gwc_} a
 * registered client id. Only {@code b64url(sha256)} of a token is ever stored; codes live in memory only.
 */
final class OAuthTokens {

  static final String ACCESS = "gwo_";
  static final String REFRESH = "gwr_";
  static final String CODE = "gwa_";
  static final String CLIENT = "gwc_";

  private static final SecureRandom RANDOM = new SecureRandom();

  /** The code a person compares on both screens: 6 of 32 unambiguous characters (30 bits), {@code XXX-XXX}. */
  static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  private OAuthTokens() {}

  static String random(String prefix) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** A shorter random id for clients people may see in a list ({@code gwc_} + 128 bits). */
  static String clientId() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return CLIENT + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String userCode() {
    char[] out = new char[7];
    for (int i = 0; i < 7; i++) {
      out[i] = i == 3 ? '-' : CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length()));
    }
    return new String(out);
  }

  /** What logs may show of a token: its kind and first 8 characters. */
  static String prefixOf(String token) {
    return token == null ? "none" : token.substring(0, Math.min(token.length(), 8)) + "…";
  }

  static boolean constantTimeEquals(String a, String b) {
    return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
        b.getBytes(StandardCharsets.US_ASCII));
  }
}
