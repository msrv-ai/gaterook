package ai.msrv.gaterook.plugin.protocol.host;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.TestResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A call or test that failed while the TCP gate refused one of its connections says what the gate refused. */
class ConnectorEndpointExplainTest {

  private static final ScopedEgress.Denial PRIVATE = new ScopedEgress.Denial(ScopedEgress.Reason.PRIVATE_ADDRESS,
      "db2.acme.internal:50000", "db2.acme.internal resolves to 10.30.4.12, a private address. Allow 10.30.4.12/32 "
      + "for warehouse on dev in the environment's policy (http.allowedPrivateRanges).", "10.30.4.12/32",
      "warehouse");

  private static ScopedEgress refusing(ScopedEgress.Denial d) {
    return new ScopedEgress() {
      @Override
      public ExecContext.EgressResponse send(ExecContext.EgressRequest request, long maxBytes) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <T, X extends Throwable> T run(ScopedValue.CallableOp<T, X> body) throws X {
        return body.call();
      }

      @Override
      public Runnable wrap(Runnable action) {
        return action;
      }

      @Override
      public Optional<Denial> lastDenial() {
        return Optional.ofNullable(d);
      }

      @Override
      public void close() {}
    };
  }

  @Test
  void aDriverThatCouldntConnectGetsTheGatesReasonInstead() {
    ConnectorException driver = ConnectorException.unavailable("IBM Db2 can't be reached: [jcc][t4][2043] "
        + "java.net.SocketException: SOCKS: Connection not allowed by ruleset", null);
    ConnectorException e = ConnectorEndpoint.explained(driver, refusing(PRIVATE));
    assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
    assertThat(e.getMessage()).isEqualTo(PRIVATE.text());
    ConnectorException quoted = ConnectorEndpoint.explained(ConnectorException.resourceError("Connection is broken: "
        + "SOCKS: Connection not allowed by ruleset", null), refusing(PRIVATE));
    assertThat(quoted.kind()).isEqualTo(ConnectorException.Kind.DENIED);
  }

  @Test
  void anotherFailureKeepsItsKindAndAddsTheRefusal() {
    ConnectorException sql = ConnectorException.badInput("Syntax error at line 1.");
    ConnectorException e = ConnectorEndpoint.explained(sql, refusing(PRIVATE));
    assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
    assertThat(e.getMessage()).isEqualTo("Syntax error at line 1. GateRook also refused a connection: "
        + PRIVATE.text());
    assertThat(ConnectorEndpoint.explained(sql, refusing(null))).isSameAs(sql);
    assertThat(ConnectorEndpoint.explained(sql, null)).isSameAs(sql);
  }

  @Test
  void aFailedTestNamesThePrivateRangeToAllowLikeTheHttpProbe() {
    TestResult failed = TestResult.failure("db2.unreachable", "Can't reach db2.acme.internal:50000.", null)
        .withChecks(List.of(new TestResult.Check("connect", false, 3, "refused")));
    TestResult t = ConnectorEndpoint.explained(failed, refusing(PRIVATE));
    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("tcp.private-address");
    assertThat(t.error().message()).isEqualTo(PRIVATE.text());
    assertThat(t.error().technical()).isEqualTo("Can't reach db2.acme.internal:50000.");
    assertThat(t.checks()).hasSize(1);
    assertThat(t.suggestedPolicyChange().toString())
        .isEqualTo("{\"kind\":\"allow-private\",\"cidr\":\"10.30.4.12/32\",\"connection\":\"warehouse\"}");
    TestResult ok = TestResult.success(5, "11.5", "db2inst1", null);
    assertThat(ConnectorEndpoint.explained(ok, refusing(PRIVATE))).isSameAs(ok);
    ScopedEgress.Denial metadata = new ScopedEgress.Denial(ScopedEgress.Reason.ALWAYS_BLOCKED, "169.254.169.254:80",
        "169.254.169.254 is cloud metadata, link-local or a reserved address: a plugin never reaches it.", null, null);
    TestResult blocked = ConnectorEndpoint.explained(failed, refusing(metadata));
    assertThat(blocked.error().code()).isEqualTo("tcp.address-blocked");
    assertThat(blocked.suggestedPolicyChange()).isNull();
  }
}
