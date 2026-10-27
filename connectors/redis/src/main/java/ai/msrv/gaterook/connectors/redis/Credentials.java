package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import redis.clients.jedis.RedisCredentials;

/**
 * The login of one call: the form's ACL user and the stored password, or both from a short-lived login (Vault's Redis
 * database engine: {@code SecretResolver.meta(...).source() == "dynamic"}). The password is a copy the pool owns and
 * {@link #close()} zeroes; resolving it put every form of it into the call's taint, so an echo of it is masked.
 *
 * <p>Jedis asks {@link #asRedisCredentials()} on every physical connection, which is why the pool keeps a copy: the
 * call's resolver is closed when the call ends, and the next connection may open during another call.
 *
 * @param user     the ACL user, or {@code null} for {@code AUTH <password>} (the default user)
 * @param password the password, empty without one (no {@code AUTH} at all)
 * @param version  changes with every new value or lease: a pool built on another version is replaced
 * @param notAfter when a short-lived login expires, or {@code null}
 */
record Credentials(String user, char[] password, String version, Instant notAfter) implements AutoCloseable {

  static Credentials resolve(RedisSettings s, SecretResolver secrets) throws SecretUnavailableException {
    String field = RedisSettings.PASSWORD;
    if (secrets == null || !secrets.isSet(field)) {
      return new Credentials(s.user(), new char[0], "none", null);
    }
    SecretResolver.Meta meta = secrets.meta(field);
    if ("dynamic".equals(meta.source())) {
      try (SecretResolver.Login login = secrets.login(field)) {
        return new Credentials(login.username(), login.password().chars().clone(), login.version(),
            login.notAfter());
      }
    }
    try (SecretResolver.Secret secret = secrets.secret(field)) {
      return new Credentials(s.user(), secret.chars().clone(), secret.version(), secret.notAfter().orElse(null));
    }
  }

  boolean hasPassword() {
    return password.length > 0;
  }

  /** The user the server authenticates: {@code default} without a name. */
  String userOrDefault() {
    return user == null || user.isEmpty() ? "default" : user;
  }

  /** What Jedis sends with {@code HELLO … AUTH}; no password means no {@code AUTH}. */
  RedisCredentials asRedisCredentials() {
    String u = user;
    char[] p = hasPassword() ? password : null;
    return new RedisCredentials() {
      @Override
      public String getUser() {
        return u;
      }

      @Override
      public char[] getPassword() {
        return p;
      }

      @Override
      public String toString() {
        return "RedisCredentials[user=" + u + ", password=‹secret›]";
      }
    };
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
    return o instanceof Credentials c && Objects.equals(user, c.user) && Objects.equals(version, c.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(user, version);
  }
}
