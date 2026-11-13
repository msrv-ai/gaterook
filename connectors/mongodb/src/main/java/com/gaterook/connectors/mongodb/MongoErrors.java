package com.gaterook.connectors.mongodb;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.TestResult;
import com.mongodb.MongoClientException;
import com.mongodb.MongoConfigurationException;
import com.mongodb.MongoException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoIncompatibleDriverException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoNodeIsRecoveringException;
import com.mongodb.MongoNotPrimaryException;
import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoServerException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoSocketWriteException;
import com.mongodb.MongoSocketWriteTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteConcernException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ServerDescription;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLException;

/**
 * What a failure means, in the kit's words (report 05 §5): outside, the driver nearly always says
 * {@link MongoTimeoutException} (server selection gave up); the cause is in the cluster description — the exception
 * of a server's monitor or of the SRV lookup — so that is where the text comes from. Connection problems are
 * {@code RESOURCE_UNAVAILABLE} (they count for the circuit breaker), a query the server refused is
 * {@code RESOURCE_ERROR} with the server's own words, {@code maxTimeMS} is {@code TIMEOUT}.
 *
 * <p>An update is different once it may have reached the server ({@link #update}): a lost connection, a missing
 * answer, an interrupted wait, a failover or a write concern error then leave it possibly applied, so the call is
 * {@code OUTCOME_UNKNOWN} and says to check the documents. The core never runs such a request again, and the agent
 * is not told that the server was merely unreachable. Only failures that provably happen before the request leaves
 * the client stay ordinary errors.
 */
final class MongoErrors {

  /**
   * Server errors that stop a command while it runs ({@code Interrupted}, {@code ExceededTimeLimit},
   * {@code MaxTimeMSExpired}): an update stopped by one may have changed documents before.
   */
  private static final Set<Integer> STOPPED_WHILE_RUNNING = Set.of(11601, 262, 50);

  private MongoErrors() {}

  /** One failure, classified. */
  record Reason(String code, String text) {}

