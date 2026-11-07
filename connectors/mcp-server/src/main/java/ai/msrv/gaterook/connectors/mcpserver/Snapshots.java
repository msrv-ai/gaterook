package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolDefinition.Risk;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Snapshot entries of an MCP server's tools (one JSON object per tool, stored by the core in
 * {@code mcp_server.tools_snapshot}) and the {@link ToolDefinition}s published from them.
 *
 * <p>A tool is a READ only if it declares {@code readOnlyHint: true} and not {@code destructiveHint: true}; it
 * starts in Read only on an endpoint. Everything else is a mutation and starts in Approval. The declarations
 * remain unverified. Unset annotations take the protocol's pessimistic defaults (destructive, not idempotent, open world).
 * Every tool counts as private data, untrusted content and a channel out for the risk callout: the gateway can't see
 * what a server does with what it is given.
 */
final class Snapshots {

  static final int MAX_DESCRIPTION = 2_000;
  static final int MAX_TITLE = 200;

  /** Entries and the tools that can't be published. */
  record Mapped(List<JsonNode> tools, List<McpServerRuntime.Rejected> rejected) {}

  private Snapshots() {}

  /**
   * The entries of what a server lists now, under {@code prefix}, in its order. Each tool is read as the MCP Java
   * SDK's {@code Tool}, the type its definition hash has always been taken over: a tool the server didn't change keeps
   * its hash, keeping definition-change detection stable.
   */
  static Mapped fromJson(String prefix, List<ObjectNode> listed, Function<String, Optional<String>> reserved) {
    List<McpSchema.Tool> tools = new ArrayList<>();
    List<ObjectNode> raw = new ArrayList<>();
    List<McpServerRuntime.Rejected> unreadable = new ArrayList<>();
    for (ObjectNode t : listed) {
      try {
        tools.add(tool(t));
        raw.add(t);
      } catch (RuntimeException e) {
        unreadable.add(new McpServerRuntime.Rejected(Texts.clean(Texts.str(t.get("name"), "(no name)"), 128, false),
            "its definition isn't a valid MCP tool"));
      }
    }
    Mapped mapped = of(prefix, tools, raw, reserved);
    if (unreadable.isEmpty()) {
      return mapped;
    }
    List<McpServerRuntime.Rejected> all = new ArrayList<>(mapped.rejected());
    all.addAll(unreadable);
    return new Mapped(mapped.tools(), List.copyOf(all));
  }

  /** A tool of a {@code tools/list} answer as the SDK's type. */
  static McpSchema.Tool tool(ObjectNode json) {
    return Session.MAPPER.convertValue(json, McpSchema.Tool.class);
  }

  /** The entries of what a server lists now, under {@code prefix}, in its order. */
  static Mapped of(String prefix, List<McpSchema.Tool> listed, Function<String, Optional<String>> reserved) {
    return of(prefix, listed, null, reserved);
  }

