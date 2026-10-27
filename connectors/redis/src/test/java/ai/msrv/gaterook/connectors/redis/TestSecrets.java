package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** The secrets of one test connection: a password by field (with a version), or a dynamic login. */
final class TestSecrets implements SecretResolver {

  private final Map<String, String> values = new HashMap<>();
  private final Map<String, String> versions = new HashMap<>();
  private String dynamicUser;
  private String dynamicPassword;
  private String dynamicVersion;
  final AtomicInteger reads = new AtomicInteger();

  TestSecrets with(String field, String value) {
    return with(field, value, "v1");
  }

  TestSecrets with(String field, String value, String version) {
    values.put(field, value);
    versions.put(field, version);
    return this;
  }

  static TestSecrets password(String value) {
    return new TestSecrets().with(RedisSettings.PASSWORD, value);
  }

  /** Vault-style dynamic credentials for {@link RedisSettings#PASSWORD}. */
  TestSecrets dynamic(String user, String password, String version) {
    this.dynamicUser = user;
    this.dynamicPassword = password;
    this.dynamicVersion = version;
    return this;
  }

  @Override
  public Secret secret(String field) throws SecretUnavailableException {
    String value = values.get(field);
    if (value == null) {
      throw new SecretUnavailableException(field + " is not set", null);
    }
    reads.incrementAndGet();
    return secret(value, versions.get(field));
  }

  private static Secret secret(String value, String version) {
    return new Secret() {
      private final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      private final char[] chars = value.toCharArray();

      @Override public char[] chars() { return chars; }
      @Override public byte[] bytes() { return bytes; }
      @Override public String unsafeString() { return value; }
      @Override public String version() { return version; }
      @Override public Optional<Instant> notAfter() { return Optional.empty(); }

      @Override
      public void close() {
        Arrays.fill(bytes, (byte) 0);
        Arrays.fill(chars, '\0');
      }

      @Override
      public String toString() {
        return "‹secret›";
      }
    };
  }

  @Override
  public Login login(String field) throws SecretUnavailableException {
    if (dynamicUser == null) {
      throw new SecretUnavailableException("no dynamic login here", null);
    }
    reads.incrementAndGet();
    return new Login(dynamicUser, secret(dynamicPassword, dynamicVersion), dynamicVersion,
        Instant.now().plusSeconds(3_600));
  }

  @Override
  public boolean isSet(String field) {
    return values.containsKey(field) || (dynamicUser != null && field.equals(RedisSettings.PASSWORD));
  }

  @Override
  public Meta meta(String field) {
    if (dynamicUser != null && field.equals(RedisSettings.PASSWORD)) {
      return new Meta("dynamic", dynamicVersion, Instant.now().plusSeconds(3_600));
    }
    return new Meta("local", versions.get(field), null);
  }

  @Override
  public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException {
    throw new SecretUnavailableException("no SSH here", null);
  }
}
