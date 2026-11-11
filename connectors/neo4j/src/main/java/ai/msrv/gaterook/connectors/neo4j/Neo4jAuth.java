package ai.msrv.gaterook.connectors.neo4j;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import java.util.Objects;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;

/**
 * The sign-in of one call: the form's user with the stored password, or the stored bearer token. Resolving the secret
 * put every form of it into the call's taint; the connector adds it to the taint once more itself, so an echo of it
 * in a result, an error or a plan is masked.
 *
 * @param version changes with every new value: a driver built on another version is replaced
 */
record Neo4jAuth(Neo4jSettings.Method method, String user, String secret, String version) {

  static Neo4jAuth resolve(Neo4jSettings s, SecretResolver secrets, ExecContext ctx) throws ConnectorException {
    String field = s.secretField();
    String what = s.method() == Neo4jSettings.Method.PASSWORD ? "password" : "bearer token";
    if (secrets == null || !secrets.isSet(field)) {
      throw ConnectorException.badInput("The " + what + " of the connection isn't set: enter it under "
          + "Authentication.");
    }
    try (SecretResolver.Secret secret = secrets.secret(field)) {
      String value = secret.unsafeString();
      if (s.method() == Neo4jSettings.Method.TOKEN) {
        value = value.strip();
      }
      if (value.isEmpty()) {
        throw ConnectorException.badInput("The " + what + " of the connection is empty: enter it under "
            + "Authentication.");
      }
      ctx.taint(value);
      return new Neo4jAuth(s.method(), s.user(), value, Objects.requireNonNullElse(secret.version(), "1"));
    } catch (SecretUnavailableException e) {
      throw ConnectorException.unavailable("The " + what + " of the connection isn't available: " + e.getMessage()
          + ".", null);
    }
  }

  /** The driver's token: basic with the user and password, or bearer. */
  AuthToken token() {
    return method == Neo4jSettings.Method.PASSWORD ? AuthTokens.basic(user, secret) : AuthTokens.bearer(secret);
  }

  /** Who signs in, for Test connection and messages; never the secret. */
  String who() {
    return method == Neo4jSettings.Method.PASSWORD ? user : "the bearer token's user";
  }

  @Override
  public String toString() {
    return "Neo4jAuth[" + method + ", user=" + user + ", version=" + version + ", secret=‹secret›]";
  }
}
