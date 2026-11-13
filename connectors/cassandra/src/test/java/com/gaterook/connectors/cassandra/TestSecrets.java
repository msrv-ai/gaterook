package com.gaterook.connectors.cassandra;

import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.SecretUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** The secrets of one test connection, by field; a field set with {@link #login} reads its user from the store too. */
final class TestSecrets implements SecretResolver {

  private final Map<String, String> values = new HashMap<>();
  private final Map<String, String> users = new HashMap<>();
  private String version = "1";

  TestSecrets with(String field, String value) {
    values.put(field, value);
    return this;
  }

  /** A login a store holds: the user is a secret field of the connection, and {@code login} gives both. */
  TestSecrets login(String field, String user, String password) {
    values.put(field, password);
    users.put(field, user);
    return this;
  }

  TestSecrets version(String v) {
    version = v;
    return this;
  }

  static TestSecrets password(String password) {
    return new TestSecrets().with(CassandraSettings.PASSWORD, password);
  }

  @Override
  public Secret secret(String field) throws SecretUnavailableException {
    String value = values.get(field);
    if (value == null) {
      throw new SecretUnavailableException(field + " is not set", null);
    }
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
    };
  }

  @Override
  public Login login(String field) throws SecretUnavailableException {
    String user = users.get(field);
    if (user == null) {
      throw new SecretUnavailableException("no login in " + field, null);
    }
    return new Login(user, secret(field), version, null);
  }

  @Override
  public boolean isSet(String field) {
    if (field.equals(CassandraSettings.USER)) {
      return users.containsKey(CassandraSettings.PASSWORD);
    }
    return values.containsKey(field);
  }

  @Override
  public Meta meta(String field) {
    return new Meta("local", version, null);
  }

  @Override
  public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException {
    throw new SecretUnavailableException("no SSH here", null);
  }
}
