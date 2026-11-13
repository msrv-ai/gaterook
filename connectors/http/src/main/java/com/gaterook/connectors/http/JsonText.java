package com.gaterook.connectors.http;

import com.gaterook.connectors.support.JsonNumbers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON as the connector writes and reads it. A request body is serialized once, two-space indented like
 * {@code JSON.stringify(value, null, 2)}: those bytes are what the approver reads and what goes on the wire. Responses
 * keep decimals as written ({@code 129.00} stays {@code 129.00}); a number whose exponent is too large to write out
 * stays text.
 */
final class JsonText {

  /** Limits for documents from APIs and specs: the size cap is applied before, these stop pathological nesting. */
  static final StreamReadConstraints LIMITS = StreamReadConstraints.builder().maxNestingDepth(200)
      .maxStringLength(25_000_000).maxDocumentLength(25_000_000L).maxNumberLength(1_000).build();

  static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(LIMITS).build())
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private JsonText() {}

  /**
   * A response body as a tree. A number whose exponent would add more than {@link JsonNumbers#MAX_PADDING} zeros to its
   * digits ({@code 1e1000000000}) is a string of its scientific notation, not a decimal that anything downstream could
   * be asked to write out. An exponent beyond what a decimal holds at all ({@code 1e-2147483648}) fails the parse with
   * a {@link NumberFormatException}, which Jackson does not wrap.
   */
  static JsonNode read(byte[] body) {
    return JsonNumbers.keepHugeAsText(JSON.readTree(body));
  }

  static byte[] pretty(JsonNode value) {
    StringBuilder out = new StringBuilder();
    write(value, 0, out);
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  static String compact(JsonNode value) {
    return JSON.writeValueAsString(value);
  }

  private static void write(JsonNode n, int depth, StringBuilder out) {
    if (n.isObject()) {
      if (n.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      boolean first = true;
      for (Map.Entry<String, JsonNode> e : n.properties()) {
        if (!first) {
          out.append(",\n");
        }
        first = false;
        indent(depth + 1, out);
        out.append(JSON.writeValueAsString(JSON.getNodeFactory().stringNode(e.getKey()))).append(": ");
        write(e.getValue(), depth + 1, out);
      }
      out.append('\n');
      indent(depth, out);
      out.append('}');
    } else if (n.isArray()) {
      if (n.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      boolean first = true;
      for (JsonNode item : n) {
        if (!first) {
          out.append(",\n");
        }
        first = false;
        indent(depth + 1, out);
        write(item, depth + 1, out);
      }
      out.append('\n');
      indent(depth, out);
      out.append(']');
    } else {
      out.append(JSON.writeValueAsString(n));
    }
  }

  private static void indent(int depth, StringBuilder out) {
    for (int i = 0; i < depth; i++) {
      out.append("  ");
    }
  }
}
