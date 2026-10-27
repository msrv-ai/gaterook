package ai.msrv.gaterook.connector.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** One call after schema validation; the core removed {@code connection} from the arguments and resolved it. */
public record ToolCall(String callId, String tool, String connection, ObjectNode arguments,
    TemplateBinding template) {

  /** A tool compiled from a template; {@code version} is part of the approval digest. */
  public record TemplateBinding(String templateId, int version, String opKey, JsonNode binding) {}

  public String string(String field) {
    JsonNode n = arguments.get(field);
    return n == null || n.isNull() ? null : n.asString();
  }

  public int integer(String field, int dflt) {
    JsonNode n = arguments.get(field);
    return n == null || n.isNull() ? dflt : n.asInt(dflt);
  }
}
