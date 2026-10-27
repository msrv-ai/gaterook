package ai.msrv.gaterook.core.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The deadline of a call (ARCHITECTURE.md §5 step 10, REMAINING §4 "long SSH commands"): the tool timeout, except for a
 * tool that runs a command bounded by its own timeout, whose call must outlast that timeout and the TERM/KILL stop.
 */
class ConnectorInvokerTimeoutTest {

  private static final Limits DEV = new Limits(1000, 1000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8192,
      Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));

  private static final ConnectionConfig CFG = new ConnectionConfig("c1", "dev-vm", "ssh", Env.DEV, 1,
      JsonNodeFactory.instance.objectNode(), Set.of(), null);

  private static ConnectorInvoker.Request request(String tool, Limits limits) {
    return new ConnectorInvoker.Request("tc_1", CFG, new ToolCall("tc_1", tool, "dev-vm",
        JsonNodeFactory.instance.objectNode(), null), limits, MaskPolicy.SECRETS_ONLY, null, null);
  }

  @Test
  void aCommandToolGetsItsCommandTimeoutAndTheStopNotTheToolTimeout() {
    Connector ssh = new Stub("ssh_run");

    assertThat(ConnectorInvoker.callTimeout(ssh, request("ssh_run", DEV), false)).isEqualTo(Duration.ofMillis(32_500));
    assertThat(ConnectorInvoker.callTimeout(ssh, request("ssh_run", DEV), true)).isEqualTo(Duration.ofMillis(32_500));
    assertThat(ConnectorInvoker.callTimeout(ssh, request("ssh_status", DEV), false)).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void withinTheClientsRequestItStopsBeforeTheClientGivesUpAfterApprovalWithinTenMinutes() {
    Limits slow = new Limits(1000, 1000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8192, Duration.ofSeconds(5),
        Duration.ofSeconds(600), Duration.ofSeconds(5), Duration.ofHours(168));
    Connector ssh = new Stub("ssh_exec");

    assertThat(ConnectorInvoker.callTimeout(ssh, request("ssh_exec", slow), false)).isEqualTo(Duration.ofSeconds(55));
    assertThat(ConnectorInvoker.callTimeout(ssh, request("ssh_exec", slow), true)).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void otherToolsKeepTheToolTimeout() {
    Connector plain = new Stub(null);

    assertThat(ConnectorInvoker.callTimeout(plain, request("sql_query", DEV), false)).isEqualTo(Duration.ofSeconds(10));
    assertThat(ConnectorInvoker.callTimeout(plain, request("sql_execute", DEV), true)).isEqualTo(Duration.ofSeconds(10));
  }

  /** A connector whose one command tool is {@code commandTool}; nothing else is called. */
  private record Stub(String commandTool) implements Connector {

    @Override
    public boolean runsCommand(String tool) {
      return tool.equals(commandTool);
    }

    @Override
    public ConnectorManifest manifest() {
      return ConnectorManifest.builder("stub", "1.0.0", ConnectorManifest.Kind.BUILT_IN).build();
    }

    @Override
    public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
      return Classification.read();
    }

    @Override
    public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "x", "x", "x");
    }

    @Override
    public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      return TestResult.success(1, null, null, null);
    }

    @Override
    public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      return ToolResult.text("ok");
    }
  }
}
