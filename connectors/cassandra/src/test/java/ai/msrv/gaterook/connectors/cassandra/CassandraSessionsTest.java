package ai.msrv.gaterook.connectors.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.auth.Authenticator;
import com.datastax.oss.driver.api.core.config.OptionsMap;
import com.datastax.oss.driver.api.core.config.TypedDriverOption;
import com.datastax.oss.driver.api.core.loadbalancing.NodeDistance;
import com.datastax.oss.driver.api.core.retry.RetryVerdict;
import com.datastax.oss.driver.api.core.servererrors.WriteType;
import com.datastax.oss.driver.internal.core.metadata.DefaultEndPoint;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** How a session is opened: the driver's options, the address guard, TLS engines, sign-in and the change profile. */
class CassandraSessionsTest {

  static CassandraSettings settings(java.util.function.Consumer<ObjectNode> tweak) throws ConnectorException {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putArray("contactPoints").add("127.0.0.1:9042");
    c.put("localDataCenter", "dc1");
    c.putObject("credentials").put("user", "gw_reader");
    if (tweak != null) {
      tweak.accept(c);
    }
    return CassandraSettings.of(new ConnectionConfig("conn-1", "c", "cassandra", Env.DEV, 1, c,
        Set.of(CassandraSettings.PASSWORD), null));
  }

  @Test
  void theDriverIsToldEverythingInCode() throws Exception {
    CassandraSettings s = settings(c -> {
      c.put("consistency", "LOCAL_QUORUM");
      c.putObject("advanced").put("connectTimeoutMs", 2_000).put("connectionsPerNode", 2);
    });
    OptionsMap o = CassandraSessions.options("conn-1", s);
    assertThat(o.get(TypedDriverOption.LOAD_BALANCING_LOCAL_DATACENTER)).isEqualTo("dc1");
    assertThat(o.get(TypedDriverOption.REQUEST_CONSISTENCY)).isEqualTo("LOCAL_QUORUM");
    assertThat(o.get(TypedDriverOption.REQUEST_SERIAL_CONSISTENCY)).isEqualTo("LOCAL_SERIAL");
    assertThat(o.get(TypedDriverOption.REQUEST_DEFAULT_IDEMPOTENCE)).isFalse();
    assertThat(o.get(TypedDriverOption.REQUEST_LOG_WARNINGS)).isFalse();
    assertThat(o.get(TypedDriverOption.CONNECTION_CONNECT_TIMEOUT)).isEqualTo(Duration.ofSeconds(2));
    assertThat(o.get(TypedDriverOption.CONNECTION_POOL_LOCAL_SIZE)).isEqualTo(2);
    assertThat(o.get(TypedDriverOption.METRICS_FACTORY_CLASS)).isEqualTo("NoopMetricsFactory");
    assertThat(o.get(TypedDriverOption.MONITOR_REPORTING_ENABLED)).isFalse();
    assertThat(o.get(TypedDriverOption.METADATA_SCHEMA_ENABLED)).isFalse();
    assertThat(o.get(TypedDriverOption.TIMESTAMP_GENERATOR_CLASS)).isEqualTo("ServerSideTimestampGenerator");
    assertThat(o.get(TypedDriverOption.CONFIG_RELOAD_INTERVAL)).isEqualTo(Duration.ZERO);
    assertThat(o.get(TypedDriverOption.NETTY_DAEMON)).isTrue();
    assertThat(o.get(TypedDriverOption.SESSION_LEAK_THRESHOLD)).isZero();
    assertThat(o.get(TypedDriverOption.APPLICATION_NAME)).isEqualTo("GateRook");
    // Approved changes: one node, once.
    assertThat(o.get(CassandraSessions.CHANGE, TypedDriverOption.REQUEST_CONSISTENCY)).isEqualTo("LOCAL_QUORUM");
    assertThat(o.get(CassandraSessions.CHANGE, TypedDriverOption.RETRY_POLICY_CLASS))
        .isEqualTo(NeverRetry.class.getName());
    assertThat(o.get(CassandraSessions.CHANGE, TypedDriverOption.SPECULATIVE_EXECUTION_POLICY_CLASS))
        .isEqualTo("NoSpeculativeExecutionPolicy");
    assertThat(o.get(CassandraSessions.CHANGE, TypedDriverOption.REQUEST_DEFAULT_IDEMPOTENCE)).isFalse();
  }

  @Test
  void theStartupOptionsNameTheDriverAndGateRookWithoutBaggage() {
    var startup = CassandraSessions.startupOptions();
    assertThat(startup).containsKeys("DRIVER_NAME", "DRIVER_VERSION", "CLIENT_ID", "APPLICATION_NAME",
        "APPLICATION_VERSION").doesNotContainKey("DRIVER_BAGGAGE");
    assertThat(startup.get("DRIVER_VERSION")).startsWith("4.19");
    assertThat(startup.get("APPLICATION_NAME")).isEqualTo("GateRook");
  }