  /** @param raw the tools as the server sent them, in the same order ({@code null}: none) */
  private static Mapped of(String prefix, List<McpSchema.Tool> listed, List<ObjectNode> raw,
      Function<String, Optional<String>> reserved) {
    List<JsonNode> out = new ArrayList<>();
    List<McpServerRuntime.Rejected> rejected = new ArrayList<>();
    Set<String> taken = new HashSet<>();
    Set<String> originals = new HashSet<>();
    for (int index = 0; index < listed.size(); index++) {
      McpSchema.Tool tool = listed.get(index);
      ObjectNode json = raw == null ? null : raw.get(index);
      String original = tool.name();
      String shown = original == null ? "(no name)" : Texts.clean(original, 128, false);
      if (original != null && !originals.add(original)) {
        rejected.add(new McpServerRuntime.Rejected(shown, "the server lists it twice"));
        continue;
      }
      ToolNameMapper.Name name = ToolNameMapper.map(prefix, original, taken, reserved);
      if (name.problem() != null) {
        rejected.add(new McpServerRuntime.Rejected(shown, name.problem()));
        continue;
      }
      ToolSchemas.Result input = ToolSchemas.input(tool.inputSchema());
      if (!input.ok()) {
        rejected.add(new McpServerRuntime.Rejected(shown, input.problem()));
        continue;
      }
      ObjectNode annotations = annotations(tool.annotations());
      boolean read = declaresReadOnly(annotations);
      ObjectNode entry = Texts.JSON.createObjectNode();
      entry.put("exposed", name.exposed());
      entry.put("original", original);
      String title = tool.title() != null ? tool.title()
          : tool.annotations() != null ? tool.annotations().title() : null;
      if (title != null) {
        entry.put("title", Texts.clean(title, MAX_TITLE, false));
      }
      entry.put("description", Texts.clean(tool.description() == null ? "" : tool.description(), MAX_DESCRIPTION,
          true));
      entry.set("inputSchema", input.schema());
      if (read) {
        // Only a tool that may run without approval publishes a result schema: a pending handle never matches one.
        ToolSchemas.Result output = ToolSchemas.output(tool.outputSchema());
        if (output.ok() && output.schema() != null) {
          entry.set("outputSchema", output.schema());
        }
      }
      if (annotations != null) {
        entry.set("annotations", annotations);
      }
      entry.put("definitionHash", definitionHash(tool));
      if (json != null) {
        extras(json, entry);
      }
      taken.add(name.exposed());
      out.add(entry);
    }
    return new Mapped(List.copyOf(out), List.copyOf(rejected));
  }

  /**
   * What a tool declares beside its definition, kept in its entry: whether it must, may or must not run as a task
   * ({@code execution.taskSupport}, 2025-11-25), and the MCP App it is shown with ({@code _meta.ui}: its
   * {@code ui://} resource and who may call it). Neither is in the definition hash, so that a hash stays what it was;
   * a tool that runs as a task is still called through the gateway, and an app is read through it.
   */
  static void extras(ObjectNode json, ObjectNode entry) {
    String task = Texts.str(json.path("execution").get("taskSupport"), null);
    if (task != null && (task.equals("required") || task.equals("optional"))) {
      entry.put("taskSupport", task);
    }
    JsonNode ui = json.path("_meta").path("ui");
    ObjectNode app = Texts.JSON.createObjectNode();
    String uri = Texts.str(ui.get("resourceUri"), Texts.str(json.path("_meta").get("ui/resourceUri"), null));
    if (uri != null && uri.startsWith("ui://") && uri.length() <= 2_048 && !Texts.invisible(uri)) {
      app.put("resourceUri", uri);
    }
    if (ui.path("visibility").isArray()) {
      ArrayNode visibility = app.putArray("visibility");
      for (JsonNode v : ui.get("visibility")) {
        if (v.isString() && (v.asString().equals("model") || v.asString().equals("app"))) {
          visibility.add(v.asString());
        }
      }
    }
    if (app.has("resourceUri")) {
      entry.set("ui", app);
    }
  }

  /** {@link #definitionHash(McpSchema.Tool)} of a tool as the server sent it. */
  static String definitionHash(ObjectNode json) {
    return definitionHash(tool(json));
  }

  /**
   * SHA-256 of what the server declared for a tool (name, title, description, both schemas, annotations), before any
   * cleaning: any change the server makes shows, even one the gateway would have normalized away.
   */
  static String definitionHash(McpSchema.Tool tool) {
    Map<String, Object> declared = new LinkedHashMap<>();
    declared.put("name", tool.name());
    declared.put("title", tool.title());
    declared.put("description", tool.description());
    declared.put("inputSchema", tool.inputSchema());
    declared.put("outputSchema", tool.outputSchema());
    declared.put("annotations", tool.annotations() == null ? null : Texts.JSON.valueToTree(tool.annotations()));
    return Texts.sha256(Texts.JSON.valueToTree(declared));
  }

