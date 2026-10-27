package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import java.time.Duration;
import tools.jackson.databind.node.ObjectNode;

/**
 * One log backend, everything that does I/O (report 07 §7). The tools' logic — limits, cursors, the tail loop, the
 * answer — is shared ({@link Search}, {@link Tail}); a backend only answers "the newest {@code want} entries of this
 * window" and "the oldest {@code want} entries of this window", each in its own API and query language. Built for one
 * call: it holds the call's secrets and context.
 */
sealed interface Backend permits LokiBackend, GcpLoggingBackend, CloudWatchBackend, OpenSearchBackend {

  /**
   * What one call gives a backend.
   *
   * @param maxChars longest message kept per entry (the policy's cell limit, 8 KB)
   */
  record Call(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Clients clients, CloudCredentials cloud,
      int maxChars) {}

  /**
   * Entries of {@code [fromNs, toNs]} (both inclusive), newest first, at most {@code want}.
   *
   * @param continuation the backend's token from an earlier page of this same window, or {@code null}
   */
  Page newest(String query, String source, long fromNs, long toNs, int want, String continuation)
      throws ConnectorException;

  /** Entries of {@code [fromNs, toNs]} (both inclusive), oldest first, at most {@code want}: one poll of a tail. */
  Page oldest(String query, String source, long fromNs, long toNs, int want) throws ConnectorException;

  /** How often {@code logs_tail} asks again within one call. */
  Duration pollEvery();

  /**
   * How far behind now a tail reads: entries arrive after their timestamp (Loki's {@code delay_for}); reading up to
   * now would skip the ones still in flight.
   */
  Duration delay();

  /** Whether a truncated search gives a cursor; Logs Insights doesn't (narrow {@code to} instead). */
  default boolean searchCursor() {
    return true;
  }

  /** Test connection: every step with its time, the version, what the account may do, hints. Writes nothing. */
  TestResult test();

  /** A cheap liveness check for the health scheduler; never spends the backend's query quota. */
  HealthResult health();

  /**
   * What {@code describe_connection} adds (≤ 2 s): sources, labels or fields worth knowing; {@code object} narrows it
   * to a label (Loki), a log group (CloudWatch) or an index pattern (OpenSearch).
   */
  ObjectNode describe(String object) throws ConnectorException;

  static Backend of(Settings s, Call call) {
    return switch (s) {
      case Settings.Loki l -> new LokiBackend(l, call);
      case Settings.Gcp g -> new GcpLoggingBackend(g, call);
      case Settings.CloudWatch c -> new CloudWatchBackend(c, call);
      case Settings.OpenSearch o -> new OpenSearchBackend(o, call);
    };
  }
}
