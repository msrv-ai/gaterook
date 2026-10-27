package ai.msrv.gaterook.connectors.cloudauth;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A resolver over a map of field → value, version "1". */
final class FakeSecrets implements SecretResolver {

  private final Map<String, String> values = new HashMap<>();

  FakeSecrets with(String field, String value) {
    values.put(field, value);
    return this;
  }

  @Override
  public Secret secret(String field) throws SecretUnavailableException {
    String value = values.get(field);
    if (value == null) {
      throw new SecretUnavailableException(field + " is not set", null);
    }
    return new Secret() {
      private final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

      @Override
      public char[] chars() {
        return value.toCharArray();
      }

      @Override
      public byte[] bytes() {
        return bytes;
      }

      @Override
      public String unsafeString() {
        return value;
      }

      @Override
      public String version() {
        return "1";
      }

      @Override
      public Optional<Instant> notAfter() {
        return Optional.empty();
      }

      @Override
      public void close() {
        java.util.Arrays.fill(bytes, (byte) 0);
      }
    };
  }

  @Override
  public Login login(String field) throws SecretUnavailableException {
    throw new SecretUnavailableException("no logins here", null);
  }

  @Override
  public boolean isSet(String field) {
    return values.containsKey(field);
  }

  @Override
  public Meta meta(String field) {
    return new Meta("local", "1", null);
  }

  @Override
  public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException {
    throw new SecretUnavailableException("no ssh CA", null);
  }
}
