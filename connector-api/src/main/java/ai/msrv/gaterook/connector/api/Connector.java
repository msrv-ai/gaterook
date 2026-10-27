package ai.msrv.gaterook.connector.api;

import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * One resource type and its tools.
 *
 * <p>The same jar runs in the native core (built-in connectors, called directly) and in the plugin host (plugins,
 * reached over GPP/1 through the core's {@code RemoteConnector}). Implementations are blocking and virtual-thread
 * friendly and react to interrupts; they start no thread pools of their own, never call {@code System.exit} or
 * {@code System.setProperty}, install no JVM-wide defaults and write files only under {@link ExecContext#workDir()}.
 * A connector knows nothing about tokens, clients, endpoints or people.
 */
public interface Connector extends AutoCloseable {

  ConnectorManifest manifest();

  /** Tools as published on one endpoint: enum of {@code connection}, effective limits in descriptions. */
  default List<ToolDefinition> tools(EndpointView endpoint) {
    return manifest().tools();
  }

  /** No I/O, no secrets, a few ms; runs before the policy. MUTATION always becomes an approval request. */
  Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy);

  /** Exact action, byte for byte what will run; no I/O; hashed into the approval digest. */
  ActionPreview describeAction(ToolCall call, ConnectionConfig cfg);

  /** Optional I/O before the request exists (dry-run, diff, impact), budget {@code ctx.deadline()} = 5 s. */
  default PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    return PreparedAction.approval(describeAction(call, cfg), null);
  }

  /** Persists nothing; may stop with needsConfirmation (host key, certificate, exec plugin). */
  TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx);

  default HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    TestResult t = test(cfg, secrets, ctx);
    return t.ok()
        ? HealthResult.healthy(t.latencyMs())
        : HealthResult.unreachable(t.error() == null ? "Unreachable" : t.error().message());
  }

  /** READ, or MUTATION only when {@code ctx.approved()}: refuse otherwise (defence in depth). */
  ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException;

  /** Contribution to describe_connection (≤ 2 s); {@code object} narrows it to a table, a collection, a log group. */
  default Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    return Optional.empty();
  }

  default List<ResourceDescriptor> resources(ConnectionConfig cfg) {
    return List.of();
  }

  default ToolResult readResource(String uri, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    throw ConnectorException.badInput("No resource " + uri);
  }

  /** No-code template kinds: "sql-query" (SQL), "ssh-runbook" (SSH), "openapi" (HTTP). */
  default List<TemplateKind> templateKinds() {
    return List.of();
  }

  /** Semaphore bulkhead per connection. */
  default int maxConcurrency() {
    return 4;
  }

  /**
   * {@code tool} runs a command bounded by {@link Limits#commandTimeout()} ({@code ssh_run}, {@code ssh_exec}): the
   * core gives its {@code execute} calls the deadline {@link Limits#commandCallTimeout} instead of the tool timeout,
   * so the command's own timeout — TERM, then KILL — is what stops it. In-process only: plugins keep the default.
   */
  default boolean runsCommand(String tool) {
    return false;
  }

  /** Config, secret version or policy changed: drop pools. */
  default void onConnectionChanged(String connectionId) {}

  default void onConnectionRemoved(String connectionId) {}

  @Override
  default void close() {}
}
