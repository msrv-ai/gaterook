package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolDefinition.Risk;
import java.util.List;
import java.util.Set;
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
  public static final String OPEN_CONNECTION_IMPORT = "open_connection_import";
  public static final String CONNECTION_IMPORT_SCHEMA = "connection_import_schema";
  public static final String CONNECTION_IMPORT_PROGRESS = "connection_import_progress";
  public static final String SUBMIT_CONNECTION_IMPORT = "submit_connection_import";
  public static final String FIND_TOOLS = "find_tools";
  public static final String CALL_TOOL = "call_tool";
  public static final String CALL_READ_TOOL = "call_read_tool";

  /** The endpoint-wide tools with a mode, in the kit's order. */
  public static final List<String> WITH_MODE = List.of(LIST_CONNECTIONS, DESCRIBE_CONNECTION);

  /** The tools that run another tool of the endpoint by its name, as if the client had called it (§5.7). */
  public static final Set<String> RUNS_TOOLS = Set.of(CALL_TOOL, CALL_READ_TOOL);

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
        .description("Shows a pending request you already received an approval handle for. Call once to show its "
            + "review card, then poll approval_status for the execution result. Do not call for completed requests "
            + "or calls that run with bypass; report a temporary bypass's expiry as text. "
            + "The card can decline or open GateRook; "
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

  public static ToolDefinition connectionImportSchema() {
    ObjectNode input = importInput();
    ((ObjectNode) input.get("properties")).putObject("type").put("type", "string").put("maxLength", 48)
        .put("description", "Omit for the compact connector catalog. Set to one returned type ID to get its full configSchema, secretFields and referenceOnlyFields.");
    return ToolDefinition.builder(CONNECTION_IMPORT_SCHEMA).title("Get connection import schemas")
        .description("For a user-started import: omit type to list installed connectors and storeKinds; set type to a returned ID for its full configSchema, secretFields and referenceOnlyFields. The complete JSON is returned in text and structuredContent. Requires the session ID and submission token from the import prompt. Does not inspect repositories, read secrets or contact services.")
        .inputSchema(input).annotations(Annotations.read()).nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY).resultShape(ResultShape.JSON)
        .risk(new Risk(false, false, Egress.NONE)).build();
  }

  public static ToolDefinition submitConnectionImport() {
    ObjectNode input = importInput();
    ObjectNode manifest = ((ObjectNode) input.get("properties")).putObject("manifest");
    manifest.put("type", "object").put("additionalProperties", false);
    manifest.putArray("required").add("version").add("connections");
    ObjectNode properties = manifest.putObject("properties");
    properties.putObject("version").put("const", 1);
    properties.putObject("warnings").put("type", "array").put("maxItems", 30).putObject("items").put("type", "string").put("maxLength", 512);
    properties.putObject("connections").put("type", "array").put("maxItems", 50).putObject("items").put("type", "object");
    return ToolDefinition.builder(SUBMIT_CONNECTION_IMPORT).title("Submit discovered connections for review")
        .description("Stage the discovery file at the private importFile path provided by a user-started import. Pass only sessionId and submissionToken; GateRook reads that one predetermined file and opens the review page. Never creates, modifies or deletes connections or settings. The user chooses connections and destination in GateRook. The optional inline manifest is a credential-free fallback and must contain no secrets. Use connection_import_schema for actual config fields. Submit once; do not replace submitted results.")
        .inputSchema(input).annotations(new Annotations(false, false, true, false)).nature(Nature.MUTATION)
        .defaultMode(Mode.BYPASS, Mode.BYPASS).resultShape(ResultShape.JSON)
        .risk(new Risk(false, false, Egress.NONE)).build();
  }

  public static ToolDefinition connectionImportProgress() {
    ObjectNode input = importInput();
    ((tools.jackson.databind.node.ArrayNode) input.get("required")).add("message");
    ((ObjectNode) input.get("properties")).putObject("message").put("type", "string")
        .put("minLength", 1).put("maxLength", ConnectionImportService.MAX_PROGRESS_MESSAGE)
        .put("description", "A short, single-line summary of the current analysis step. Never include credentials, tokens, configuration values or file contents.");
    return ToolDefinition.builder(CONNECTION_IMPORT_PROGRESS).title("Report connection import progress")
        .description("For a user-started import, call before analysis to report that work has started, then at meaningful steps while working. Send the session ID and submission token from the prompt and a short message. GateRook shows the latest update and marks progress paused after 15 minutes without updates. Only available before submission; does not create connections, submit results or extend the session lifetime.")
        .inputSchema(input).annotations(new Annotations(false, false, false, false)).nature(Nature.MUTATION)
        .defaultMode(Mode.BYPASS, Mode.BYPASS).resultShape(ResultShape.JSON)
        .risk(new Risk(false, false, Egress.NONE)).build();
  }

  private static ObjectNode importInput() {
    ObjectNode input = object();
    input.putArray("required").add("sessionId").add("submissionToken");
    ObjectNode properties = (ObjectNode) input.get("properties");
    properties.putObject("sessionId").put("type", "string").put("pattern", "^ci_[a-z0-9]{12}$");
    properties.putObject("submissionToken").put("type", "string").put("minLength", 36).put("maxLength", 36);
    return input;
  }

  /**
   * Searches the tools an endpoint doesn't list ({@link ToolSearch}): only what {@code tools/list} would show of them,
   * never a resource.
   */
  public static ToolDefinition findTools() {
    ObjectNode input = object();
    ObjectNode properties = (ObjectNode) input.get("properties");
    properties.putObject("query").put("type", "string").put("minLength", 1).put("maxLength", 200)
        .put("description", "What you want to do, in a few words, or part of a tool's name: \"jira issues by "
            + "status\", \"slow sql queries\", \"sentry\".");
    ObjectNode names = properties.putObject("names").put("type", "array").put("minItems", 1)
        .put("maxItems", ToolSearch.MAX_NAMES).put("uniqueItems", true)
        .put("description", "Exact tool names instead of a query: their full definitions.");
    names.putObject("items").put("type", "string").put("minLength", 1).put("maxLength", 128);
    properties.putObject("connection").put("type", "string").put("maxLength", 128)
        .put("description", "Only the tools of this connection.");
    properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", ToolSearch.MAX_LIMIT)
        .put("default", ToolSearch.DEFAULT_LIMIT);
    return ToolDefinition.builder(FIND_TOOLS)
        .title("Find tools")
        .description("Finds the tools of this endpoint that aren't listed. Give a query, a few words about what you "
            + "want to do, or names. Each match says what it does, whether it waits for a person's approval and, for "
            + "the best ones, its inputSchema. Run a match that only reads with call_read_tool, any other with "
            + "call_tool.")
        .inputSchema(input)
        .annotations(Annotations.read())
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(ResultShape.JSON)
        .risk(new Risk(false, false, Egress.NONE))
        .build();
  }

  /**
   * Runs a tool that {@code find_tools} found: the call is the named tool's, with its modes, approvals, limits and
   * audit, so this definition promises nothing about what it does.
   */
  public static ToolDefinition callTool() {
    return ToolDefinition.builder(CALL_TOOL)
        .title("Run a found tool")
        .description("Runs a tool of this endpoint by its name, such as one find_tools found, with its arguments. "
            + "The tool's own rules apply: a change waits until a person approves the exact action, and you get a "
            + "handle to poll with approval_status.")
        .inputSchema(runInput())
        .annotations(new Annotations(false, true, false, true))
        .nature(Nature.MUTATION)
        .defaultMode(Mode.APPROVAL, Mode.APPROVAL)
        .resultShape(ResultShape.JSON)
        .risk(new Risk(true, true, Egress.CHANNEL))
        .build();
  }

  /** {@link #callTool} for the tools that only read: an AI app may run it without asking, as it may run them. */
  public static ToolDefinition callReadTool() {
    return ToolDefinition.builder(CALL_READ_TOOL)
        .title("Run a found tool that only reads")
        .description("Runs a tool of this endpoint that only reads (readOnly in find_tools), by its name, with its "
            + "arguments. Refuses a tool that can change data: run that one with call_tool.")
        .inputSchema(runInput())
        .annotations(new Annotations(true, false, false, true))
        .nature(Nature.READ)
        .defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
        .resultShape(ResultShape.JSON)
        .risk(new Risk(true, true, Egress.NONE))
        .build();
  }

  private static ObjectNode runInput() {
    ObjectNode input = object();
    input.putArray("required").add("name");
    ObjectNode properties = (ObjectNode) input.get("properties");
    properties.putObject("name").put("type", "string").put("minLength", 1).put("maxLength", 128)
        .put("description", "The tool's name, as find_tools gives it.");
    properties.putObject("arguments").put("type", "object")
        .put("description", "The tool's own arguments, as its inputSchema describes them.");
    return input;
  }

  /** This app-only action navigates to a caller-owned review; it cannot change connections or settings. */
  public static ToolDefinition openConnectionImport() {
    ObjectNode input = object();
    input.putArray("required").add("sessionId");
    ((ObjectNode) input.get("properties")).putObject("sessionId").put("type", "string")
        .put("pattern", "^ci_[a-z0-9]{12}$");
    return ToolDefinition.builder(OPEN_CONNECTION_IMPORT).title("Open import in GateRook")
        .description("Opens a submitted import owned by this caller and endpoint for review in GateRook. "
            + "Navigation only: cannot create, change or delete connections, change settings, or accept an import.")
        .inputSchema(input).annotations(new Annotations(false, false, true, false)).nature(Nature.MUTATION)
        .defaultMode(Mode.BYPASS, Mode.BYPASS).resultShape(ResultShape.JSON)
        .risk(new Risk(false, false, Egress.NONE)).build();
  }
}
