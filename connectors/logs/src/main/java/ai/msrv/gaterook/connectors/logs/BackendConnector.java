package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** A concrete service's catalog, settings and tools over the shared transport implementation. */
public final class BackendConnector implements Connector {

  private final String type;
  private final LogsConnector delegate;
  private final ConnectorManifest manifest;

  public BackendConnector(String type, CloudCredentials cloud) {
    this(type, cloud, Clock.systemUTC());
  }

  public BackendConnector(String type, CloudCredentials cloud, Clock clock) {
    if (!List.of("gcp-logging", "loki", "cloudwatch", "opensearch", "elasticsearch").contains(type)) {
      throw new IllegalArgumentException("Unknown log or search service: " + type);
    }
    this.type = type;
    this.delegate = new LogsConnector(cloud, clock);
    ConnectorManifest shared = delegate.manifest();
    String name = switch (type) {
      case "gcp-logging" -> "GCP Cloud Logging";
      case "cloudwatch" -> "AWS CloudWatch";
      case "opensearch" -> "OpenSearch";
      case "elasticsearch" -> "Elasticsearch";
      default -> "Loki";
    };
    String description = switch (type) {
      case "gcp-logging" -> "Search Google Cloud logs with native Logs Explorer filters and scoped credentials.";
      case "cloudwatch" -> "Search AWS CloudWatch log groups with Logs Insights or filter patterns.";
      case "opensearch" -> "Query OpenSearch indices and use its REST API. Administrative requests require approval by default.";
      case "elasticsearch" -> "Query Elasticsearch indices and use its REST API. Administrative requests require approval by default.";
      default -> "Search and tail Loki logs with LogQL and scoped credentials.";
    };
    List<String> files = switch (type) {
      case "gcp-logging" -> List.of("~/.config/gcloud/application_default_credentials.json");
      case "cloudwatch", "opensearch" -> List.of("~/.aws/config", "~/.aws/credentials");
      default -> List.of();
    };
    this.manifest = ConnectorManifest.builder(type, shared.version(), shared.kind()).name(name)
        .icon(isSearch(type) ? "database" : "scroll-text")
        .group(isSearch(type) ? "Databases" : "Logs").description(description)
        .configSchema(configurationSchema(shared.configSchema(), type))
        .policySchema(isSearch(type) ? null : shared.policySchema())
        .tools(LogsTools.forBackend(shared.tools(), type, false))
        .access(new ConnectorManifest.RequiredAccess(List.of(), List.of(), files)).build();
  }

  @Override public ConnectorManifest manifest() { return manifest; }

