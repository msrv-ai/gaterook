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
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.connectors.ManifestCodec;
import ai.msrv.gaterook.core.connectors.PluginBuild;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
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
import tools.jackson.databind.node.ObjectNode;

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
   * @param declaredBy        for MCP server tools and the tools of a plugin whose build GateRook's catalog didn't
   *                          sign: who declared the annotations ("Declared by github-mcp, not verified"); {@code null}
   *                          for built-ins, templates and the tools of a signed plugin, whose code GateRook reviewed
   * @param provenanceVersion the version a Read only confirmation of the tool is bound to: that of the connector
   *                          serving the connection (a plugin's version; an MCP server connection's upstream server
   *                          version, which its connector reports as its manifest version), not the type's catalog card
   * @param template          the template it was compiled from, or {@code null}
   */
  public record Offered(String toolKey, ToolDefinition definition, ConnectorManifest.Kind kind, String declaredBy,
      String provenanceVersion, TemplateService.Compiled template, TemplateKind.CompiledTool compiled) {

    /**
     * Tools whose Read only needs the person's confirmation per tool, bound to its definition and version: those of an
     * unsigned plugin, which only declares what they do.
     */
    public boolean needsReadOnlyConfirmation() {
      return kind == ConnectorManifest.Kind.PLUGIN && declaredBy != null;
    }

    /**
     * Tools that start in Read only from their annotations, without a separate per-tool confirmation: MCP tools
     * explicitly declared read only, and the reads of a signed plugin.
     */
    public boolean automaticReadOnly() {
      boolean declaredRead = definition.readOnlyModeAllowed()
          && Boolean.TRUE.equals(definition.annotations().readOnlyHint());
      return switch (kind) {
        case MCP_SERVER -> declaredRead;
        case PLUGIN -> declaredBy == null && declaredRead && definition.nature() == ToolDefinition.Nature.READ;
        case BUILT_IN -> false;
      };
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
      if (ConnectionService.needsSetup(m.config())) continue;
      groups.computeIfAbsent(groupKey(m), k -> new ArrayList<>()).add(m);
    }
    Map<String, List<Offered>> byConnection = new HashMap<>();
    for (List<Member> group : groups.values()) {
      byConnection.putAll(offeredByGroup(group, slug, env));
    }
    Map<String, List<Offered>> out = new LinkedHashMap<>();
    for (Member m : members) {
      out.put(m.config().id(), byConnection.getOrDefault(m.config().id(), List.of()));
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
    if (ConnectionService.needsSetup(cfg)) return List.of();
    String key = groupKey(self);
    List<Member> group = members.stream().filter(m -> !ConnectionService.needsSetup(m.config()))
        .filter(m -> groupKey(m).equals(key)).toList();
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
    // A tool that a module's type shares with a compiled-in one is the family's, whatever build worded it.
    List<ToolDefinition> tools = registry.published(manifest.id(),
        connector.map(c -> c.tools(view)).orElse(manifest.tools()));
    Map<String, List<Offered>> out = new LinkedHashMap<>();
    for (Member m : group) {
      ConnectionConfig cfg = m.config();
      List<Offered> offered = new ArrayList<>();
      for (ToolDefinition t : tools) {
        if (connector.isPresent() && !connector.get().supportsTool(t.name(), cfg)) {
          continue;
        }
        offered.add(new Offered(key(manifest, cfg, t.name()), t, manifest.kind(),
            declaredBy(manifest, cfg, connector.orElse(null)), provenance, null, null));
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

  /**
   * Who declared the annotations of a connection's tools, when GateRook doesn't vouch for them: an MCP server, or a
   * plugin unless the build that serves it is signed. A plugin whose connector isn't running here counts as unsigned.
   */
  private static String declaredBy(ConnectorManifest manifest, ConnectionConfig cfg, Connector connector) {
    return switch (manifest.kind()) {
      case BUILT_IN -> null;
      case PLUGIN -> connector instanceof PluginBuild build && build.signed() ? null : manifest.name();
      case MCP_SERVER -> cfg.name();
    };
  }

  /**
   * SHA-256 of a tool's published definition: what a Read only confirmation and a temporary bypass are bound to. The
   * app a tool shows ({@code _meta.ui}: the URI the endpoint publishes it under and who may call the tool) is part of
   * it; a tool without one hashes as it always did.
   */
  public static String definitionHash(ToolDefinition t) {
    return CanonicalJson.sha256Hex(published(t));
  }

  private static ObjectNode published(ToolDefinition t) {
    ObjectNode published = ManifestCodec.writeTool(t);
    if (t.meta() != null && !t.meta().isNull()) {
      published.set("meta", t.meta().deepCopy());
    }
    return published;
  }

  /**
   * The definition to record for a tool that comes in as new or that a person sets a mode for: that of an external
   * MCP server's tool, else {@code null}. Built-in tools, templates and plugins have their own binding (a plugin's
   * Read only confirmation) or none. It is the SHA-256 of what the server declared for the tool, as its connector
   * carries it in {@link ToolDefinition#declared()}: the hash of its definition as the server sent it (name, title,
   * description, both schemas and annotations, before any cleaning) and the app it shows ({@code _meta.ui}). Nothing
   * GateRook derives from them is in it: not the title, description and hints as published, which fall back to
   * defaults where the server declares none, not whether the result schema is published, nor the nature, the default
   * modes, the result shape or the risk, so that a later change of that derivation sends no tool back to Approval. The
   * connection's name isn't in it either: renaming the connection changes neither the server nor the tool. A
   * definition that carries no declared values (the MCP server connector gives them for every tool) is recorded by its
   * {@link #definitionHash}, which covers what is derived as well. A temporary bypass and a Read only confirmation use
   * {@link #definitionHash}, which this leaves as it is; the check before a call compares the connector's own hash of
   * what the server declares (the one carried in the declared values) and the app it shows.
   */
  public static String reviewedDefinition(Offered offered) {
    if (offered.kind() != ConnectorManifest.Kind.MCP_SERVER) {
      return null;
    }
    JsonNode declared = offered.definition().declared();
    if (declared == null || !declared.isObject() || declared.isEmpty()) {
      return definitionHash(offered.definition());
    }
    return CanonicalJson.sha256Hex(declared);
  }

  /**
   * Whether an external MCP server's tool publishes the definition its row's mode was set or reviewed for
   * (ARCHITECTURE.md §7.5). Read only and Bypass hold only while it does; a row without a record of one doesn't count
   * as reviewed. Always {@code true} for other tools.
   */
  public static boolean reviewed(EndpointToolEntity row, Offered offered) {
    String current = reviewedDefinition(offered);
    return current == null || row != null && current.equals(row.getReviewedDefinition());
  }

  /**
   * Where a tool goes when it may no longer run without a person: Approval, or Off on a read-only endpoint, which
   * never waits for one.
   */
  public static Mode demoted(ToolDefinition t, boolean readOnlyEndpoint) {
    return readOnlyEndpoint ? clampReadOnly(t, Mode.APPROVAL) : Mode.APPROVAL;
  }

  // ── Modes ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The mode a pair starts with (ARCHITECTURE.md §5.1): on a standard endpoint nothing starts Off, so agents get every
   * tool a connection offers. Built-in tools take the manifest's default for the endpoint's environment, which is Read
   * only for what reads and Approval for what changes data, runs commands or reads secrets; a default of Off or Bypass
   * starts in Approval. MCP tools and the tools of a plugin start in Read only when {@link Offered#automaticReadOnly()}
   * and in Approval otherwise. On a read-only endpoint, no Approval and nothing that changes data.
   */
  public static Mode initial(Offered t, Env endpointEnv, boolean readOnlyEndpoint) {
    Mode mode = endpointEnv == Env.PROD ? t.definition().prodDefaultMode() : t.definition().defaultMode();
    if (t.kind() != ConnectorManifest.Kind.BUILT_IN) {
      mode = t.automaticReadOnly() ? Mode.READ_ONLY : Mode.APPROVAL;
    } else if (mode == Mode.OFF || mode == Mode.BYPASS) {
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
   * @param roConfirmed whether a plugin tool has a current Read only confirmation
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
