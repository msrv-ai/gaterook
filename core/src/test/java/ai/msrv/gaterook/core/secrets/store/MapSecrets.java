package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A store's sign-in secrets for a test: field → value, version "1", closed like the real resolver. */
public final class MapSecrets implements SecretResolver, AutoCloseable {

  private final Map<String, String> values = new HashMap<>();
  private boolean closed;

  public MapSecrets with(String field, String value) {
    values.put(field, value);
    return this;
  }

  @Override
  public Secret secret(String field) throws SecretUnavailableException {
    String value = values.get(field);
    if (value == null || closed) {
      throw new SecretUnavailableException(field + " is not set", null);
    }
    char[] chars = value.toCharArray();
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return new Secret() {
      @Override
      public char[] chars() {
        return chars;
      }

      @Override
      public byte[] bytes() {
        return bytes;
      }

      @Override
      public String unsafeString() {
        return new String(chars);
      }

      @Override
      public String version() {
        return "v1";
      }

      @Override
      public Optional<Instant> notAfter() {
        return Optional.empty();
      }

      @Override
      public void close() {
        Arrays.fill(chars, '\0');
        Arrays.fill(bytes, (byte) 0);
      }
    };
  }

  @Override
  public Login login(String field) throws SecretUnavailableException {
    throw new SecretUnavailableException(field + " is not a login", null);
  }

  @Override
  public boolean isSet(String field) {
    return values.containsKey(field);
  }

  @Override
  public Meta meta(String field) {
    return values.containsKey(field) ? new Meta("local", "v1", null) : new Meta(null, null, null);
  }

  @Override
  public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException {
    throw new SecretUnavailableException("No SSH CA here", null);
  }

  @Override
  public void close() {
    closed = true;
  }
}