  @Override
  public boolean supportsTool(String tool, ConnectionConfig cfg) {
    if (manifest.tools().stream().noneMatch(definition -> definition.name().equals(tool))) return false;
    try {
      return delegate.supportsTool(internalTool(tool), configuration(cfg));
    } catch (ConnectorException e) {
      return false;
    }
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    try {
      return delegate.classify(internalCall(call), configuration(cfg), policy);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("unsupported_tool"), e.getMessage(), null);
    }
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    try {
      return delegate.describeAction(internalCall(call), configuration(cfg), call.tool());
    } catch (ConnectorException e) {
      return ActionPreview.of(ActionPreview.Kind.JSON, "json", call.arguments().toString(), cfg.name(),
          call.tool() + " on " + cfg.name());
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ToolCall internal = internalCall(call);
    if (ctx.approved() && !isSearch(type)) {
      JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied(
          "The authorization contains no log request. Nothing was executed."));
      if (!internal.tool().equals(payload.path("tool").asString(""))) {
        throw ConnectorException.denied("The authorized log operation does not match the requested tool.");
      }
    }
    return delegate.execute(internal, configuration(cfg), secrets, ctx);
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      return delegate.test(configuration(cfg), secrets, ctx);
    } catch (ConnectorException e) {
      return TestResult.failure("config", e.getMessage(), null);
    }
  }

  @Override
  public TestResult discoverProjects(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    if (!type.equals("gcp-logging")) return Connector.super.discoverProjects(cfg, secrets, ctx);
    try {
      return delegate.discoverProjects(configuration(cfg), secrets, ctx);
    } catch (ConnectorException e) {
      return TestResult.failure("config", e.getMessage(), null);
    }
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      return delegate.health(configuration(cfg), secrets, ctx);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    Optional<JsonNode> result = delegate.describe(configuration(cfg), secrets, ctx, object);
    if (isSearch(type) && result.orElse(null) instanceof ObjectNode details) {
      details.put("scope", "Server credentials and permissions control all REST targets, including body-level indices.");
      details.remove("tailWaitsUpToSeconds");
      details.remove("sources");
    }
    return result;
  }

  @Override public void onConnectionChanged(String id) { delegate.onConnectionChanged(id); }
  @Override public void onConnectionRemoved(String id) { delegate.onConnectionRemoved(id); }
  @Override public void close() { delegate.close(); }

  private ToolCall internalCall(ToolCall call) throws ConnectorException {
    if (manifest.tools().stream().noneMatch(tool -> tool.name().equals(call.tool()))) {
      throw ConnectorException.badInput(manifest.name() + " has no tool " + call.tool() + ".");
    }
    return new ToolCall(call.callId(), internalTool(call.tool()), call.connection(), call.arguments(), call.template());
  }

  private String internalTool(String tool) {
    if (tool.equals(LogsTools.searchName(type))) return "logs_search";
    if (tool.equals(LogsTools.tailName(type))) return "logs_tail";
    return tool;
  }

  private ConnectionConfig configuration(ConnectionConfig cfg) throws ConnectorException {
    if (cfg.config() == null || !cfg.config().isObject()) {
      throw ConnectorException.badInput("The connector settings must be an object.");
    }
    String backend = cfg.config().path("backend").asString(type);
    if (!type.equals(cfg.type()) || !type.equals(backend)) {
      throw ConnectorException.badInput("This connection must use the " + manifest.name() + " connector.");
    }
    ObjectNode config = (ObjectNode) cfg.config().deepCopy();
    config.put("backend", type);
    if (isSearch(type)) {
      if (!config.path("apiAccess").asString("full").equals("full")) {
        throw ConnectorException.badInput("The " + manifest.name() + " connector uses full REST API access.");
      }
      config.put("apiAccess", "full");
    }
    return new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(), config,
        cfg.secretFields(), cfg.metadata());
  }

  /** Each service uses its canonical schema or legacy branch, never a second editable schema copy. */
  public static JsonNode configurationSchema(JsonNode shared, String type) {
    if (type.equals("elasticsearch")) {
      return SchemaResources.of(LogsConnector.class, "logs").load("elasticsearch.config");
    }
    ObjectNode schema = null;
    for (JsonNode branch : shared.path("oneOf")) {
      if (type.equals(branch.at("/properties/backend/const").asString(""))) {
        schema = (ObjectNode) branch.deepCopy();
        break;
      }
    }
    if (schema == null) throw new IllegalArgumentException("Unknown backend " + type);
    schema.put("$schema", "https://json-schema.org/draft/2020-12/schema").put("type", "object");
    ObjectNode properties = (ObjectNode) schema.path("properties");
    properties.putObject("backend").put("type", "string").put("const", type).put("default", type);
    if (type.equals("gcp-logging")) schema.set("x-project-selection", shared.path("x-project-selection").deepCopy());
    if (isSearch(type)) {
      schema.remove("allOf");
      for (String field : List.of("indices", "timestampField", "messageField", "severityField", "labelFields",
          "scopeQuery", "advanced")) properties.remove(field);
      properties.putObject("apiAccess").put("type", "string").put("const", "full").put("default", "full");
      ((ObjectNode) properties.path("caCertificate")).remove("x-section");
    }
    Set<String> references = new LinkedHashSet<>();
    collectReferences(schema, references);
    if (!references.isEmpty()) {
      ObjectNode definitions = schema.putObject("$defs");
      for (String reference : references) definitions.set(reference, shared.path("$defs").path(reference).deepCopy());
    }
    return schema;
  }

  private static boolean isSearch(String type) {
    return type.equals("opensearch") || type.equals("elasticsearch");
  }

  private static void collectReferences(JsonNode node, Set<String> references) {
    if (node.isObject()) {
      String reference = node.path("$ref").asString("");
      if (reference.startsWith("#/$defs/")) references.add(reference.substring("#/$defs/".length()));
      for (var property : node.properties()) collectReferences(property.getValue(), references);
    } else if (node.isArray()) {
      for (JsonNode child : node) collectReferences(child, references);
    }
  }
}
