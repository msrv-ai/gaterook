package ai.msrv.gaterook.connectors.cassandra;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorException.Kind;
import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.NoNodeAvailableException;
import com.datastax.oss.driver.api.core.auth.AuthenticationException;
import com.datastax.oss.driver.api.core.connection.ClosedConnectionException;
import com.datastax.oss.driver.api.core.connection.ConnectionInitException;
import com.datastax.oss.driver.api.core.servererrors.CASWriteUnknownException;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.datastax.oss.driver.api.core.servererrors.OverloadedException;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.ServerError;
import com.datastax.oss.driver.api.core.servererrors.SyntaxError;
import com.datastax.oss.driver.api.core.servererrors.TruncateException;
import com.datastax.oss.driver.api.core.servererrors.UnauthorizedException;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.core.servererrors.WriteFailureException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.WriteType;
import com.datastax.oss.driver.internal.core.metadata.DefaultEndPoint;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * Driver failures as connector errors: a change whose request may have reached a node is an unknown outcome and is
 * never retried; what the cluster refused before running anything changed nothing.
 */
class CassandraErrorsTest {

  private static final DefaultEndPoint NODE = new DefaultEndPoint(new InetSocketAddress("127.0.0.1", 9042));
  /** A node the driver's error maps can name: its end point and a name, nothing else. */
  static final com.datastax.oss.driver.api.core.metadata.Node NODE_META = node(NODE);

  static com.datastax.oss.driver.api.core.metadata.Node node(DefaultEndPoint endPoint) {
    return (com.datastax.oss.driver.api.core.metadata.Node) java.lang.reflect.Proxy.newProxyInstance(
        CassandraErrorsTest.class.getClassLoader(), new Class<?>[] {com.datastax.oss.driver.api.core.metadata.Node
            .class}, (proxy, method, args) -> switch (method.getName()) {
              case "getEndPoint" -> endPoint;
              case "toString" -> "Node(" + endPoint + ")";
              case "hashCode" -> System.identityHashCode(proxy);
              case "equals" -> proxy == args[0];
              case "getDatacenter" -> "dc1";
              default -> null;
            });
  }

  @Test
  void aChangeThatMayHaveReachedANodeHasAnUnknownOutcome() {
    for (Throwable t : new Throwable[] {
        new DriverTimeoutException("Query timed out after PT2S"),
        new WriteTimeoutException(null, ConsistencyLevel.LOCAL_QUORUM, 1, 2, WriteType.SIMPLE),
        new CASWriteUnknownException(null, ConsistencyLevel.LOCAL_SERIAL, 1, 2),
        new WriteFailureException(null, ConsistencyLevel.LOCAL_QUORUM, 1, 2, WriteType.SIMPLE, 1, Map.of()),
        new ReadTimeoutException(null, ConsistencyLevel.LOCAL_SERIAL, 0, 1, false),
        new TruncateException(null, "Truncate timed out"),
        new ServerError(null, "java.lang.RuntimeException"),
        new ClosedConnectionException("Lost connection to remote peer"),
        new CancellationException(),
        new CompletionException(new DriverTimeoutException("wrapped"))}) {
      ConnectorException e = CassandraErrors.change(t, "UPDATE on shop.orders");
      assertThat(e.kind()).as(t.getClass().getSimpleName()).isEqualTo(Kind.OUTCOME_UNKNOWN);
      assertThat(e.countsForCircuitBreaker()).isFalse();
      assertThat(e.getMessage()).startsWith("The outcome is unknown").contains("GateRook doesn't retry a change");
    }
  }

  @Test
  void whatTheClusterRefusedChangedNothing() {
    ConnectorException syntax = CassandraErrors.change(new SyntaxError(null, "line 1:7 no viable alternative"),
        "UPDATE on shop.orders");
    assertThat(syntax.kind()).isEqualTo(Kind.RESOURCE_ERROR);
    assertThat(syntax.getMessage()).contains("no viable alternative", "Nothing ran");
    ConnectorException denied = CassandraErrors.change(new UnauthorizedException(null, "User gw has no MODIFY "
        + "permission on <table shop.orders>"), "UPDATE on shop.orders");
    assertThat(denied.kind()).isEqualTo(Kind.RESOURCE_ERROR);
    assertThat(denied.getMessage()).contains("may not", "MODIFY permission");
    ConnectorException unavailable = CassandraErrors.change(new UnavailableException(null,
        ConsistencyLevel.LOCAL_QUORUM, 2, 1), "UPDATE on shop.orders");
    assertThat(unavailable.kind()).isEqualTo(Kind.RESOURCE_UNAVAILABLE);
    assertThat(unavailable.getMessage()).contains("1 of 2", "Nothing ran");
    assertThat(CassandraErrors.change(new OverloadedException(null), "x").kind())
        .isEqualTo(Kind.RESOURCE_UNAVAILABLE);
  }

