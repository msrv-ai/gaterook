package ai.msrv.gaterook.core.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.connectors.CallContext;
import ai.msrv.gaterook.core.connectors.CallContexts;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ConnectorInvokerTimingTest {

  private static final Limits LIMITS = new Limits(1000, 1000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8192,
      Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(500), Duration.ofDays(7));
  private static final ConnectionConfig CFG = new ConnectionConfig("cn_logs", "dev-logs", "ssh-logs", Env.DEV, 1,
      JsonNodeFactory.instance.objectNode(), Set.of(), null);
  private static final ConnectorInvoker.Request REQUEST = new ConnectorInvoker.Request("tc_timeout", CFG,
      new ToolCall("tc_timeout", "ssh_logs_list", CFG.name(), JsonNodeFactory.instance.objectNode(), null), LIMITS,
      MaskPolicy.SECRETS_ONLY, null, null);

  @Test
  void aTimeoutIncludesResourceTimeBeforeTheInterruptedConnectorReturns() throws Exception {
    Connector connector = mock(Connector.class, CALLS_REAL_METHODS);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(connector.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
      started.countDown();
      while (release.getCount() != 0) {
        try {
          release.await();
        } catch (InterruptedException e) {
          interrupted.countDown();
        }
      }
      return ToolResult.text("late result");
    });
    ToolGuard guard = new ToolGuard();
    try {
      ConnectorInvoker.Outcome outcome = invoker(connector, guard).execute(REQUEST);
      assertThat(started.getCount()).as("the connector entered before the deadline").isZero();
      assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the guard interrupted the connector").isTrue();
      assertThat(outcome.errorKind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
      assertThat(outcome.resourceMs()).as("time in the still-running connector").isGreaterThanOrEqualTo(100);
      assertThat(release.getCount()).as("the connector has not reached its finally block").isEqualTo(1);
    } finally {
      release.countDown();
      guard.destroy();
    }
  }

  @Test
  void aCallRefusedBeforeInvocationHasNoResourceTime() throws Exception {
    Connector connector = mock(Connector.class, CALLS_REAL_METHODS);
    ToolGuard guard = mock(ToolGuard.class);
    int concurrency = connector.maxConcurrency();
    when(guard.run(eq(CFG.id()), eq(CFG.name()), eq(concurrency), eq(LIMITS.toolTimeout()), any(),
        any())).thenThrow(ConnectorException.unavailable("The connection is busy.", null));

    ConnectorInvoker.Outcome outcome = invoker(connector, guard).execute(REQUEST);

    assertThat(outcome.errorKind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
    assertThat(outcome.resourceMs()).isZero();
    verify(connector, never()).execute(any(), any(), any(), any());
  }

  private static ConnectorInvoker invoker(Connector connector, ToolGuard guard) {
    ConnectorRegistry registry = mock(ConnectorRegistry.class);
    when(registry.connectorFor(CFG)).thenReturn(Optional.of(connector));
    CallContexts contexts = mock(CallContexts.class);
    when(contexts.builder(eq(REQUEST.callId()), eq(CFG), eq(LIMITS))).thenAnswer(invocation ->
        CallContext.builder(REQUEST.callId(), Env.DEV, LIMITS, Clock.systemUTC())
            .handles(mock(ExecContext.HandleCodec.class)));
    SecretService secrets = mock(SecretService.class);
    when(secrets.resolver(eq(CFG), any())).thenCallRealMethod();
    return new ConnectorInvoker(registry, contexts, secrets, guard, Masker.standard());
  }
}
