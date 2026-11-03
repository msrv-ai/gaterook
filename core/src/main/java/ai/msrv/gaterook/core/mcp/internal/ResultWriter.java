package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.platform.CoreBuild;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes results for the request's era as trees (ARCHITECTURE.md A1, §5.1): no databind of records, so nothing needs
 * reflection metadata in the native image. Modern results always carry {@code resultType} and
 * {@code _meta["io.modelcontextprotocol/serverInfo"]}; every {@code CacheableResult} ({@code server/discover},
 * {@code tools/list}, {@code resources/list}, {@code resources/templates/list}, {@code resources/read},
 * {@code prompts/list}) also {@code ttlMs} and {@code cacheScope: "private"} (the answer depends on the token). Legacy
 * results have none of these. With 2025-03-26 a {@code resource_link} becomes text with its URI (the type is newer).
 * Images, audio and embedded resources pass as their blocks: every revision the gateway speaks has them.
 */
public final class ResultWriter {

  public static final String SERVER_NAME = "gaterook";
  public static final String META_SERVER = "io.modelcontextprotocol/serverInfo";

  private ResultWriter() {}

  /** A complete result, finished for the era; {@code ttlMs} {@code < 0}: not cacheable. */
  public static ObjectNode complete(Envelope env, ObjectNode result, long ttlMs) {
    if (env.era() == Era.MODERN) {
      result.put("resultType", "complete");
      if (ttlMs >= 0) {
        result.put("ttlMs", ttlMs);
        result.put("cacheScope", "private");
      }
      serverInfo(result);
    }
    return result;
  }

  /** A task in place of the result (the Tasks extension of 2026-07-28: {@code resultType: task}). */
  public static ObjectNode task(ObjectNode task) {
    task.put("resultType", "task");
    serverInfo(task);
    return task;
  }

  /** {@code input_required} (MRTR, modern only). */
  public static ObjectNode inputRequired(ObjectNode inputRequests, String requestState) {
    ObjectNode r = JsonNodeFactory.instance.objectNode();
    r.put("resultType", "input_required");
    if (inputRequests != null) {
      r.set("inputRequests", inputRequests);
    }
    if (requestState != null) {
      r.put("requestState", requestState);
    }
    serverInfo(r);
    return r;
  }

  private static void serverInfo(ObjectNode result) {
    ObjectNode meta = result.has("_meta") && result.get("_meta").isObject() ? (ObjectNode) result.get("_meta")
        : result.putObject("_meta");
    meta.putObject(META_SERVER).put("name", SERVER_NAME).put("version", CoreBuild.VERSION);
  }

  /**
   * A {@code CallToolResult} from a neutral result.
   *
   * @param callId  the gateway's id of the call ({@code ai.msrv.gaterook/callId}), or {@code null}
   * @param traceId the trace of the call ({@code ai.msrv.gaterook/traceId}), or {@code null}
   */
  public static ObjectNode callToolResult(Envelope env, ToolResult r, String callId, String traceId,
      ObjectNode extraMeta) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode content = out.putArray("content");
    boolean oldest = "2025-03-26".equals(env.version());
    for (ToolResult.Content c : r.content()) {
      content.add(block(c, oldest));
    }
    JsonNode s = r.structuredContent();
    if (s != null && !s.isNull()) {
      // structuredContent is an object in both revisions: anything else is wrapped.
      out.set("structuredContent", s.isObject() ? s : JsonNodeFactory.instance.objectNode().set("value", s));
    }
    out.put("isError", r.isError());
    ObjectNode meta = JsonNodeFactory.instance.objectNode();
    if (callId != null) {
      meta.put("ai.msrv.gaterook/callId", callId);
    }
    if (traceId != null) {
      meta.put("ai.msrv.gaterook/traceId", traceId);
    }
    if (r.maskedCount() > 0 || callId != null) {
      meta.putObject("ai.msrv.gaterook/masked").put("fields", r.maskedCount());
    }
    if (r.truncated()) {
      meta.putObject("ai.msrv.gaterook/truncated").put("reason", r.truncatedReason());
    }
    if (extraMeta != null) {
      meta.setAll(extraMeta);
    }
    if (!meta.isEmpty()) {
      out.set("_meta", meta);
    }
    return complete(env, out, -1);
  }

  /**
   * One content block as MCP writes it: text, an image or audio with its Base64 {@code data}, an embedded resource
   * (its text or blob), a link to a resource. With 2025-03-26 a link becomes text with its URI (the type is newer).
   */
  static ObjectNode block(ToolResult.Content c, boolean oldest) {
    ObjectNode b = JsonNodeFactory.instance.objectNode();
    switch (c.type()) {
      case "image", "audio" -> {
        if (c.data() == null) {
          return b.put("type", "text").put("text", c.text() == null ? "" : c.text());
        }
        b.put("type", c.type()).put("data", c.data()).put("mimeType", c.mimeType() == null
            ? "application/octet-stream" : c.mimeType());
      }
      case "resource" -> {
        b.put("type", "resource");
        ObjectNode res = b.putObject("resource");
        res.put("uri", c.uri() == null ? "" : c.uri());
        if (c.mimeType() != null) {
          res.put("mimeType", c.mimeType());
        }
        if (c.data() != null) {
          res.put("blob", c.data());
        } else {
          res.put("text", c.text() == null ? "" : c.text());
        }
        if (c.meta() != null && c.meta().isObject()) {
          res.set("_meta", c.meta());
        }
        return b;
      }
      case "resource_link" -> {
        if (oldest || c.uri() == null) {
          return b.put("type", "text").put("text", (c.name() == null ? "" : c.name() + ": ")
              + (c.uri() == null ? "" : c.uri()));
        }
        b.put("type", "resource_link");
        b.put("uri", c.uri());
        b.put("name", c.name() == null ? c.uri() : c.name());
        if (c.mimeType() != null) {
          b.put("mimeType", c.mimeType());
        }
      }
      default -> b.put("type", "text").put("text", c.text() == null ? "" : c.text());
    }
    if (c.meta() != null && c.meta().isObject()) {
      b.set("_meta", c.meta());
    }
    return b;
  }

  /** {@code isError} result with one text: the tool ran (or was refused) and the model should read why. */
  public static ObjectNode toolError(Envelope env, String text, ObjectNode extraMeta) {
    return callToolResult(env, ToolResult.error(text), null, null, extraMeta);
  }
}
