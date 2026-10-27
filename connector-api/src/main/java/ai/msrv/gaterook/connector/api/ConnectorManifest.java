package ai.msrv.gaterook.connector.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Catalog card + connection form + Policies section. Stored in the DB for plugins, so tools/list never starts a
 * host. Plugins use {@link #builder}: the canonical constructor may grow.
 *
 * @param id           connector type, e.g. {@code "postgres"}
 * @param icon         Lucide icon name
 * @param group        Databases | Remote access | Logs | Kubernetes | Cloud | HTTP and MCP
 * @param configSchema JSON Schema 2020-12 with the {@code x-…} dictionary; {@code writeOnly} marks a secret
 * @param policySchema the connector's Policies section, or {@code null}
 * @param access       "Needs access to"
 * @param dynamicTools tools come from the resource itself (MCP servers)
 */
public record ConnectorManifest(String id, String version, Kind kind, String name, String icon, String group,
    String description, JsonNode configSchema, JsonNode policySchema, List<ToolDefinition> tools,
    RequiredAccess access, boolean dynamicTools, String connectorApi) {

  public enum Kind { BUILT_IN, PLUGIN, MCP_SERVER }

  public record RequiredAccess(List<String> hosts, List<String> commands, List<String> files) {
    public static RequiredAccess none() {
      return new RequiredAccess(List.of(), List.of(), List.of());
    }
  }

  public static Builder builder(String id, String version, Kind kind) {
    return new Builder(id, version, kind);
  }

  public static final class Builder {
    private final String id;
    private final String version;
    private final Kind kind;
    private String name;
    private String icon = "puzzle";
    private String group = "HTTP and MCP";
    private String description = "";
    private JsonNode configSchema;
    private JsonNode policySchema;
    private List<ToolDefinition> tools = List.of();
    private RequiredAccess access = RequiredAccess.none();
    private boolean dynamicTools;

    private Builder(String id, String version, Kind kind) {
      this.id = id;
      this.version = version;
      this.kind = kind;
      this.name = id;
    }

    public Builder name(String v) {
      name = v;
      return this;
    }

    public Builder icon(String v) {
      icon = v;
      return this;
    }

    public Builder group(String v) {
      group = v;
      return this;
    }

    public Builder description(String v) {
      description = v;
      return this;
    }

    public Builder configSchema(JsonNode v) {
      configSchema = v;
      return this;
    }

    public Builder policySchema(JsonNode v) {
      policySchema = v;
      return this;
    }

    public Builder tools(List<ToolDefinition> v) {
      tools = List.copyOf(v);
      return this;
    }

    public Builder access(RequiredAccess v) {
      access = v;
      return this;
    }

    public Builder dynamicTools(boolean v) {
      dynamicTools = v;
      return this;
    }

    public ConnectorManifest build() {
      return new ConnectorManifest(id, version, kind, name, icon, group, description, configSchema, policySchema,
          tools, access, dynamicTools, ApiVersion.CURRENT);
    }
  }
}