  @Test
  void aChangeIsNeverRetried() {
    NeverRetry never = new NeverRetry(null, CassandraSessions.CHANGE);
    assertThat(never.onReadTimeoutVerdict(null, ConsistencyLevel.LOCAL_QUORUM, 2, 2, false, 0))
        .isEqualTo(RetryVerdict.RETHROW);
    assertThat(never.onWriteTimeoutVerdict(null, ConsistencyLevel.LOCAL_QUORUM, WriteType.BATCH_LOG, 2, 0, 0))
        .isEqualTo(RetryVerdict.RETHROW);
    assertThat(never.onUnavailableVerdict(null, ConsistencyLevel.LOCAL_QUORUM, 2, 1, 0))
        .isEqualTo(RetryVerdict.RETHROW);
    assertThat(never.onRequestAbortedVerdict(null, new RuntimeException(), 0)).isEqualTo(RetryVerdict.RETHROW);
    assertThat(never.onErrorResponseVerdict(null, null, 0)).isEqualTo(RetryVerdict.RETHROW);
  }

  @Test
  void addressesGateRookNeverReachesAreRefusedAndIgnored() throws Exception {
    for (String blocked : List.of("169.254.169.254:9042", "0.0.0.0", "[fd00:ec2::254]:9042", "224.0.0.1:9042")) {
      CassandraSettings s = settings(c -> c.putArray("contactPoints").add(blocked));
      assertThatThrownBy(() -> CassandraSessions.contactPoints(s)).as(blocked)
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
            assertThat(e.getMessage()).contains("GateRook never connects there");
          });
    }
    List<InetSocketAddress> ok = CassandraSessions.contactPoints(settings(c -> c.putArray("contactPoints")
        .add("127.0.0.1:9142").add("10.0.4.12")));
    assertThat(ok).containsExactly(new InetSocketAddress("127.0.0.1", 9142), new InetSocketAddress("10.0.4.12",
        9042));
    // A node the cluster announces at such an address is never connected to.
    var guard = CassandraSessions.guard();
    assertThat(guard.evaluateDistance(CassandraErrorsTest.node(new DefaultEndPoint(new InetSocketAddress(
        "169.254.169.254", 9042))), "dc1")).isEqualTo(NodeDistance.IGNORED);
    assertThat(guard.evaluateDistance(CassandraErrorsTest.node(new DefaultEndPoint(new InetSocketAddress(
        "10.0.4.13", 9042))), "dc1")).isNull();
  }

  @Test
  void tlsChecksTheNodesNameOrTheConfiguredOne() throws Exception {
    SSLContext context = SSLContext.getDefault();
    SSLEngine own = new CassandraSessions.TlsEngines(context, true, null).newSslEngine(new DefaultEndPoint(
        new InetSocketAddress("127.0.0.1", 9142)));
    assertThat(own.getUseClientMode()).isTrue();
    assertThat(own.getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
    assertThat(own.getPeerHost()).isEqualTo("127.0.0.1");
    SSLEngine named = new CassandraSessions.TlsEngines(context, true, "cassandra.eu-central-1.amazonaws.com")
        .newSslEngine(new DefaultEndPoint(new InetSocketAddress("127.0.0.1", 9142)));
    assertThat(named.getPeerHost()).isEqualTo("cassandra.eu-central-1.amazonaws.com");
    assertThat(named.getSSLParameters().getServerNames()).containsExactly(new SNIHostName(
        "cassandra.eu-central-1.amazonaws.com"));
    SSLEngine caOnly = new CassandraSessions.TlsEngines(context, false, null).newSslEngine(new DefaultEndPoint(
        new InetSocketAddress("127.0.0.1", 9142)));
    assertThat(caOnly.getSSLParameters().getEndpointIdentificationAlgorithm()).isNull();
  }

  @Test
  void theSignInSendsAFreshCopyAndTheSessionZeroesItsOwn() throws Exception {
    char[] password = "s3cret-pw".toCharArray();
    CassandraSessions.PasswordAuth auth = new CassandraSessions.PasswordAuth("gw_reader", password);
    assertThat(auth.toString()).doesNotContain("s3cret");
    Authenticator a = auth.newAuthenticator(new DefaultEndPoint(new InetSocketAddress("127.0.0.1", 9042)),
        "org.apache.cassandra.auth.PasswordAuthenticator");
    ByteBuffer response = a.initialResponse().toCompletableFuture().get();
    String sent = StandardCharsets.UTF_8.decode(response.duplicate()).toString();
    assertThat(sent).isEqualTo("\0gw_reader\0s3cret-pw");
    auth.close();
    assertThat(password).containsOnly('\0');
  }

  @Test
  void theLoginOfACallJoinsItsTaint() throws Exception {
    CassandraSettings s = settings(null);
    TestCtx ctx = TestCtx.of();
    try (CassandraSessions.Login login = CassandraSessions.login(s, TestSecrets.password("pw-1234"), ctx)) {
      assertThat(login.user()).isEqualTo("gw_reader");
      assertThat(new String(login.password())).isEqualTo("pw-1234");
      assertThat(login.toString()).doesNotContain("pw-1234");
    }
    assertThat(ctx.taints).containsExactly("pw-1234");
    // A user read from a store with its password.
    TestCtx stored = TestCtx.of();
    try (CassandraSessions.Login login = CassandraSessions.login(settings(c -> c.remove("credentials")),
        new TestSecrets().login(CassandraSettings.PASSWORD, "vault_user", "vault-pw"), stored)) {
      assertThat(login.user()).isEqualTo("vault_user");
    }
    assertThat(stored.taints).containsExactly("vault-pw");
    // No password: no sign-in.
    try (CassandraSessions.Login none = CassandraSessions.login(s, new TestSecrets(), TestCtx.of())) {
      assertThat(none.password()).isNull();
    }
  }
}
