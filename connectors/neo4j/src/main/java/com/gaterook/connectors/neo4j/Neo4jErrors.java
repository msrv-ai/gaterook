package com.gaterook.connectors.neo4j;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connectors.support.OutputSanitizer;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Locale;
import javax.net.ssl.SSLException;
import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.driver.exceptions.AuthorizationExpiredException;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ConnectionReadTimeoutException;
import org.neo4j.driver.exceptions.DatabaseException;
import org.neo4j.driver.exceptions.DiscoveryException;
import org.neo4j.driver.exceptions.FatalDiscoveryException;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.ProtocolException;
import org.neo4j.driver.exceptions.SecurityException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;
import org.neo4j.driver.exceptions.TokenExpiredException;
import org.neo4j.driver.exceptions.TransactionTerminatedException;
import org.neo4j.driver.exceptions.TransientException;
import org.neo4j.driver.exceptions.UntrustedServerException;

/**
 * What a failure of the driver means, in the kit's words. A server that can't be reached, a sign-in it refuses and TLS
 * it can't verify are {@code RESOURCE_UNAVAILABLE} (they count for the circuit breaker); a statement it refuses is
 * {@code BAD_INPUT} with its own words; a transaction it stopped at the timeout is {@code TIMEOUT}; a write a READ
 * transaction refused is {@code DENIED}.
 *
 * <p>A change is different once its COMMIT was sent ({@link #commit}): an answer lost on the way back leaves it
 * possibly applied, so the call is {@code OUTCOME_UNKNOWN} and says to check the data. The core never runs it again.
 * Before the COMMIT, an explicit transaction never applies anything, whatever broke ({@link #beforeCommit}).
 *
 * <p>The server's words go through {@link OutputSanitizer}, one line and 500 characters at most; they never hold the
 * password or token, which the driver sends only in its sign-in.
 */
final class Neo4jErrors {

  private Neo4jErrors() {}

  /** The server's message: first line, sanitized, bounded. */
  static String said(Throwable e) {
    String m = e.getMessage();
    if (m == null || m.isBlank()) {
      return e.getClass().getSimpleName();
    }
    m = m.strip();
    int nl = m.indexOf('\n');
    if (nl > 0) {
      m = m.substring(0, nl).strip();
    }
    if (m.length() > 500) {
      m = m.substring(0, 499) + "…";
    }
    m = OutputSanitizer.sanitize(m);
    return m.endsWith(".") ? m.substring(0, m.length() - 1) : m;
  }

  /** A failure while building a driver or connecting. */
  static ConnectorException connect(RuntimeException e, Neo4jSettings s) {
    ConnectorException c = transport(e, s, false);
    return c != null ? c : ConnectorException.unavailable("Can't connect to Neo4j at " + s.address() + ": "
        + said(e) + ".", e);
  }

  /** A failure of a read: a query, the schema, Test connection, a health check. */
  static ConnectorException read(RuntimeException e, Neo4jSettings s, Duration timeout) {
    ConnectorException c = transport(e, s, false);
    return c != null ? c : server(e, s, timeout, "");
  }

  /** A failure of a change before its COMMIT was sent: the transaction is rolled back, nothing changed. */
  static ConnectorException beforeCommit(RuntimeException e, Neo4jSettings s, Duration timeout) {
    ConnectorException c = transport(e, s, true);
    return c != null ? c : server(e, s, timeout, " Nothing changed.");
  }

  /**
   * A failure of the COMMIT. The server's own refusal (a constraint, a deadlock, a terminated transaction) leaves
   * nothing changed; any other failure leaves the outcome unknown.
   */
  static ConnectorException commit(RuntimeException e, Neo4jSettings s) {
    if (e instanceof ClientException || e instanceof TransientException) {
      return ConnectorException.resourceError("Neo4j refused the commit, so nothing changed: " + said(e) + ".", e);
    }
    return ConnectorException.outcomeUnknown("The answer to the commit was lost (" + said(e) + "), so whether "
        + "Neo4j applied the change is not known. Check the data before you try again.", e);
  }

