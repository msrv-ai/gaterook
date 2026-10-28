package ai.msrv.gaterook.core.endpoints.internal;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.templates.TemplateService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The tools a connection brings to an endpoint, with their {@code endpoint_tool.tool_key}: the connector's tools
 * ({@code sql_query}; a plugin's {@code plugin:<type>:<tool>}; an MCP server's {@code mcp:<connection>:<tool>}) and
 * the tools compiled from the templates that run on it ({@code tpl:<id>:<opKey>}); and the rules for their modes.
 */
@Component
public class ToolPairs {

  /**
   * One tool a connection offers.
   *
   * @param declaredBy        for plugin and MCP server tools: who declared the annotations ("Declared by github-mcp,
   *                          not verified"); {@code null} for built-ins and templates
   * @param provenanceVersion the version a Read only confirmation of the tool is bound to: that of the connector
   *                          serving the connection (a plugin's version; an MCP server connection's upstream server
   *                          version, which its connector reports as its manifest version), not the type's catalog card
   * @param template          the template it was compiled from, or {@code null}
   */
  public record Offered(String toolKey, ToolDefinition definition, ConnectorManifest.Kind kind, String declaredBy,
      String provenanceVersion, TemplateService.Compiled template, TemplateKind.CompiledTool compiled) {

    /** Tools whose Read only needs the person's confirmation per tool, bound to its definition and version. */
    public boolean needsReadOnlyConfirmation() {
      return kind != ConnectorManifest.Kind.BUILT_IN;
    }
  }

  /**
   * A connection wired into (or about to join) an endpoint, as {@link #offered(List, String, Env)} needs it.
   *
   * @param usable whether its tools can run there (its row verified, its type is on): only usable connections share a
   *               view with the others of their type
   */
  public record Member(ConnectionConfig config, ConnectorManifest manifest, boolean usable) {}

  private final ConnectorRegistry registry;
  private final TemplateService templates;
  private final EnvironmentPolicies policies;

  public ToolPairs(ConnectorRegistry registry, TemplateService templates, EnvironmentPolicies policies) {
    this.registry = registry;
    this.templates = templates;
    this.policies = policies;
  }

  /**
   * Every tool each member offers on one endpoint, by connection id, in the members' order. A connector is asked once
   * per type with one {@link EndpointView} of every usable connection of that type on the endpoint, their limits and
   * the contributions of all their templates (report 06 §3.1: {@code ssh_run}'s {@code runbook} enum and its if/then
   * per connection span every SSH connection of the endpoint), and each tool it returns is paired with the connections
   * for which {@link Connector#supportsTool(String, ConnectionConfig)} is true. Same-named pairs carry one definition
   * and publish as one tool. A connection that isn't usable
   * is asked alone: what an edited row says never shapes the tools of the others. An MCP server's tools are its own
   * (one connector per connection, tool keys name the connection), so each MCP server connection is asked alone too.
   */
  public Map<String, List<Offered>> offered(List<Member> members, String slug, Env env) {
    Map<String, List<Member>> groups = new LinkedHashMap<>();
    for (Member m : members) {
      groups.computeIfAbsent(groupKey(m), k -> new ArrayList<>()).add(m);
    }
    Map<String, List<Offered>> byConnection = new HashMap<>();
    for (List<Member> group : groups.values()) {
      byConnection.putAll(offeredByGroup(group, slug, env));
    }
    Map<String, List<Offered>> out = new LinkedHashMap<>();
    for (Member m : members) {
      out.put(m.config().id(), byConnection.get(m.config().id()));
    }
    return out;
  }

  /**
   * What {@code cfg} offers on an endpoint whose connections are {@code members} (it may be one of them, or the one
   * about to join): only its own group is asked.
   */
  public List<Offered> offeredFor(ConnectionConfig cfg, List<Member> members, String slug, Env env) {
    Member self = null;
    for (Member m : members) {
      if (m.config().id().equals(cfg.id())) {
        self = m;
      }
    }
    if (self == null) {
      throw new IllegalArgumentException(cfg.name() + " is not among the endpoint's connections");
    }
    String key = groupKey(self);
    List<Member> group = members.stream().filter(m -> groupKey(m).equals(key)).toList();
    return offeredByGroup(group, slug, env).get(cfg.id());
  }