  @Test
  void readsTimeOutOrFailWithTheServersWords() {
    ConnectorException timeout = CassandraErrors.read(new DriverTimeoutException("x"), "The query");
    assertThat(timeout.kind()).isEqualTo(Kind.TIMEOUT);
    assertThat(timeout.countsForCircuitBreaker()).isTrue();
    ConnectorException server = CassandraErrors.read(new ReadTimeoutException(null, ConsistencyLevel.LOCAL_QUORUM, 1,
        2, false), "The query");
    assertThat(server.kind()).isEqualTo(Kind.TIMEOUT);
    assertThat(server.getMessage()).contains("1 of 2", "LOCAL_QUORUM");
    ConnectorException invalid = CassandraErrors.read(new InvalidQueryException(null,
        "Undefined column name nope\u001b[31m in table shop.orders"), "The query");
    assertThat(invalid.kind()).isEqualTo(Kind.RESOURCE_ERROR);
    assertThat(invalid.getMessage()).contains("Undefined column name nope\\e[31m").doesNotContain("\u001b");
    assertThat(CassandraErrors.read(new ClosedConnectionException("gone"), "The query").kind())
        .isEqualTo(Kind.RESOURCE_UNAVAILABLE);
  }

  @Test
  void connectingSaysWhetherItWasTheSignInTlsOrTheNetwork() {
    AllNodesFailedException auth = failed(new AuthenticationException(NODE, "Provided username gw and/or password "
        + "are incorrect"));
    ConnectorException signIn = CassandraErrors.connect(auth);
    assertThat(signIn.kind()).isEqualTo(Kind.RESOURCE_ERROR);
    assertThat(signIn.getMessage()).startsWith("Cassandra refused the sign-in");

    AllNodesFailedException tls = failed(new ConnectionInitException("Protocol initialization request, step 1 "
        + "(OPTIONS): failed", new SSLHandshakeException("PKIX path building failed")));
    ConnectorException handshake = CassandraErrors.connect(tls);
    assertThat(handshake.kind()).isEqualTo(Kind.RESOURCE_UNAVAILABLE);
    assertThat(handshake.getMessage()).startsWith("TLS to the node failed: PKIX path building failed");

    assertThat(CassandraErrors.connect(failed(new ConnectionInitException("connect failed",
        new java.net.ConnectException("refused")))).getMessage()).startsWith("No node answered")
        .contains("connection refused");
    assertThat(CassandraErrors.connect(new NoNodeAvailableException()).getMessage())
        .contains("local data center");
    // Failures a future wraps are judged by what they wrap.
    assertThat(CassandraErrors.read(new CompletionException(auth), "x").getMessage())
        .startsWith("Cassandra refused the sign-in");
    assertThat(CassandraSessions.opening(new CompletionException(tls)).kind()).isEqualTo(Kind.RESOURCE_UNAVAILABLE);
  }

  private static AllNodesFailedException failed(Throwable error) {
    return AllNodesFailedException.fromErrors(List.of(Map.entry(NODE_META, error)));
  }

  @Test
  void serverTextIsOneSanitizedLine() {
    assertThat(CassandraErrors.server(new ServerError(null, "first‮line\nsecond line"))).isEqualTo(
        "first\\u202Eline");
    assertThat(CassandraErrors.server(new ServerError(null, "x".repeat(900)))).hasSize(500).endsWith("…");
    assertThat(CassandraErrors.causeOf(new RuntimeException(new IllegalStateException(new SSLHandshakeException("h"))),
        SSLHandshakeException.class)).isNotNull();
    assertThat(CassandraErrors.causeOf(new RuntimeException("plain"), SSLHandshakeException.class)).isNull();
  }
}
