package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import java.time.Instant;
import java.util.Arrays;

/**
 * The login of one call: the form's user and the stored password, or both from a short-lived login (Vault dynamic
 * credentials: {@code SecretResolver.meta(...).source() == "dynamic"}, report 04 §6.7). The password is a copy that
 * {@link #close()} zeroes; resolving it put every form of it into the call's taint.
 *
 * @param version   changes with every new value or lease: a pool built on another version is replaced
 * @param notAfter  when a short-lived login expires, or {@code null}
 */
record Credentials(String user, char[] password, String version, Instant notAfter) implements AutoCloseable {

  static Credentials resolve(ConnectionConfig cfg, SecretResolver secrets) throws SecretUnavailableException {
    String field = ConnectionSettings.PASSWORD;
    String user = cfg.string("/credentials/user");
    if (secrets == null || !secrets.isSet(field)) {
      // A server that trusts this machine: no password at all.
      return new Credentials(user, new char[0], "none", null);
    }
    SecretResolver.Meta meta = secrets.meta(field);
    if ("dynamic".equals(meta.source())) {
      try (SecretResolver.Login login = secrets.login(field)) {
        return new Credentials(login.username(), login.password().chars().clone(), login.version(), login.notAfter());
      }
    }
    try (SecretResolver.Secret secret = secrets.secret(field)) {
      return new Credentials(user, secret.chars().clone(), secret.version(),
          secret.notAfter().orElse(null));
    }
  }

  /** A copy whose password the pool owns and zeroes itself. */
  Credentials copy() {
    return new Credentials(user, password.clone(), version, notAfter);
  }

  @Override
  public void close() {
    Arrays.fill(password, '\0');
  }

  @Override
  public String toString() {
    return "Credentials[user=" + user + ", version=" + version + ", password=‹secret›]";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Credentials c && java.util.Objects.equals(user, c.user)
        && java.util.Objects.equals(version, c.version);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(user, version);
  }
}
