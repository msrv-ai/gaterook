package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolDefinition.Risk;
import java.util.List;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The core's own tools (ARCHITECTURE.md §3, report 01 §3.5): not a connector, no connector {@code execute}.
 * {@code list_connections} and {@code describe_connection} are endpoint-wide pairs ({@code connection_id IS NULL})
 * whose mode the person may set; {@code approval_status} is always there and has no mode. The {@code mcp} module
 * implements them; their definitions live here because the endpoint's tool list carries them. Approval card
 * interactions can decline a caller-owned request or open the app, but cannot approve it.
 */
public final class CoreTools {

  public static final String LIST_CONNECTIONS = "list_connections";
  public static final String DESCRIBE_CONNECTION = "describe_connection";
  public static final String APPROVAL_STATUS = "approval_status";
  public static final String APPROVAL_CARD = "approval_card";
  public static final String APPROVAL_CARD_ACTION = "approval_card_action";

  /** The endpoint-wide tools with a mode, in the kit's order. */
  public static final List<String> WITH_MODE = List.of(LIST_CONNECTIONS, DESCRIBE_CONNECTION);

  private CoreTools() {}

  public static ToolDefinition listConnections() {
    ObjectNode input = object();
    return ToolDefinition.builder(LIST_CONNECTIONS)
        .title("List connections")
        .description("Lists the connections of this endpoint: name, type, environment and what each one is for.")
        .inputSchema(input)
        .annotations(Annotations.read())
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(ResultShape.JSON)
        .risk(new Risk(false, false, Egress.NONE))
        .build();
  }

  /** {@code connection} gets the endpoint's connections as its enum, like every connector tool. */
  public static ToolDefinition describeConnection() {
    ObjectNode input = object();
    input.putArray("required").add("connection");
    ObjectNode properties = (ObjectNode) input.get("properties");
    properties.putObject("connection").put("type", "string");
    properties.putObject("object").put("type", "string").put("maxLength", 200)
        .put("description", "A table, collection or log group to describe instead of the whole connection.");
    return ToolDefinition.builder(DESCRIBE_CONNECTION)
        .title("Describe a connection")
        .description("Describes one connection: what it holds (schemas, collections, log groups) and the limits that "
            + "apply. Credentials are never shown.")
        .inputSchema(input)
        .annotations(Annotations.read())
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(ResultShape.JSON)
        .risk(new Risk(true, false, Egress.NONE))
        .build();
  }

  public static ToolDefinition approvalStatus() {
    ObjectNode input = object();
    input.putArray("required").add("handle");
    ObjectNode properties = (ObjectNode) input.get("properties");
    properties.putObject("handle").put("type", "string").put("pattern", "^ap_[a-z0-9]{12}$");
    properties.putObject("wait_seconds").put("type", "integer").put("minimum", 0).put("maximum", 25)
        .put("default", 0);
    return ToolDefinition.builder(APPROVAL_STATUS)
        .title("Check an approval request")
        .description("Returns the state of an approval request you got a handle for: pending, completed with the "
            + "result, rejected or expired. Waits up to wait_seconds for a decision.")
        .inputSchema(input)
        .annotations(Annotations.read())
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(ResultShape.JSON)
        .risk(new Risk(false, false, Egress.NONE))
        .build();
  }

  public static ToolDefinition withMode(String name) {
    return switch (name) {
      case LIST_CONNECTIONS -> listConnections();
      case DESCRIBE_CONNECTION -> describeConnection();
      default -> throw new IllegalArgumentException("Not a core tool with a mode: " + name);
    };
  }

  /** Rendering is separate from polling so a host does not recreate a card on every status check. */
  public static ToolDefinition approvalCard() {
    return ToolDefinition.builder(APPROVAL_CARD)
        .title("Show an approval card")
        .description("Shows a request you already received an approval handle for. Call once to show its review "
            + "card, then poll approval_status for the execution result. The card can decline or open GateRook; "
            + "approval always requires the person to confirm the exact action in GateRook.")
        .inputSchema(cardInput(false)).outputSchema(cardOutput())
        .annotations(Annotations.read()).nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY).resultShape(ResultShape.JSON)
        .risk(new Risk(false, false, Egress.NONE)).build();
  }

  /** App-only is a host presentation hint, never proof of a human decision. There is no approve operation. */
  public static ToolDefinition approvalCardAction() {
    return ToolDefinition.builder(APPROVAL_CARD_ACTION)
        .title("Interact with an approval card")
        .description("Refreshes, declines, or opens a caller-owned approval in GateRook. Cannot approve or run it.")
        .inputSchema(cardInput(true))
        .annotations(new Annotations(false, true, true, false)).nature(Nature.MUTATION)
        .defaultMode(Mode.BYPASS, Mode.BYPASS).resultShape(ResultShape.JSON)
        .risk(new Risk(false, false, Egress.NONE)).build();
  }

  private static ObjectNode cardInput(boolean interaction) {
    ObjectNode input = object();
    input.putArray("required").add("handle");
    ObjectNode properties = (ObjectNode) input.get("properties");
    properties.putObject("handle").put("type", "string").put("pattern", "^ap_[a-z0-9]{12}$");
    if (interaction) {
      ((tools.jackson.databind.node.ArrayNode) input.get("required")).add("action");
      properties.putObject("action").put("type", "string").putArray("enum")
          .add("status").add("open").add("deny");
    }
    return input;
  }

  private static ObjectNode cardOutput() {
    ObjectNode schema = object();
    schema.putArray("required").add("kind").add("version").add("handle").add("tool").add("connection")
        .add("environment").add("status").add("expiresAt").add("serverTime").add("request")
        .add("commentRequired").add("canDeny").add("canOpen").add("approvalInApp");
    ObjectNode p = (ObjectNode) schema.get("properties");
    p.putObject("kind").put("const", "gaterook.approval-card");
    p.putObject("version").put("const", 1);
    for (String key : List.of("handle", "tool", "connection", "environment", "status", "expiresAt", "serverTime")) {
      p.putObject(key).put("type", "string");
    }
    for (String key : List.of("commentRequired", "canDeny", "canOpen")) {
      p.putObject(key).put("type", "boolean");
    }
    p.putObject("approvalInApp").put("const", true);
    ObjectNode request = object();
    request.putArray("required").add("language").add("text").add("truncated");
    ObjectNode rp = (ObjectNode) request.get("properties");
    rp.putObject("language").put("type", "string");
    rp.putObject("text").put("type", "string");
    rp.putObject("truncated").put("type", "boolean");
    p.set("request", request);
    return schema;
  }

  private static ObjectNode object() {
    ObjectNode input = JsonNodeFactory.instance.objectNode();
    input.put("type", "object");
    input.put("additionalProperties", false);
    input.putObject("properties");
    return input;
  }
}
