package ai.msrv.gaterook.core.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PreparedAction;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A call whose result, message or description can't be masked (the masker fails, for example with a stack overflow
 * on a pathological text) ends as an internal error with nothing unmasked in it, instead of an exception that leaves
 * the call without its completed audit row (ARCHITECTURE.md §5 step 11).
 */
class ConnectorInvokerMaskingFailureTest {

  private static final Limits LIMITS = new Limits(1000, 1000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8192,
      Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofDays(7));
  private static final ConnectionConfig CFG = new ConnectionConfig("cn_db", "dev-db", "fake", Env.DEV, 1,
      JsonNodeFactory.instance.objectNode(), Set.of(), null);
  private static final ConnectorInvoker.Request REQUEST = new ConnectorInvoker.Request("tc_mask", CFG,
      new ToolCall("tc_mask", "fake_change", CFG.name(), JsonNodeFactory.instance.objectNode(), null), LIMITS,
      MaskPolicy.SECRETS_ONLY, null, null);
  private static final String UNMASKED = "the answer as the resource gave it";

  private final ToolGuard guard = new ToolGuard();

  @AfterEach
  void stopGuard() {
    guard.destroy();
  }

  @Test
  void aResultThatCannotBeMaskedIsWithheldAndTheCallStillEnds() throws Exception {
    Connector connector = connector();
    when(connector.execute(any(), any(), any(), any())).thenReturn(ToolResult.text(UNMASKED));
    Masker masker = mock(Masker.class);
    when(masker.result(any(), any(), any())).thenThrow(new StackOverflowError());
    ConnectorInvoker invoker = invoker(connector, masker);

    ConnectorInvoker.Outcome read = invoker.execute(REQUEST);
    ConnectorInvoker.Outcome approved = invoker.executeApproved(REQUEST,
        new ConnectorInvoker.Approval("ap_masking0001", null, null));

    for (ConnectorInvoker.Outcome outcome : List.of(read, approved)) {
      assertThat(outcome.errorKind()).isEqualTo(ConnectorException.Kind.INTERNAL);
      assertThat(outcome.errorMessage()).contains("tc_mask").doesNotContain(UNMASKED);
      assertThat(outcome.result().content()).extracting(ToolResult.Content::text)
          .noneMatch(text -> text != null && text.contains(UNMASKED));
      assertThat(outcome.preview()).doesNotContain(UNMASKED);
    }
    assertThat(read.outcomeUnknown()).as("a read changes nothing").isFalse();
    assertThat(approved.outcomeUnknown()).as("the approved change ran: it may have applied").isTrue();
  }

  @Test
  void aMessageThatCannotBeMaskedIsWithheld() throws Exception {
    Connector connector = connector();
    when(connector.execute(any(), any(), any(), any())).thenThrow(ConnectorException.badInput(UNMASKED));
    Masker masker = mock(Masker.class);
    when(masker.text(any(), any(), any())).thenThrow(new StackOverflowError());

    ConnectorInvoker.Outcome outcome = invoker(connector, masker).execute(REQUEST);

    assertThat(outcome.errorKind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
    assertThat(outcome.errorMessage()).contains("withheld").contains("tc_mask").doesNotContain(UNMASKED);
  }

  @Test
  void aDescriptionThatCannotBeMaskedAsksNoOne() throws Exception {
    Connector connector = connector();
    when(connector.prepare(any(), any(), any(), any())).thenReturn(PreparedAction.approval(
        ActionPreview.of(ActionPreview.Kind.TEXT, null, "change one thing", "dev-db", UNMASKED), null));
    Masker masker = mock(Masker.class);
    when(masker.text(any(), any(), any())).thenThrow(new StackOverflowError());

    ConnectorInvoker.Prepared prepared = invoker(connector, masker).prepare(REQUEST);

    assertThat(prepared.ok()).isFalse();
    assertThat(prepared.errorKind()).isEqualTo(ConnectorException.Kind.INTERNAL);
    assertThat(prepared.errorMessage()).doesNotContain(UNMASKED);
  }

  private static Connector connector() {
    Connector connector = mock(Connector.class, CALLS_REAL_METHODS);
    when(connector.manifest()).thenReturn(ConnectorManifest.builder("fake", "1.0.0", ConnectorManifest.Kind.BUILT_IN)
        .name("Fake").build());
    return connector;
  }

  private ConnectorInvoker invoker(Connector connector, Masker masker) {
    ConnectorRegistry registry = mock(ConnectorRegistry.class);
    when(registry.connectorFor(CFG)).thenReturn(Optional.of(connector));
    CallContexts contexts = mock(CallContexts.class);
    when(contexts.builder(eq(REQUEST.callId()), eq(CFG), eq(LIMITS))).thenAnswer(invocation ->
        CallContext.builder(REQUEST.callId(), Env.DEV, LIMITS, Clock.systemUTC())
            .handles(mock(ExecContext.HandleCodec.class)));
    SecretService secrets = mock(SecretService.class);
    when(secrets.resolver(eq(CFG), any())).thenCallRealMethod();
    return new ConnectorInvoker(registry, contexts, secrets, guard, masker);
  }
}
