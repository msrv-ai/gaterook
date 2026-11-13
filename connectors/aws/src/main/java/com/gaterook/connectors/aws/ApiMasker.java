package com.gaterook.connectors.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Masks the answer of an AWS API call ({@code aws_api_read}, {@code aws_api_change}) before it leaves the connector;
 * the core's masker runs after it with the call's taint. In order:
 *
 * <ol>
 *   <li>every value whose shape the model marks {@code @sensitive} (Lambda's {@code EnvironmentVariableValue},
 *       Secrets Manager's {@code SecretStringType}, keys and passwords of many services);</li>
 *   <li>what the models leave unmarked: any {@code UserData}, whatever its shape; presigned URLs
 *       ({@code X-Amz-Signature}), such as Lambda's {@code Code.Location}; API Gateway key values and AppSync API keys,
 *       whose id is the key; the content of received SQS messages, their bodies and attribute values;</li>
 *   <li>the {@link Redactor}'s rules for environment variables, headers, repository credentials and keys that name
 *       secrets, keeping cursor keys readable.</li>
 * </ol>
 */
final class ApiMasker {

  static final String MASK = Redactor.MASK;

  /** Members that hold a key or a key-like URL although the model doesn't mark them: service → operation → paths. */
  private static final Map<String, Map<String, List<String>>> PATHS = Map.of(
      "apigateway", Map.of("GetApiKey", List.of("value"), "GetApiKeys", List.of("items[].value"),
          "UpdateApiKey", List.of("value")),
      "appsync", Map.of("ListApiKeys", List.of("apiKeys[].id"), "UpdateApiKey", List.of("apiKey.id")));
  /**
   * Content, not configuration: data an approved change returns that agents don't get (§5.2: mask message bodies).
   * {@code name{}} is every value of a map. IDs, receipt handles, system attributes and digests stay readable.
   */
  private static final Map<String, Map<String, List<String>>> DATA = Map.of(
      "sqs", Map.of("ReceiveMessage", List.of("Messages[].Body", "Messages[].MessageAttributes{}.StringValue",
          "Messages[].MessageAttributes{}.BinaryValue", "Messages[].MessageAttributes{}.StringListValues",
          "Messages[].MessageAttributes{}.BinaryListValues")));

  private ApiMasker() {}

  /** Masks {@code result} (an answer of {@code op}) in place; returns how many values were masked. */
  static int mask(ApiModel m, ApiModel.Operation op, JsonNode result) {
    int masked = 0;
    if (op.output() != null) {
      masked += sensitive(m, m.shape(op.output()), result);
    }
    for (Map<String, Map<String, List<String>>> table : List.of(PATHS, DATA)) {
      for (String path : table.getOrDefault(m.name, Map.of()).getOrDefault(op.name(), List.of())) {
        masked += path(result, path.split("\\."), 0);
      }
    }
    masked += unmarked(result);
    masked += Redactor.redactAnswer(result);
    return masked;
  }

  /** Whether the answer of {@code op} holds content that is masked as data (received messages). */
  static boolean hidesData(String service, ApiModel.Operation op) {
    return DATA.getOrDefault(service, Map.of()).containsKey(op.name());
  }

  /** Values of {@code @sensitive} shapes, walking {@code value} along {@code shape}. */
  static int sensitive(ApiModel m, JsonNode shape, JsonNode value) {
    if (value == null || value.isNull()) {
      return 0;
    }
    int masked = 0;
    switch (ApiModel.type(shape)) {
      case "structure", "union" -> {
        if (value instanceof ObjectNode o) {
          for (Map.Entry<String, JsonNode> e : ApiModel.members(shape)) {
            JsonNode v = o.get(e.getKey());
            if (v == null || v.isNull()) {
              continue;
            }
            JsonNode target = m.shape(e.getValue().path("s").asString());
            if (target.path("sensitive").asInt(0) == 1) {
              masked += replace(o, e.getKey());
            } else {
              masked += sensitive(m, target, v);
            }
          }
        }
      }
      case "list" -> {
        if (value instanceof ArrayNode a) {
          JsonNode target = m.shape(shape.path("member").path("s").asString());
          for (int i = 0; i < a.size(); i++) {
            if (target.path("sensitive").asInt(0) == 1) {
              if (!isEmpty(a.get(i))) {
                a.set(i, a.stringNode(MASK));
                masked++;
              }
            } else {
              masked += sensitive(m, target, a.get(i));
            }
          }
        }
      }
      case "map" -> {
        if (value instanceof ObjectNode o) {
          JsonNode target = m.shape(shape.path("value").path("s").asString());
          boolean keys = m.shape(shape.path("key").path("s").asString()).path("sensitive").asInt(0) == 1;
          for (String k : new ArrayList<>(o.propertyNames())) {
            if (target.path("sensitive").asInt(0) == 1 || keys) {
              masked += replace(o, k);
            } else {
              masked += sensitive(m, target, o.get(k));
            }
          }
        }
      }
      default -> {
        // Scalars are masked by their parent, which knows the member.
      }
    }
    return masked;
  }

  /** {@code UserData} anywhere and presigned URLs anywhere. */
  private static int unmarked(JsonNode node) {
    int masked = 0;
    if (node instanceof ObjectNode o) {
      for (String k : new ArrayList<>(o.propertyNames())) {
        JsonNode v = o.get(k);
        if (k.equalsIgnoreCase("UserData")) {
          masked += replace(o, k);
        } else if (v.isString() && presigned(v.asString())) {
          masked += replace(o, k);
        } else {
          masked += unmarked(v);
        }
      }
    } else if (node instanceof ArrayNode a) {
      for (int i = 0; i < a.size(); i++) {
        JsonNode v = a.get(i);
        if (v.isString() && presigned(v.asString())) {
          a.set(i, a.stringNode(MASK));
          masked++;
        } else {
          masked += unmarked(v);
        }
      }
    }
    return masked;
  }

  /** A URL signed with SigV4 or a session token: whoever has it can use it until it expires. */
  static boolean presigned(String s) {
    return s.contains("X-Amz-Signature=") || s.contains("X-Amz-Security-Token=") || s.contains("x-amz-signature=")
        || (s.contains("Signature=") && s.contains("Expires=") && s.startsWith("http"));
  }

  private static int path(JsonNode node, String[] parts, int at) {
    if (node == null || at >= parts.length) {
      return 0;
    }
    String part = parts[at];
    boolean each = part.endsWith("[]");
    boolean values = part.endsWith("{}");
    String name = each || values ? part.substring(0, part.length() - 2) : part;
    if (!(node instanceof ObjectNode o) || !o.has(name)) {
      return 0;
    }
    if (values) {
      int masked = 0;
      if (o.get(name) instanceof ObjectNode map) {
        for (String key : new ArrayList<>(map.propertyNames())) {
          masked += at == parts.length - 1 ? replace(map, key) : path(map.get(key), parts, at + 1);
        }
      }
      return masked;
    }
    if (at == parts.length - 1 && !each) {
      return replace(o, name);
    }
    if (each) {
      int masked = 0;
      for (JsonNode item : o.get(name)) {
        masked += at == parts.length - 1 ? 0 : path(item, parts, at + 1);
      }
      return masked;
    }
    return path(o.get(name), parts, at + 1);
  }

  private static int replace(ObjectNode o, String name) {
    JsonNode v = o.get(name);
    if (isEmpty(v)) {
      return 0;
    }
    o.put(name, MASK);
    return 1;
  }

  private static boolean isEmpty(JsonNode v) {
    return v == null || v.isNull() || (v.isString() && (v.asString().isEmpty() || v.asString().equals(MASK)));
  }
}
