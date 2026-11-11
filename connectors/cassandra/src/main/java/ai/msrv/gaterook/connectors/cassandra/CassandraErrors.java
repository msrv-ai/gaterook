package ai.msrv.gaterook.connectors.cassandra;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.DriverExecutionException;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.NoNodeAvailableException;
import com.datastax.oss.driver.api.core.RequestThrottlingException;
import com.datastax.oss.driver.api.core.UnsupportedProtocolVersionException;
import com.datastax.oss.driver.api.core.auth.AuthenticationException;
import com.datastax.oss.driver.api.core.connection.BusyConnectionException;
import com.datastax.oss.driver.api.core.connection.ClosedConnectionException;
import com.datastax.oss.driver.api.core.connection.ConnectionInitException;
import com.datastax.oss.driver.api.core.connection.FrameTooLongException;
import com.datastax.oss.driver.api.core.connection.HeartbeatException;
import com.datastax.oss.driver.api.core.servererrors.BootstrappingException;
import com.datastax.oss.driver.api.core.servererrors.CASWriteUnknownException;
import com.datastax.oss.driver.api.core.servererrors.CoordinatorException;
import com.datastax.oss.driver.api.core.servererrors.OverloadedException;
import com.datastax.oss.driver.api.core.servererrors.QueryValidationException;
import com.datastax.oss.driver.api.core.servererrors.ReadFailureException;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.ServerError;
import com.datastax.oss.driver.api.core.servererrors.TruncateException;
import com.datastax.oss.driver.api.core.servererrors.UnauthorizedException;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.core.servererrors.WriteFailureException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLException;

/**
 * Driver exceptions as {@link ConnectorException}s with messages that say what happened and what to do. Server text
 * passes through {@link OutputSanitizer} and is cut; a password never reaches the driver's messages (it holds none).
 *
 * <p>For a change, everything that may have happened after the request left — a timeout of the driver or of the
 * coordinator, a connection lost while waiting, a failed or unknown write, a server error, a failed truncation — is
 * {@link ConnectorException.Kind#OUTCOME_UNKNOWN}, and never retried. What the cluster refused before running it
 * (syntax, schema, permissions, too few replicas, overload) changed nothing.
 */
final class CassandraErrors {

  private CassandraErrors() {}

