package com.gaterook.core.mcpservers.internal.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Proof Key for Code Exchange with S256 (RFC 7636), and the other random values of a sign-in: a verifier and a
 * {@code state} of 256 bits each, base64url without padding.
 *
 * @param verifier  the {@code code_verifier}, sent only to the token endpoint
 * @param challenge {@code BASE64URL(SHA256(verifier))}, sent with the authorization request
 */
public record Pkce(String verifier, String challenge) {

  private static final SecureRandom RANDOM = new SecureRandom();

  public static Pkce create() {
    String verifier = random(32);
    return new Pkce(verifier, challenge(verifier));
  }

  /** The S256 challenge of {@code verifier}. */
  public static String challenge(String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code bytes} random bytes, base64url without padding. */
  public static String random(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  @Override
  public String toString() {
    return "Pkce[‹redacted›]";
  }
}
