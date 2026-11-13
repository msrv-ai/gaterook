package com.gaterook.core.connectors.plugins;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.ResourceDescriptor;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Answers for a connector type that an earlier build of GateRook compiled in and that this build gets as a connector
 * module, from the start of the build until the module is installed (docs/connector-delivery.md). The type's
 * connections keep their tools on their endpoints meanwhile, as the type's signed row describes them, so an agent's
 * list of tools is the same right after the update as it was before.
 *
 * <p>The stand-in runs nothing. What needs the connector waits a while for it and goes to it once it is here;
 * otherwise it is told that the connector is on its way, or why it isn't. A classification the connector didn't give
 * is a deny, and nothing is prepared, described as the connector would or executed without it.
 */
final class AwaitedConnector implements Connector {

  /** How the connector this stand-in answers for arrives. */
  interface Arrival {

    /**
     * The type's own connector for {@code cfg} once it is here, waiting up to {@code budget} for it. With no budget
     * it only looks, and starts nothing.
     */
    Optional<Connector> await(ConnectionConfig cfg, Duration budget);

    /** Why it isn't here yet, in a sentence a person and an agent can act on. */
    String problem();
  }

  /** The rule a call is refused under while its connector isn't here, as for a module that can't be reached. */
  static final String RULE = "connector-unavailable";

  private final ConnectorManifest known;
  private final Arrival arrival;

  AwaitedConnector(ConnectorManifest known, Arrival arrival) {
    this.known = known;
    this.arrival = arrival;
  }

  @Override
  public ConnectorManifest manifest() {
    return known;
  }

  private Optional<Connector> here(ConnectionConfig cfg) {
    return arrival.await(cfg, Duration.ZERO);
  }

  private Optional<Connector> waited(ConnectionConfig cfg, ExecContext ctx) {
    Duration budget = ModuleService.CALL_WAIT;
    if (ctx != null && ctx.remaining().compareTo(budget) < 0) {
      budget = ctx.remaining();
    }
    return arrival.await(cfg, budget);
  }

  private Connector required(ConnectionConfig cfg, ExecContext ctx) throws ConnectorException {
    return waited(cfg, ctx).orElseThrow(() -> ConnectorException.unavailable(arrival.problem(), null));
  }

  // ── Building an endpoint's tools: never waits ───────────────────────────────────────────────────────────────

  @Override
  public boolean supportsTool(String tool, ConnectionConfig cfg) {
    return here(cfg).map(c -> c.supportsTool(tool, cfg)).orElse(true);
  }

  @Override
  public List<ToolDefinition> previewTools(ConnectionConfig cfg) {
    return here(cfg).map(c -> c.previewTools(cfg)).orElseGet(known::tools);
  }

  // ── A call ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    Optional<Connector> connector = arrival.await(cfg, ModuleService.CALL_WAIT);
    return connector.isPresent() ? connector.get().classify(call, cfg, policy)
        : Classification.deny(policy.rule(RULE), arrival.problem(), null);
  }

  /**
   * The exact action as the connector writes it. Nothing gets this far without the connector's own classification;
   * should it be gone again, the action is the call itself, with a warning.
   */
  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    Optional<Connector> connector = here(cfg);
    return connector.isPresent() ? connector.get().describeAction(call, cfg)
        : ModuleConnector.callAsAction(call, cfg, arrival.problem());
  }

  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    return required(cfg, ctx).prepare(call, cfg, secrets, ctx);
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    return required(cfg, ctx).execute(call, cfg, secrets, ctx);
  }

  // ── The connection's form and state ─────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    Optional<Connector> connector = waited(cfg, ctx);
    return connector.isPresent() ? connector.get().test(cfg, secrets, ctx) : notHere();
  }

  @Override
  public TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    Optional<Connector> connector = waited(cfg, ctx);
    return connector.isPresent() ? connector.get().discoverDatabases(cfg, secrets, ctx) : notHere();
  }

  @Override
  public TestResult discoverProjects(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    Optional<Connector> connector = waited(cfg, ctx);
    return connector.isPresent() ? connector.get().discoverProjects(cfg, secrets, ctx) : notHere();
  }

  @Override
  public TestResult browseFiles(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String path) {
    Optional<Connector> connector = waited(cfg, ctx);
    return connector.isPresent() ? connector.get().browseFiles(cfg, secrets, ctx, path) : notHere();
  }

  private TestResult notHere() {
    return TestResult.failure(RULE, arrival.problem(), null);
  }

  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope)
      throws ConnectorException {
    // Called as the person types: it looks and never waits.
    Optional<Connector> connector = here(cfg);
    return connector.isPresent() ? connector.get().suggest(cfg, secrets, ctx, scope) : Suggestions.none();
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    Optional<Connector> connector = here(cfg);
    return connector.isPresent() ? connector.get().health(cfg, secrets, ctx)
        : HealthResult.unreachable(arrival.problem());
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    Optional<Connector> connector = here(cfg);
    return connector.isPresent() ? connector.get().describe(cfg, secrets, ctx, object) : Optional.empty();
  }

  // ── Resources and prompts ───────────────────────────────────────────────────────────────────────────────────

  @Override
  public List<ResourceDescriptor> resources(ConnectionConfig cfg) {
    return here(cfg).map(c -> c.resources(cfg)).orElse(List.of());
  }

  @Override
  public ToolResult readResource(String uri, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    return required(cfg, ctx).readResource(uri, cfg, secrets, ctx);
  }

  @Override
  public boolean sharesResources(ConnectionConfig cfg) {
    return here(cfg).map(c -> c.sharesResources(cfg)).orElse(false);
  }

  @Override
  public boolean sharesPrompts(ConnectionConfig cfg) {
    return here(cfg).map(c -> c.sharesPrompts(cfg)).orElse(false);
  }

  @Override
  public JsonNode listResources(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, boolean templates)
      throws ConnectorException {
    Optional<Connector> connector = here(cfg);
    return connector.isPresent() ? connector.get().listResources(cfg, secrets, ctx, templates) : null;
  }

  @Override
  public JsonNode listPrompts(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    Optional<Connector> connector = here(cfg);
    return connector.isPresent() ? connector.get().listPrompts(cfg, secrets, ctx) : null;
  }

  @Override
  public JsonNode getPrompt(String name, JsonNode arguments, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx) throws ConnectorException {
    return required(cfg, ctx).getPrompt(name, arguments, cfg, secrets, ctx);
  }

  @Override
  public JsonNode complete(JsonNode params, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    Optional<Connector> connector = here(cfg);
    return connector.isPresent() ? connector.get().complete(params, cfg, secrets, ctx) : null;
  }

  @Override
  public void subscriptions(ConnectionConfig cfg, Set<String> uris) {
    here(cfg).ifPresent(c -> c.subscriptions(cfg, uris));
  }
}
