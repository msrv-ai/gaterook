package ai.msrv.gaterook.connector.api;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A no-code template kind. Storage, append-only versions and the binding to a connection ("Runs on") belong to the
 * core; the kind validates and compiles.
 */
public interface TemplateKind {

  /** {@code sql-query | ssh-runbook | openapi}. */
  String kind();

  List<String> connectionTypes();

  /** The template editor form. */
  JsonNode definitionSchema();

  /** SQL templates are classified here, on save. */
  CompileResult compile(JsonNode definition, ConnectionConfig runsOn);

  /** Playground and editor; no I/O. */
  ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn);

  /** {@code opKey}: "q" | "POST /refunds". */
  record CompiledTool(String opKey, ToolDefinition definition, JsonNode binding) {}

  /** {@code contributions}: runbooks for ssh_run. */
  record CompileResult(List<CompiledTool> tools, List<JsonNode> contributions, List<String> errors,
      List<String> warnings, String digest) {

    public boolean ok() {
      return errors.isEmpty();
    }
  }
}