  private Map<String, List<Offered>> offeredByGroup(List<Member> group, String slug, Env env) {
    Member first = group.get(0);
    ConnectorManifest manifest = first.manifest();
    Optional<Connector> connector = registry.connectorFor(first.config());
    String provenance = connector.map(c -> c.manifest().version()).orElse(manifest.version());
    List<ConnectionConfig> configs = new ArrayList<>();
    Map<String, Limits> limits = new LinkedHashMap<>();
    List<EndpointView.Contribution> contributions = new ArrayList<>();
    Map<String, List<TemplateService.Compiled>> compiledById = new HashMap<>();
    for (Member m : group) {
      ConnectionConfig cfg = m.config();
      List<TemplateService.Compiled> compiled = templates.compiledFor(cfg.id());
      compiledById.put(cfg.id(), compiled);
      configs.add(cfg);
      limits.put(cfg.id(), policies.view(cfg.env(), m.manifest()).limits());
      for (TemplateService.Compiled c : compiled) {
        for (JsonNode value : c.contributions()) {
          contributions.add(new EndpointView.Contribution(cfg.id(), c.templateId(), c.version(), value));
        }
      }
    }
    EndpointView view = new EndpointView(slug, env, List.copyOf(configs), Collections.unmodifiableMap(limits),
        List.copyOf(contributions));
    List<ToolDefinition> tools = connector.map(c -> c.tools(view)).orElse(manifest.tools());
    Map<String, List<Offered>> out = new LinkedHashMap<>();
    for (Member m : group) {
      ConnectionConfig cfg = m.config();
      List<Offered> offered = new ArrayList<>();
      for (ToolDefinition t : tools) {
        if (connector.isPresent() && !connector.get().supportsTool(t.name(), cfg)) {
          continue;
        }
        offered.add(new Offered(key(manifest, cfg, t.name()), t, manifest.kind(), declaredBy(manifest, cfg),
            provenance, null, null));
      }
      for (TemplateService.Compiled c : compiledById.get(cfg.id())) {
        for (TemplateKind.CompiledTool tool : c.tools()) {
          offered.add(new Offered(c.toolKey(tool), tool.definition(), ConnectorManifest.Kind.BUILT_IN, null, null, c,
              tool));
        }
      }
      out.put(cfg.id(), List.copyOf(offered));
    }
    return out;
  }

  /** Connections asked together: the usable ones of one type; everything else alone. */
  private static String groupKey(Member m) {
    boolean ownTools = m.manifest().kind() == ConnectorManifest.Kind.MCP_SERVER;
    return m.usable() && !ownTools ? "type:" + m.config().type() : "connection:" + m.config().id();
  }

  /** What the templates of a connection contribute to its tools (runbooks of {@code ssh_run}). */
  public List<JsonNode> contributions(String connectionId) {
    List<JsonNode> out = new ArrayList<>();
    for (TemplateService.Compiled c : templates.compiledFor(connectionId)) {
      out.addAll(c.contributions());
    }
    return List.copyOf(out);
  }

  public static String key(ConnectorManifest manifest, ConnectionConfig cfg, String tool) {
    return switch (manifest.kind()) {
      case BUILT_IN -> tool;
      case PLUGIN -> "plugin:" + manifest.id() + ":" + tool;
      case MCP_SERVER -> "mcp:" + cfg.name() + ":" + tool;
    };
  }

  private static String declaredBy(ConnectorManifest manifest, ConnectionConfig cfg) {
    return switch (manifest.kind()) {
      case BUILT_IN -> null;
      case PLUGIN -> manifest.name();
      case MCP_SERVER -> cfg.name();
    };
  }

  /** SHA-256 of a tool's published definition: what a Read only confirmation is bound to. */
  public static String definitionHash(ToolDefinition t) {
    return CanonicalJson.sha256Hex(ai.msrv.gaterook.core.connectors.ManifestCodec.writeTool(t));
  }

  // ── Modes ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The mode a pair starts with (ARCHITECTURE.md §3 "Initial mode"): the manifest's default for the endpoint's
   * environment; plugin and MCP server tools always Approval; on a read-only endpoint, no Approval and nothing that
   * changes data.
   */
  public static Mode initial(Offered t, Env endpointEnv, boolean readOnlyEndpoint) {
    Mode mode = endpointEnv == Env.PROD ? t.definition().prodDefaultMode() : t.definition().defaultMode();
    if (t.kind() != ConnectorManifest.Kind.BUILT_IN || mode == Mode.BYPASS) {
      mode = Mode.APPROVAL;
    }
    return readOnlyEndpoint ? clampReadOnly(t.definition(), mode) : mode;
  }

  /** What a read-only endpoint allows of {@code mode}: never Approval, never a tool that only changes data. */
  public static Mode clampReadOnly(ToolDefinition t, Mode mode) {
    if (t.nature() == ToolDefinition.Nature.MUTATION || mode == Mode.APPROVAL || mode == Mode.BYPASS) {
      return Mode.OFF;
    }
    if (mode == Mode.READ_ONLY && !t.readOnlyModeAllowed()) {
      return Mode.OFF;
    }
    return mode;
  }

  /**
   * Why {@code mode} can't be set on a pair, or empty when it can.
   *
   * @param roConfirmed whether a plugin or MCP server tool has a current Read only confirmation
   */
  public static Optional<String> refusal(Offered t, Mode mode, boolean readOnlyEndpoint, boolean roConfirmed,
      String slug) {
    ToolDefinition d = t.definition();
    if (readOnlyEndpoint && mode != Mode.OFF && clampReadOnly(d, mode) == Mode.OFF) {
      return Optional.of(slug + " is read only: " + d.name() + " can only be Off there");
    }
    if (mode == Mode.READ_ONLY && !d.readOnlyModeAllowed()) {
      return Optional.of(d.name() + " changes data, so Read only isn't available: choose Approval, Bypass or Off");
    }
    if (mode == Mode.READ_ONLY && t.needsReadOnlyConfirmation() && !roConfirmed) {
      return Optional.of("Confirm that " + d.name() + " only reads before you set it to Read only: its annotations "
          + "were declared by " + t.declaredBy() + ", not verified");
    }
    return Optional.empty();
  }

  public static String label(Mode mode) {
    return switch (mode) {
      case OFF -> "Off";
      case READ_ONLY -> "Read only";
      case APPROVAL -> "Approval";
      case BYPASS -> "Bypass";
    };
  }
}
