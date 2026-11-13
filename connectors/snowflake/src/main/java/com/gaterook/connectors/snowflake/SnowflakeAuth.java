package com.gaterook.connectors.snowflake;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.SecretUnavailableException;
import com.gaterook.connectors.support.Pem;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * How a call signs in to the SQL API: a JWT signed with the connection's RSA private key ({@code KEYPAIR_JWT}), or the
 * user's programmatic access token ({@code PROGRAMMATIC_ACCESS_TOKEN}), as {@code Authorization: Bearer}. Each call
 * makes its own JWT, and every token goes to the call's taint, so an echo of it in an answer is masked.
 *
 * <p>The JWT follows Snowflake's key-pair authentication: {@code iss} is
 * {@code <ACCOUNT>.<USER>.SHA256:<fingerprint>}, {@code sub} is {@code <ACCOUNT>.<USER>}, both in upper case, the
 * account without its region; the fingerprint is the Base64 of the SHA-256 of the public key's DER
 * (SubjectPublicKeyInfo), the value {@code DESCRIBE USER} shows as {@code RSA_PUBLIC_KEY_FP}. It lives a few minutes
 * longer than its call, and never more than 59 minutes (Snowflake accepts at most an hour).
 */
final class SnowflakeAuth {

  static final String KEYPAIR_JWT = "KEYPAIR_JWT";
  static final String PROGRAMMATIC_ACCESS_TOKEN = "PROGRAMMATIC_ACCESS_TOKEN";
  static final Duration MIN_LIFETIME = Duration.ofMinutes(5);
  static final Duration MAX_LIFETIME = Duration.ofMinutes(59);
  private static final Pattern TOKEN_TEXT = Pattern.compile("[\\x21-\\x7E]{1,8192}");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  private SnowflakeAuth() {}

  /**
   * A signed-in identity of one call.
   *
   * @param type        the {@code X-Snowflake-Authorization-Token-Type} it goes with
   * @param fingerprint {@code SHA256:…} of a key pair's public key, {@code null} for a token
   */
  record Credential(String token, String type, String fingerprint) {

    @Override
    public String toString() {
      return "Credential[" + type + "]";
    }
  }

  /** The credential of one call, tainted. */
  static Credential of(SnowflakeSettings s, SecretResolver secrets, ExecContext ctx) throws ConnectorException {
    Credential c = s.auth() == SnowflakeSettings.Auth.KEY_PAIR
        ? keyPair(s, secrets, ctx.clock().instant(), lifetime(ctx.remaining()))
        : token(secrets);
    ctx.taint(c.token());
    return c;
  }

  /** The call's remaining time and five minutes, at least five and at most 59 minutes. */
  static Duration lifetime(Duration remaining) {
    Duration wanted = remaining.plus(MIN_LIFETIME);
    return wanted.compareTo(MAX_LIFETIME) > 0 ? MAX_LIFETIME : wanted;
  }

  static Credential keyPair(SnowflakeSettings s, SecretResolver secrets, Instant now, Duration lifetime)
      throws ConnectorException {
    RSAPrivateCrtKey key = privateKey(secrets);
    String fingerprint = fingerprint(key);
    String subject = s.jwtAccount() + "." + s.user().toUpperCase(Locale.ROOT);
    ObjectNode header = JsonNodeFactory.instance.objectNode().put("alg", "RS256").put("typ", "JWT");
    ObjectNode claims = JsonNodeFactory.instance.objectNode()
        .put("iss", subject + "." + fingerprint)
        .put("sub", subject)
        .put("iat", now.getEpochSecond())
        .put("exp", now.plus(lifetime).getEpochSecond());
    String signed = URL.encodeToString(JSON.writeValueAsBytes(header)) + "."
        + URL.encodeToString(JSON.writeValueAsBytes(claims));
    try {
      Signature rs256 = Signature.getInstance("SHA256withRSA");
      rs256.initSign(key);
      rs256.update(signed.getBytes(StandardCharsets.US_ASCII));
      return new Credential(signed + "." + URL.encodeToString(rs256.sign()), KEYPAIR_JWT, fingerprint);
    } catch (GeneralSecurityException e) {
      throw ConnectorException.badInput("The private key can't sign a Snowflake sign-in: " + e.getMessage() + ".");
    }
  }

  /** The connection's RSA private key: PKCS#8 or PKCS#1 PEM, unencrypted, at least 2048 bits. */
  static RSAPrivateCrtKey privateKey(SecretResolver secrets) throws ConnectorException {
    PrivateKey key;
    try (SecretResolver.Secret pem = secrets.secret(SnowflakeSettings.PRIVATE_KEY)) {
      key = Pem.privateKey(pem.unsafeString());
    } catch (SecretUnavailableException e) {
      throw ConnectorException.unavailable("The private key of the connection isn't available: " + e.getMessage()
          + ".", null);
    } catch (IllegalArgumentException e) {
      // Pem's messages never quote the input.
      throw ConnectorException.badInput("The private key can't be used: " + e.getMessage() + ".");
    }
    if (!(key instanceof RSAPrivateCrtKey rsa)) {
      throw ConnectorException.badInput("Snowflake signs in with RSA key pairs; this private key is "
          + key.getAlgorithm() + ".");
    }
    if (rsa.getModulus().bitLength() < 2048) {
      throw ConnectorException.badInput("Snowflake needs an RSA key of at least 2048 bits; this one has "
          + rsa.getModulus().bitLength() + ".");
    }
    return rsa;
  }

  /** {@code SHA256:<Base64 of the SHA-256 of the public key's DER>}, as Snowflake shows RSA_PUBLIC_KEY_FP. */
  static String fingerprint(RSAPrivateCrtKey key) throws ConnectorException {
    try {
      byte[] der = KeyFactory.getInstance("RSA")
          .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent())).getEncoded();
      return "SHA256:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(der));
    } catch (GeneralSecurityException e) {
      throw ConnectorException.badInput("The public key of the private key can't be derived: " + e.getMessage()
          + ".");
    }
  }

  /** The programmatic access token, without the white space a paste may bring. */
  static Credential token(SecretResolver secrets) throws ConnectorException {
    try (SecretResolver.Secret secret = secrets.secret(SnowflakeSettings.TOKEN)) {
      String token = secret.unsafeString().strip();
      if (!TOKEN_TEXT.matcher(token).matches()) {
        throw ConnectorException.badInput("The programmatic access token isn't one: paste the token's secret as "
            + "Snowflake showed it, on one line.");
      }
      return new Credential(token, PROGRAMMATIC_ACCESS_TOKEN, null);
    } catch (SecretUnavailableException e) {
      throw ConnectorException.unavailable("The token of the connection isn't available: " + e.getMessage() + ".",
          null);
    }
  }
}