  /** The reason a client couldn't connect, or {@code null} when it isn't a connection problem. */
  static Reason connection(Throwable e, MongoSettings s, MongoClient client) {
    Throwable cause = e;
    if (e instanceof MongoTimeoutException && client != null) {
      Throwable described = described(client.getClusterDescription());
      if (described != null) {
        cause = described;
      } else if (e.getMessage() != null && e.getMessage().contains("waiting for a connection")) {
        return new Reason("mongo.busy", "is busy: " + s.poolSize() + (s.poolSize() == 1 ? " call is" : " calls are")
            + " running. Try again.");
      }
    }
    String host = s.parsed() == null ? "the server" : s.parsed().firstHost();
    String hostPort = s.parsed() == null ? "the server" : s.parsed().srv() ? host
        : host + ":" + s.parsed().firstPort();
    for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof MongoConfigurationException) {
        return new Reason("mongo.srv", "can't be found through DNS: " + srvText(t, host));
      }
      if (t instanceof MongoSecurityException sec) {
        return new Reason("mongo.auth", "refused the login: authentication failed for "
            + (sec.getCredential() == null || sec.getCredential().getUserName() == null ? "the user"
                : sec.getCredential().getUserName()) + " (auth database " + s.authSource() + ").");
      }
      if (t instanceof MongoIncompatibleDriverException) {
        return new Reason("mongo.old-server", "is too old for the driver: MongoDB 4.2 or later is required.");
      }
      if (t instanceof UnknownHostException) {
        return new Reason("mongo.unknown-host", "can't be found: can't resolve " + host + ". Check the VPN or the "
            + "host name.");
      }
      if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
        return new Reason("mongo.refused", "refused the connection on port " + (s.parsed() == null ? 27017
            : s.parsed().firstPort()) + ". Is mongod running?");
      }
      if (t instanceof SSLException) {
        return new Reason("mongo.tls", "refused TLS: " + tlsText(t, host));
      }
      if (t instanceof SocketTimeoutException || t instanceof MongoSocketReadTimeoutException) {
        return new Reason("mongo.timeout", "didn't answer: " + hostPort + " timed out. Check the VPN or the host.");
      }
    }
    if (cause instanceof MongoSocketException || cause instanceof MongoTimeoutException) {
      return new Reason("mongo.unreachable", "can't be reached: " + firstLine(cause.getMessage()));
    }
    return null;
  }

  /** A failed call, for the agent. */
  static ConnectorException call(MongoException e, String connection, MongoSettings s, MongoClient client) {
    if (e instanceof MongoOperationTimeoutException) {
      return ConnectorException.timeout("The operation exceeded its time limit on " + connection
          + ". An update may have applied changes before the timeout; check the documents before retrying.");
    }
    if (e instanceof MongoExecutionTimeoutException) {
      return ConnectorException.timeout("The query ran longer than its time limit (maxTimeMS) on " + connection
          + " and the server stopped it. Narrow the filter, put $match first, or ask for fewer documents.");
    }
    if (e instanceof MongoInterruptedException) {
      return ConnectorException.timeout("The call on " + connection + " was stopped at its deadline.");
    }
    if (e instanceof MongoServerException server && !(e instanceof com.mongodb.MongoNodeIsRecoveringException)
        && !(e instanceof com.mongodb.MongoNotPrimaryException)) {
      int code = server.getCode();
      String msg = errorMessage(server);
      if (code == 13) {
        return ConnectorException.resourceError("MongoDB refused it on " + connection + ": " + msg + ". The user "
            + "may only perform operations allowed by its roles.", null);
      }
      if (code == 292 || code == 146) {
        return ConnectorException.resourceError("MongoDB stopped it on " + connection + ": " + msg + ". Add $match "
            + "or $limit before $sort and $group.", null);
      }
      return ConnectorException.resourceError("MongoDB refused the query on " + connection + ": " + msg
          + (code > 0 ? " (code " + code + ")." : "."), null);
    }
    Reason r = connection(e, s, client);
    if (r != null) {
      return ConnectorException.unavailable(connection + " " + r.text(), null);
    }
    return ConnectorException.resourceError("MongoDB failed on " + connection + ": " + firstLine(e.getMessage()),
        null);
  }

  /**
   * A failed update ({@code mongo_update} when {@code many} is false, {@code mongo_update_many} when it is true): an
   * update that may have changed documents is {@code OUTCOME_UNKNOWN}, any other failure is what {@link #call} says.
   */
  static ConnectorException update(MongoException e, String connection, MongoSettings s, MongoClient client,
      boolean many) {
    String what = mayHaveApplied(e, many, connection);
    if (what == null) {
      return call(e, connection, s, client);
    }
    return ConnectorException.outcomeUnknown("Outcome unknown: " + what
        + " It may have changed documents; read them before retrying.", e);
  }

  /**
   * How an update on {@code connection} that failed may still have changed documents, or {@code null} when it can't
   * have: it never left the client, or the server refused it before changing anything.
   */
  static String mayHaveApplied(MongoException e, boolean many, String connection) {
    if (e instanceof MongoOperationTimeoutException) {
      // The call's own deadline: call() reports a TIMEOUT whose text says the update may have applied, and the core
      // treats a timed-out approved call as one whose outcome is unknown.
      return null;
    }
    if (neverSent(e)) {
      return null;
    }
    if (e instanceof MongoWriteConcernException wc) {
      return "MongoDB applied the update on " + connection + " but couldn't confirm it with its replicas ("
          + firstLine(wc.getWriteConcernError() == null ? wc.getMessage() : wc.getWriteConcernError().getMessage())
          + "), so it may not last.";
    }
    if (e instanceof MongoNotPrimaryException || e instanceof MongoNodeIsRecoveringException) {
      return "the server of " + connection + " stopped being the primary or shut down during the update ("
          + errorMessage((MongoServerException) e) + ").";
    }
    if (e instanceof MongoWriteException w) {
      // One document changes atomically or not at all; updateMany changes them one after the other and stops at the
      // first error, keeping what it changed before.
      return many ? "MongoDB stopped updating on " + connection + " at an error: "
          + firstLine(w.getError().getMessage()) + " (code " + w.getError().getCode() + "). Documents it changed "
          + "before the error stay changed." : null;
    }
    if (e instanceof MongoServerException server) {
      return STOPPED_WHILE_RUNNING.contains(server.getCode()) ? "MongoDB stopped the update on " + connection
          + " while it ran (" + errorMessage(server) + ")." : null;
    }
    if (e instanceof MongoExecutionTimeoutException) {
      return "MongoDB stopped the update on " + connection + " at its time limit while it ran.";
    }
    if (e instanceof MongoInterruptedException) {
      return "the call was stopped while the update on " + connection + " was running.";
    }
    if (e instanceof MongoSocketReadTimeoutException) {
      return connection + " didn't answer the update in time.";
    }
    if (e instanceof MongoSocketException) {
      return "the connection to " + connection + " was lost after the update was sent, before MongoDB answered.";
    }
    return "the update on " + connection + " failed after it was sent: " + firstLine(e.getMessage());
  }

  /**
   * Failures that happen before an operation's request leaves the client: no server or no free connection was found,
   * a connection couldn't be opened or authenticated, the client couldn't be built, or the request couldn't be written
   * in full (a server acts only on a whole message).
   */
  private static boolean neverSent(MongoException e) {
    return e instanceof MongoClientException || e instanceof MongoSocketOpenException
        || e instanceof MongoSocketWriteException || e instanceof MongoSocketWriteTimeoutException;
  }

  /** A failed Test connection. */
  static TestResult test(Throwable e, MongoSettings s, MongoClient client) {
    Reason r = connection(e, s, client);
    String technical = technical(e);
    if (r != null) {
      return TestResult.failure(r.code(), sentence(r, s), technical);
    }
    if (e instanceof MongoServerException server) {
      return TestResult.failure("mongo.error", "MongoDB refused the test: " + errorMessage(server) + ".",
          technical);
    }
    return TestResult.failure("mongo.error", "The test failed: " + firstLine(e.getMessage()), technical);
  }

  /** "mongo.dev.internal:27017 refused the connection…": a reason that reads after the server's name. */
  static String sentence(Reason r, MongoSettings s) {
    String who = s.parsed() == null ? "The server" : s.parsed().srv() ? s.parsed().firstHost()
        : s.parsed().firstHost() + ":" + s.parsed().firstPort();
    return who + " " + r.text();
  }

  private static Throwable described(ClusterDescription d) {
    if (d.getSrvResolutionException() != null) {
      return d.getSrvResolutionException();
    }
    for (ServerDescription server : d.getServerDescriptions()) {
      if (server.getException() != null) {
        return server.getException();
      }
    }
    return null;
  }

  private static String srvText(Throwable t, String host) {
    String msg = t.getMessage() == null ? "" : t.getMessage();
    if (msg.startsWith("No SRV records")) {
      return "no SRV records for " + host + ".";
    }
    Throwable inner = t.getCause();
    return firstLine(inner != null && inner.getMessage() != null ? inner.getMessage() : msg);
  }

  private static String tlsText(Throwable t, String host) {
    String all = chain(t).toLowerCase(Locale.ROOT);
    if (all.contains("no subject alternative") || all.contains("no name matching")) {
      return "the certificate doesn't match " + host + ". Use the name in the certificate.";
    }
    if (all.contains("pkix") || all.contains("unable to find valid certification path")
        || all.contains("certificate_unknown")) {
      return "the server certificate isn't trusted. Add its CA certificate.";
    }
    if (all.contains("unrecognized ssl message") || all.contains("plaintext") || all.contains("terminated the "
        + "handshake") || all.contains("ssl peer shut down")) {
      return "the server doesn't speak TLS on that port. Select Without TLS in Connection type, or use its TLS port.";
    }
    return firstLine(t.getMessage()) + ".";
  }

  static String errorMessage(MongoServerException e) {
    if (e instanceof com.mongodb.MongoCommandException c && c.getErrorMessage() != null
        && !c.getErrorMessage().isBlank()) {
      return firstLine(c.getErrorMessage());
    }
    if (e instanceof MongoWriteException w && w.getError() != null && w.getError().getMessage() != null
        && !w.getError().getMessage().isBlank()) {
      // The server's words, without the driver's wrapper around them (which names the server's address).
      return firstLine(w.getError().getMessage());
    }
    return firstLine(e.getMessage());
  }

  private static String chain(Throwable t) {
    StringBuilder out = new StringBuilder();
    for (Throwable c = t; c != null && out.length() < 2_000; c = c.getCause() == c ? null : c.getCause()) {
      out.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
    }
    return out.toString();
  }

  static String technical(Throwable t) {
    String text = chain(t);
    return text.length() > 1_000 ? text.substring(0, 1_000) : text;
  }

  static String firstLine(String message) {
    if (message == null) {
      return "no details";
    }
    int nl = message.indexOf('\n');
    String line = nl < 0 ? message : message.substring(0, nl);
    return line.length() > 300 ? line.substring(0, 300) + "…" : line;
  }
}