  /** Connection, TLS and sign-in failures; {@code null} for an answer of the server. */
  private static ConnectorException transport(RuntimeException e, Neo4jSettings s, boolean change) {
    String nothing = change ? " Nothing changed." : "";
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof SSLException || t instanceof CertificateException) {
        return ConnectorException.unavailable("TLS with " + s.address() + " failed: " + said(t) + ". "
            + (s.caPem() == null ? "If the server's certificate comes from a private CA, paste the CA certificate "
            + "under Advanced." : "Check that the CA certificate under Advanced signed the server's certificate, and "
            + "that the certificate names " + s.host() + ".") + nothing, null);
      }
      if (t instanceof UnknownHostException) {
        return ConnectorException.unavailable("The host " + s.host() + " can't be found. Check the URI and this "
            + "computer's DNS or VPN." + nothing, null);
      }
      if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
        return ConnectorException.unavailable("Nothing answers at " + s.address() + ". Check that Neo4j runs and "
            + "that this computer can reach it." + nothing, null);
      }
    }
    if (e instanceof TokenExpiredException) {
      return ConnectorException.unavailable("The bearer token has expired: paste a new one under Authentication."
          + nothing, null);
    }
    if (e instanceof AuthenticationException) {
      return ConnectorException.unavailable("Neo4j refused the sign-in of "
          + (s.method() == Neo4jSettings.Method.PASSWORD ? s.user() : "the bearer token") + ": check the "
          + (s.method() == Neo4jSettings.Method.PASSWORD ? "user and password" : "token") + " under Authentication."
          + nothing, null);
    }
    if (e instanceof AuthorizationExpiredException) {
      return ConnectorException.unavailable("Neo4j's authorization of the connection expired. Try again." + nothing,
          null);
    }
    if (e instanceof ConnectionReadTimeoutException) {
      return ConnectorException.timeout("Neo4j at " + s.address() + " stopped answering." + nothing);
    }
    if (e instanceof FatalDiscoveryException) {
      return ConnectorException.badInput("Neo4j has no database " + s.database() + ", or the user may not use it: "
          + said(e) + ". Check the connection's database." + nothing);
    }
    if (e instanceof ServiceUnavailableException || e instanceof SessionExpiredException
        || e instanceof DiscoveryException) {
      return ConnectorException.unavailable("Can't reach Neo4j at " + s.address() + ": " + said(e) + "." + nothing,
          e);
    }
    if (e instanceof UntrustedServerException || e instanceof ProtocolException) {
      return ConnectorException.unavailable("The server at " + s.address() + " didn't answer as Neo4j: " + said(e)
          + "." + nothing, null);
    }
    return null;
  }

  /** The server's answer to a statement. */
  private static ConnectorException server(RuntimeException e, Neo4jSettings s, Duration timeout, String nothing) {
    String code = e instanceof Neo4jException n && n.code() != null ? n.code() : "";
    String said = said(e);
    if (code.startsWith("Neo.ClientError.Transaction.TransactionTimedOut")
        || (e instanceof TransactionTerminatedException && said.toLowerCase(Locale.ROOT).contains("timeout"))) {
      return ConnectorException.timeout("The transaction ran longer than " + seconds(timeout) + ", so Neo4j stopped "
          + "it. Narrow the query, or add a LIMIT." + nothing);
    }
    if (code.equals("Neo.ClientError.Statement.AccessMode")) {
      return ConnectorException.denied("Neo4j refused a write in a read transaction: " + said + "." + nothing);
    }
    if (code.equals("Neo.ClientError.Database.DatabaseNotFound")) {
      return ConnectorException.badInput("Neo4j has no database " + s.database() + ": check the connection's "
          + "database." + nothing);
    }
    if (code.startsWith("Neo.ClientError.Statement.") || code.startsWith("Neo.ClientError.Procedure.")
        || code.startsWith("Neo.ClientError.Request.")) {
      return ConnectorException.badInput("Neo4j refused the statement: " + said + "." + nothing);
    }
    if (e instanceof SecurityException) {
      return ConnectorException.resourceError("Neo4j refused this for "
          + (s.method() == Neo4jSettings.Method.PASSWORD ? s.user() : "the token's user") + ": " + said + "."
          + nothing, null);
    }
    if (e instanceof TransactionTerminatedException) {
      return ConnectorException.resourceError("Neo4j stopped the transaction: " + said + "." + nothing, null);
    }
    if (e instanceof TransientException) {
      return ConnectorException.resourceError("Neo4j couldn't run it now: " + said + ". Try again in a moment."
          + nothing, null);
    }
    if (e instanceof ClientException || e instanceof DatabaseException) {
      return ConnectorException.resourceError("Neo4j failed: " + said + "." + nothing, null);
    }
    return new ConnectorException(ConnectorException.Kind.INTERNAL, "The Neo4j driver failed: " + said + "."
        + nothing, e);
  }

  static String seconds(Duration d) {
    if (d == null) {
      return "the timeout";
    }
    long ms = d.toMillis();
    return ms % 1000 == 0 ? ms / 1000 + " s" : String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
  }
}
