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

  /**
   * Whether a published tool applies to this connection's backend and configuration. No I/O or secrets; the core
   * uses this when building tool/connection pairs. Classification must still reject unsupported calls.
   */
  default boolean supportsTool(String tool, ConnectionConfig cfg) {
    return true;
  }

  /** Draft setup descriptions for this configuration. No I/O, credential resolution or persisted changes. */
  default List<ToolDefinition> previewTools(ConnectionConfig cfg) {
    return manifest().tools().stream().filter(tool -> supportsTool(tool.name(), cfg)).toList();
  }

  /** No I/O, no secrets, a few ms; runs before the policy. MUTATION requires approval unless the pair explicitly uses Bypass. */
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

  /** Authenticates a server draft and returns selectable names in {@code details.databases}; saves nothing. */
  default TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return TestResult.failure("discovery-unavailable", "This connector can't list databases.", null);
  }

  /** Authenticates a draft and returns selectable cloud projects in {@code details.projects}; saves nothing. */
  default TestResult discoverProjects(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return TestResult.failure("discovery-unavailable", "This connector can't list projects.", null);
  }

  /**
   * Lists a remote directory for the administrative connection form; saves nothing and grants no agent access.
   * Like {@link #test}, this may require host-key confirmation before credentials are sent. Implementations return
   * bounded entries in {@code details.entries}, with {@code path}, {@code parent} and {@code truncated} metadata.
   */
  default TestResult browseFiles(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String path) {
    return TestResult.failure("discovery-unavailable", "This connector can't browse remote files.", null);
  }

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

  /**
   * Whether this connection shares resources only the resource itself can list ({@link #listResources}): an external
   * MCP server the person let share its resources. No I/O.
   */
  default boolean sharesResources(ConnectionConfig cfg) {
    return false;
  }

  /** Whether this connection shares prompts ({@link #listPrompts}): an external MCP server the person let share them. */
  default boolean sharesPrompts(ConnectionConfig cfg) {
    return false;
  }

  /**
   * The resources a sharing connection lists now, in MCP's shape: {@code {"resources": […]}}, or with
   * {@code templates} {@code {"resourceTemplates": […]}}; URIs as the endpoint publishes them, under the connection's
   * own prefix. The core masks the answer like a result; {@code null}: none.
   */
  default JsonNode listResources(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, boolean templates)
      throws ConnectorException {
    return null;
  }

  /** The prompts a sharing connection lists now ({@code {"prompts": […]}}), named as the endpoint publishes them. */
  default JsonNode listPrompts(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    return null;
  }

  /** {@code prompts/get} of one of {@link #listPrompts}: {@code {"description", "messages"}}, masked by the core. */
  default JsonNode getPrompt(String name, JsonNode arguments, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx) throws ConnectorException {
    throw ConnectorException.badInput("No prompt " + name);
  }

  /**
   * {@code completion/complete} of a prompt's argument or a resource template's variable this connection shares
   * ({@code params} as the client sent them, names and URIs as the endpoint publishes them): {@code {"completion":
   * {"values", "total", "hasMore"}}}, or {@code null} for none.
   */
  default JsonNode complete(JsonNode params, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    return null;
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