  /** The exception a driver future failed with, without the wrappers of futures. */
  static Throwable unwrap(Throwable e) {
    Throwable t = e;
    while ((t instanceof CompletionException || t instanceof ExecutionException
        || t instanceof DriverExecutionException) && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  /** A failure of a read or of the gateway's own metadata statements. */
  static ConnectorException read(Throwable e, String what) {
    Throwable t = unwrap(e);
    ConnectorException common = common(t, what);
    if (common != null) {
      return common;
    }
    if (t instanceof DriverTimeoutException) {
      return ConnectorException.timeout(what + " didn't answer within the call's time. Narrow it with a partition "
          + "key or a smaller limit.");
    }
    if (t instanceof ReadTimeoutException rt) {
      return ConnectorException.timeout(what + " timed out on the cluster: " + rt.getReceived() + " of "
          + rt.getBlockFor() + " replicas answered in time at " + rt.getConsistencyLevel() + ". Narrow the query, "
          + "or read at LOCAL_ONE.");
    }
    if (t instanceof ReadFailureException rf) {
      return ConnectorException.resourceError(what + " failed on " + rf.getNumFailures() + " replica"
          + (rf.getNumFailures() == 1 ? "" : "s") + ": " + server(rf) + ".", t);
    }
    if (t instanceof ClosedConnectionException || t instanceof HeartbeatException) {
      return ConnectorException.unavailable(what + " lost its connection to the node. Try again.", t);
    }
    if (t instanceof CoordinatorException ce) {
      return ConnectorException.resourceError(what + " failed. Cassandra said: " + server(ce) + ".", t);
    }
    return internal(t, what);
  }

  /**
   * A failure of an approved change, after the request was sent. {@code sent}: the driver may have handed it to a
   * node; what fails before (preparing, binding, connecting) is a {@link #read} failure.
   */
  static ConnectorException change(Throwable e, String what) {
    Throwable t = unwrap(e);
    if (t instanceof DriverTimeoutException) {
      return unknown(what + " got no answer within the call's time", t);
    }
    if (t instanceof WriteTimeoutException wt) {
      return unknown("the cluster timed out on " + what + " (" + wt.getReceived() + " of " + wt.getBlockFor()
          + " replicas acknowledged at " + wt.getConsistencyLevel() + ", write type " + wt.getWriteType() + ")", t);
    }
    if (t instanceof CASWriteUnknownException cw) {
      return unknown("Cassandra couldn't tell whether the lightweight transaction " + what + " applied ("
          + cw.getReceived() + " of " + cw.getBlockFor() + " replicas)", t);
    }
    if (t instanceof WriteFailureException wf) {
      return unknown(what + " failed on " + wf.getNumFailures() + " replica" + (wf.getNumFailures() == 1 ? "" : "s")
          + " (" + server(wf) + "); others may have applied it", t);
    }
    if (t instanceof ReadTimeoutException || t instanceof ReadFailureException) {
      return unknown("the cluster timed out while it read for " + what, t);
    }
    if (t instanceof TruncateException te) {
      return unknown("the truncation failed on the cluster (" + server(te) + "); some nodes may have truncated", t);
    }
    if (t instanceof ServerError se) {
      return unknown("the coordinator failed while it ran " + what + " (" + server(se) + ")", t);
    }
    if (t instanceof ClosedConnectionException || t instanceof HeartbeatException) {
      return unknown("the connection to the node closed before " + what + " was answered", t);
    }
    if (t instanceof CancellationException || t instanceof InterruptedException) {
      return unknown("the call was stopped while " + what + " was on its way", t);
    }
    ConnectorException common = common(t, what);
    if (common != null) {
      return common;
    }
    if (t instanceof CoordinatorException ce) {
      return ConnectorException.resourceError(what + " failed. Cassandra said: " + server(ce) + ". Nothing changed.",
          t);
    }
    return unknown(what + " failed in a way the driver couldn't explain (" + t.getClass().getSimpleName() + ")", t);
  }

  private static ConnectorException unknown(String what, Throwable t) {
    return ConnectorException.outcomeUnknown("The outcome is unknown: " + what + ". Check the data before you try "
        + "again; GateRook doesn't retry a change.", t);
  }

  /** Failures that mean the same for reads and changes: nothing ran. */
  private static ConnectorException common(Throwable t, String what) {
    if (t instanceof AllNodesFailedException all) {
      return connect(all);
    }
    if (t instanceof AuthenticationException) {
      return ConnectorException.resourceError("Cassandra refused the sign-in: check the user name and password of "
          + "the connection.", t);
    }
    if (t instanceof UnauthorizedException ue) {
      return ConnectorException.resourceError("The connection's role may not do this: " + server(ue) + ". Grant it "
          + "in Cassandra, or use a connection whose role may.", ue);
    }
    if (t instanceof QueryValidationException qe) {
      return ConnectorException.resourceError(what + " failed. Cassandra said: " + server(qe) + ". Nothing ran.", t);
    }
    if (t instanceof UnavailableException ue) {
      return ConnectorException.unavailable("Too few replicas are up for " + ue.getConsistencyLevel() + ": "
          + ue.getAlive() + " of " + ue.getRequired() + " needed. Nothing ran.", t);
    }
    if (t instanceof OverloadedException || t instanceof BootstrappingException) {
      return ConnectorException.unavailable("The node is overloaded or still starting: " + server(t)
          + ". Nothing ran; try again later.", t);
    }
    if (t instanceof BusyConnectionException || t instanceof RequestThrottlingException) {
      return ConnectorException.unavailable("The connection to the node is busy. Nothing ran; try again.", t);
    }
    if (t instanceof FrameTooLongException) {
      return ConnectorException.resourceError("The answer is larger than GateRook reads in one page. Select fewer "
          + "columns or use a smaller limit.", t);
    }
    if (t instanceof IllegalStateException && String.valueOf(t.getMessage()).contains("closed")) {
      return ConnectorException.unavailable("The connection to the cluster was closed while the call started. Try "
          + "again.", t);
    }
    return null;
  }

  /** Why no node took a session or a request: the first error of each node, the sign-in and TLS said plainly. */
  static ConnectorException connect(AllNodesFailedException all) {
    for (List<Throwable> errors : all.getAllErrors().values()) {
      for (Throwable error : errors) {
        if (causeOf(error, AuthenticationException.class) != null) {
          return ConnectorException.resourceError("Cassandra refused the sign-in: check the user name and password "
              + "of the connection.", error);
        }
        SSLException tls = causeOf(error, SSLException.class);
        if (tls != null) {
          return ConnectorException.unavailable("TLS to the node failed: " + OutputSanitizer.sanitize(cut(firstLine(
              String.valueOf(tls.getMessage())), 300)) + ". Check the CA certificate and the host name, or whether "
              + "the node speaks TLS.", error);
        }
      }
    }
    if (all instanceof NoNodeAvailableException) {
      return ConnectorException.unavailable("No node of the local data center is available. Check the local data "
          + "center's name and that its nodes are up.", all);
    }
    return ConnectorException.unavailable("No node answered: " + summary(all) + ". Check the contact points, the "
        + "network and TLS.", all);
  }

  /** The node errors in one line, at most three of them. */
  static String summary(AllNodesFailedException all) {
    List<String> parts = new ArrayList<>();
    for (Map.Entry<?, List<Throwable>> e : all.getAllErrors().entrySet()) {
      if (parts.size() == 3) {
        parts.add("…");
        break;
      }
      Throwable first = e.getValue().isEmpty() ? null : e.getValue().getFirst();
      String reason = first == null ? "no answer" : describe(first);
      parts.add(OutputSanitizer.sanitize(cut(String.valueOf(e.getKey()), 80)) + ": " + reason);
    }
    return parts.isEmpty() ? "no node could be tried" : String.join("; ", parts);
  }

  private static String describe(Throwable t) {
    Throwable root = rootOf(t);
    if (t instanceof ConnectionInitException && root instanceof java.net.ConnectException) {
      return "connection refused";
    }
    if (root instanceof java.net.UnknownHostException) {
      return "unknown host";
    }
    if (t instanceof UnsupportedProtocolVersionException) {
      return "no protocol version in common";
    }
    String m = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    return OutputSanitizer.sanitize(cut(firstLine(m), 160));
  }

  private static ConnectorException internal(Throwable t, String what) {
    return new ConnectorException(ConnectorException.Kind.INTERNAL, what + " failed: "
        + OutputSanitizer.sanitize(cut(firstLine(String.valueOf(t.getMessage())), 300)), t);
  }

  /** The server's message, sanitized and cut. */
  static String server(Throwable t) {
    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    // The driver puts the coordinator first ("Query; ..."); keep what the server said.
    return OutputSanitizer.sanitize(cut(firstLine(m), 500));
  }

  /** The first exception of {@code type} in {@code t}'s chain of causes, {@code t} included, or {@code null}. */
  static <T extends Throwable> T causeOf(Throwable t, Class<T> type) {
    Throwable r = t;
    for (int guard = 0; r != null && guard < 20; guard++) {
      if (type.isInstance(r)) {
        return type.cast(r);
      }
      r = r.getCause() == r ? null : r.getCause();
    }
    return null;
  }

  static Throwable rootOf(Throwable t) {
    Throwable r = t;
    int guard = 0;
    while (r.getCause() != null && r.getCause() != r && guard++ < 20) {
      r = r.getCause();
    }
    return r;
  }

  static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    return (nl < 0 ? s : s.substring(0, nl)).strip();
  }

  static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }
}
