package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.MongoCredential;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * The login of one call (report 05 §6 "Secrets"): the form's user and the stored password, or both from a short-lived
 * login (Vault's database engine: {@code SecretResolver.meta(...).source() == "dynamic"}). Resolving the password put
 * every form of it into the call's taint, so an echo of it in a document is masked. The password here is a copy that
 * {@link #close()} zeroes.
 *
 * @param version changes with every new value or lease: a cached client built on another version is replaced
 */
record MongoLogin(String user, char[] password, String version, Instant notAfter) implements AutoCloseable {

  static MongoLogin resolve(MongoSettings s, SecretResolver secrets) throws SecretUnavailableException {
    String field = MongoSettings.PASSWORD;
    if ("none".equals(s.mechanism())) {
      return new MongoLogin(null, new char[0], "none", null);
    }
    if (secrets == null || !secrets.isSet(field)) {
      return new MongoLogin(s.user(), new char[0], "none", null);
    }
    SecretResolver.Meta meta = secrets.meta(field);
    if ("dynamic".equals(meta.source())) {
      try (SecretResolver.Login login = secrets.login(field)) {
        return new MongoLogin(login.username(), login.password().chars().clone(), login.version(),
            login.notAfter());
      }
    }
    try (SecretResolver.Secret secret = secrets.secret(field)) {
      return new MongoLogin(s.user(), secret.chars().clone(), secret.version(), secret.notAfter().orElse(null));
    }
  }

  boolean hasPassword() {
    return password.length > 0;
  }

  /**
   * The driver's credential, or {@code null} without a user. The driver copies the password into the credential,
   * which lives as long as its client; this copy is zeroed by {@link #close()}.
   */
  MongoCredential credential(MongoSettings s) {
    if (user == null || "none".equals(s.mechanism())) {
      return null;
    }
    return switch (s.mechanism()) {
      case "SCRAM-SHA-256" -> MongoCredential.createScramSha256Credential(user, s.authSource(), password);
      case "SCRAM-SHA-1" -> MongoCredential.createScramSha1Credential(user, s.authSource(), password);
      default -> MongoCredential.createCredential(user, s.authSource(), password);
    };
  }

  /** {@code gw_reader@admin}, or {@code null}. */
  String identity(MongoSettings s) {
    return user == null ? null : user + "@" + s.authSource();
  }

  @Override
  public void close() {
    Arrays.fill(password, '\0');
  }

  @Override
  public String toString() {
    return "MongoLogin[user=" + user + ", version=" + version + ", password=‹secret›]";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof MongoLogin l && Objects.equals(user, l.user) && Objects.equals(version, l.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(user, version);
  }
}
