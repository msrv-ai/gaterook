package ai.msrv.gaterook.connector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/** The default methods of {@link Connector} and {@link ExecContext}: what a minimal plugin gets for free. */
class ConnectorDefaultsTest {

  private static final ActionPreview PREVIEW =
      ActionPreview.of(ActionPreview.Kind.TEXT, "text", "do it", "target", "summary");

  /** A connector that implements only the abstract methods; test() answers what the test sets. */
  private static final class Minimal implements Connector {
    private final ConnectorManifest manifest =
        ConnectorManifest.builder("minimal", "1.0.0", ConnectorManifest.Kind.PLUGIN)
            .tools(List.of(ToolDefinition.builder("minimal_read").build()))
            .build();
    private TestResult testResult = TestResult.success(12, "1", "me", null);

    @Override
    public ConnectorManifest manifest() {
      return manifest;
    }

    @Override
    public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
      return Classification.read();
    }

    @Override
    public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
      return PREVIEW;
    }

    @Override
    public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      return testResult;
    }

    @Override
    public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      return ToolResult.text("ok");
    }
  }

  private final Minimal connector = new Minimal();

  @Test
  void toolsOnAnEndpointAreTheManifestTools() {
    EndpointView endpoint = new EndpointView("dev", Env.DEV, List.of(), Map.of(), List.of());

    assertThat(connector.tools(endpoint)).isEqualTo(connector.manifest().tools());
  }

  @Test
  void prepareAsksForApprovalOfTheDescribedAction() throws ConnectorException {
    PreparedAction prepared = connector.prepare(call(), null, null, null);

    assertThat(prepared).isEqualTo(PreparedAction.approval(PREVIEW, null));
  }

  @Test
  void healthFollowsTheTest() {
    assertThat(connector.health(null, null, null)).isEqualTo(HealthResult.healthy(12));

    connector.testResult = TestResult.failure("x", "Connection refused on port 5432.", null);
    assertThat(connector.health(null, null, null))
        .isEqualTo(HealthResult.unreachable("Connection refused on port 5432."));

    connector.testResult =
        TestResult.confirm(new TestResult.Confirmation("host-key", "Trust?", null, null), List.of());
    assertThat(connector.health(null, null, null)).isEqualTo(HealthResult.unreachable("Unreachable"));
  }

  @Test
  void optionalCapabilitiesAreOff() throws ConnectorException {
    assertThat(connector.discoverDatabases(null, null, null).error().code()).isEqualTo("discovery-unavailable");
    assertThat(connector.describe(null, null, null, "orders")).isEmpty();
    assertThat(connector.resources(null)).isEmpty();
    assertThat(connector.templateKinds()).isEmpty();
    assertThat(connector.maxConcurrency()).isEqualTo(4);
    assertThat(connector.runsCommand("minimal_read")).isFalse();
    assertThatThrownBy(() -> connector.readResource("mcpgw://connections/dev-db/schema", null, null, null))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT))
        .hasMessage("No resource mcpgw://connections/dev-db/schema");
    connector.onConnectionChanged("c1");
    connector.onConnectionRemoved("c1");
    connector.close();
  }

  /** A command tool's call lasts its command timeout plus the stop, within the client's wait or after approval. */
  @Test
  void aCommandCallLetsTheCommandTimeoutRun() {
    Limits dev = limits(Duration.ofSeconds(30), Duration.ofSeconds(10));
    assertThat(dev.commandCallTimeout(false)).isEqualTo(Duration.ofMillis(32_500));
    assertThat(dev.commandCallTimeout(true)).isEqualTo(Duration.ofMillis(32_500));
    // Past what a client waits: 55 s within its request, up to ten minutes after a decision.
    Limits slow = limits(Duration.ofSeconds(600), Duration.ofSeconds(5));
    assertThat(slow.commandCallTimeout(false)).isEqualTo(Duration.ofSeconds(55));
    assertThat(slow.commandCallTimeout(true)).isEqualTo(Duration.ofMinutes(10));
    // Never shorter than the tool timeout of the policy.
    Limits patient = limits(Duration.ofSeconds(2), Duration.ofSeconds(120));
    assertThat(patient.commandCallTimeout(false)).isEqualTo(Duration.ofSeconds(120));
    assertThat(limits(null, Duration.ofSeconds(10)).commandCallTimeout(true)).isEqualTo(Duration.ofSeconds(10));
  }

  private static Limits limits(Duration command, Duration tool) {
    return new Limits(1000, 1000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8192, Duration.ofSeconds(5),
        command, tool, Duration.ofHours(168));
  }

  @Test
  void remainingCountsDownToZero() {
    Instant now = Instant.parse("2026-10-22T12:30:00Z");

    assertThat(context(now, now.plusSeconds(5)).remaining()).isEqualTo(Duration.ofSeconds(5));
    assertThat(context(now, now.minusSeconds(1)).remaining()).isEqualTo(Duration.ZERO);
  }

  private static ToolCall call() {
    return new ToolCall("tc_1", "minimal_read", "c", JsonNodeFactory.instance.objectNode(), null);
  }

  /** An ExecContext where only clock() and deadline() answer: enough for its default method. */
  private static ExecContext context(Instant now, Instant deadline) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    return (ExecContext) Proxy.newProxyInstance(ExecContext.class.getClassLoader(), new Class<?>[] {ExecContext.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "clock" -> clock;
          case "deadline" -> deadline;
          case "remaining" -> java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