  /** The tool as an endpoint publishes it, {@code server} being the connection's name. */
  static ToolDefinition definition(JsonNode entry, String server) {
    JsonNode annotations = entry.path("annotations");
    boolean read = declaresReadOnly(annotations.isObject() ? (ObjectNode) annotations : null);
    Boolean idempotent = bool(annotations, "idempotentHint");
    Boolean openWorld = bool(annotations, "openWorldHint");
    Boolean destructive = bool(annotations, "destructiveHint");
    String original = entry.path("original").asString();
    String description = Texts.str(entry.path("description"), "");
    if (description.isBlank()) {
      description = original + " of the MCP server " + server + ".";
    }
    ToolDefinition.Builder b = ToolDefinition.builder(entry.path("exposed").asString())
        .title(Texts.str(entry.path("title"), original))
        .description(description)
        .inputSchema(entry.path("inputSchema").deepCopy())
        .defaultMode(read ? Mode.READ_ONLY : Mode.APPROVAL, read ? Mode.READ_ONLY : Mode.APPROVAL)
        .resultShape(ResultShape.JSON)
        .risk(new Risk(true, true, Egress.CHANNEL));
    JsonNode ui = entry.path("ui");
    if (ui.path("resourceUri").isString()) {
      // Its app, by the URI the endpoint publishes it under: a client that renders apps reads it through the gateway.
      ObjectNode meta = Texts.JSON.createObjectNode();
      ObjectNode app = meta.putObject("ui");
      app.put("resourceUri", Federation.exposed(server, ui.get("resourceUri").asString()));
      if (ui.path("visibility").isArray()) {
        app.set("visibility", ui.get("visibility").deepCopy());
      }
      b.meta(meta);
    }
    if (read) {
      b.nature(Nature.READ).annotations(new Annotations(true, false, idempotent == null ? Boolean.FALSE : idempotent,
          openWorld == null ? Boolean.TRUE : openWorld));
      if (entry.path("outputSchema").isObject()) {
        b.outputSchema(entry.path("outputSchema").deepCopy());
      }
    } else {
      b.nature(Nature.MUTATION).annotations(new Annotations(false, destructive == null ? Boolean.TRUE : destructive,
          idempotent == null ? Boolean.FALSE : idempotent, openWorld == null ? Boolean.TRUE : openWorld));
    }
    return b.build();
  }

  /** {@code readOnlyHint: true} and not {@code destructiveHint: true}. */
  static boolean declaresReadOnly(ObjectNode annotations) {
    return annotations != null && Boolean.TRUE.equals(bool(annotations, "readOnlyHint"))
        && !Boolean.TRUE.equals(bool(annotations, "destructiveHint"));
  }

  /** The hints as declared; {@code null} when the server declares none. */
  static ObjectNode annotations(McpSchema.ToolAnnotations a) {
    if (a == null) {
      return null;
    }
    ObjectNode out = Texts.JSON.createObjectNode();
    if (a.readOnlyHint() != null) {
      out.put("readOnlyHint", a.readOnlyHint());
    }
    if (a.destructiveHint() != null) {
      out.put("destructiveHint", a.destructiveHint());
    }
    if (a.idempotentHint() != null) {
      out.put("idempotentHint", a.idempotentHint());
    }
    if (a.openWorldHint() != null) {
      out.put("openWorldHint", a.openWorldHint());
    }
    return out.isEmpty() ? null : out;
  }

  /** The entry named {@code exposed}. */
  static Optional<JsonNode> find(List<JsonNode> tools, String exposed) {
    for (JsonNode t : tools) {
      if (exposed.equals(Texts.str(t.path("exposed"), null))) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /** The entry for upstream name {@code original}. */
  static Optional<JsonNode> byOriginal(List<JsonNode> tools, String original) {
    for (JsonNode t : tools) {
      if (original.equals(Texts.str(t.path("original"), null))) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /** How many tools declare any annotation. */
  static int annotated(List<JsonNode> tools) {
    int n = 0;
    for (JsonNode t : tools) {
      if (t.path("annotations").isObject()) {
        n++;
      }
    }
    return n;
  }

  private static Boolean bool(JsonNode annotations, String key) {
    JsonNode n = annotations == null ? null : annotations.get(key);
    return n != null && n.isBoolean() ? n.asBoolean() : null;
  }
}
