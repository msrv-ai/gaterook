package ai.msrv.gaterook.connectors.aws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.w3c.dom.Element;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An AWS answer read back by protocol, under the output shape's member names: the JSON body of awsJson and
 * restJson1; the {@code <Operation>Result} of awsQuery, the {@code <Operation>Response} of ec2Query, the root of a
 * restXml body; plus, for the REST protocols, the members bound to headers and the status code. Errors as AWS gives
 * them: the code ({@code __type}, {@code x-amzn-ErrorType}, {@code <Code>}) and message.
 */
final class ApiResponse {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** What AWS answered: the output, the request id when it said one. */
  record Answer(JsonNode output, String requestId) {}

  /** What AWS refused: its error code, message and request id. */
  record Error(String code, String message, String requestId) {}

  private ApiResponse() {}

  static Answer read(ApiModel m, ApiModel.Operation op, int status, Map<String, List<String>> headers, byte[] body)
      throws IOException {
    JsonNode out = op.output() == null ? null : m.shape(op.output());
    String requestId = header(headers, "x-amzn-requestid");
    if (requestId == null) {
      requestId = header(headers, "x-amz-request-id");
    }
    switch (m.protocol) {
      case "awsJson1_0", "awsJson1_1" -> {
        JsonNode tree = json(body);
        return new Answer(out == null ? F.objectNode() : ApiJson.read(m, out, tree, false), requestId);
      }
      case "awsQuery", "ec2Query" -> {
        if (body.length == 0) {
          return new Answer(F.objectNode(), requestId);
        }
        Element root = ApiXml.parse(body);
        Element result = m.protocol.equals("ec2Query") ? root : ApiXml.child(root, op.name() + "Result");
        Element meta = ApiXml.child(root, "ResponseMetadata");
        if (meta != null && ApiXml.child(meta, "RequestId") != null) {
          requestId = ApiXml.child(meta, "RequestId").getTextContent().strip();
        } else if (ApiXml.child(root, "requestId") != null) {
          requestId = ApiXml.child(root, "requestId").getTextContent().strip();
        }
        if (out == null || result == null) {
          return new Answer(F.objectNode(), requestId);
        }
        return new Answer(ApiXml.structure(m, out, result), requestId);
      }
      case "restJson1", "restXml" -> {
        return new Answer(rest(m, out, status, headers, body, m.protocol.equals("restJson1")), requestId);
      }
      default -> throw new IOException(m.sdkId + " speaks " + m.protocol);
    }
  }

  private static JsonNode rest(ApiModel m, JsonNode out, int status, Map<String, List<String>> headers, byte[] body,
      boolean json) throws IOException {
    if (out == null) {
      return F.objectNode();
    }
    ObjectNode result = F.objectNode();
    String payload = null;
    for (Map.Entry<String, JsonNode> e : ApiModel.members(out)) {
      if (e.getValue().has("payload")) {
        payload = e.getKey();
      }
    }
    if (payload != null) {
      JsonNode member = out.path("m").path(payload);
      JsonNode target = m.shape(member.path("s").asString());
      switch (ApiModel.type(target)) {
        case "blob" -> result.put(payload, Base64.getEncoder().encodeToString(body));
        case "string" -> result.put(payload, new String(body, StandardCharsets.UTF_8));
        default -> {
          if (body.length > 0) {
            result.set(payload, json ? ApiJson.read(m, target, json(body), true)
                : ApiXml.value(m, target, ApiXml.parse(body)));
          }
        }
      }
    } else if (body.length > 0) {
      JsonNode members = json ? ApiJson.read(m, out, json(body), true) : ApiXml.structure(m, out, ApiXml.parse(body));
      if (members instanceof ObjectNode o) {
        result.setAll(o);
      }
    }
    for (Map.Entry<String, JsonNode> e : ApiModel.members(out)) {
      JsonNode member = e.getValue();
      JsonNode target = m.shape(member.path("s").asString());
      if (member.has("status")) {
        result.put(e.getKey(), status);
      } else if (member.has("header")) {
        String v = header(headers, member.get("header").asString());
        if (v != null) {
          result.set(e.getKey(), headerValue(m, target, v));
        }
      } else if (member.has("prefixHeaders")) {
        String prefix = member.get("prefixHeaders").asString().toLowerCase(Locale.ROOT);
        ObjectNode map = F.objectNode();
        headers.forEach((name, values) -> {
          if (name.toLowerCase(Locale.ROOT).startsWith(prefix) && !values.isEmpty()) {
            map.put(name.substring(prefix.length()), values.getFirst());
          }
        });
        if (!map.isEmpty()) {
          result.set(e.getKey(), map);
        }
      }
    }
    return result;
  }

  private static JsonNode headerValue(ApiModel m, JsonNode shape, String value) {
    String type = ApiModel.type(shape);
    if (type.equals("list")) {
      ArrayNode out = F.arrayNode();
      JsonNode item = m.shape(shape.path("member").path("s").asString());
      for (String part : value.split(",")) {
        out.add(headerValue(m, item, part.strip()));
      }
      return out;
    }
    if (type.equals("string") && shape.hasNonNull("media")) {
      try {
        return F.stringNode(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        return F.stringNode(value);
      }
    }
    return ApiXml.scalar(shape, value);
  }

  static JsonNode json(byte[] body) throws IOException {
    if (body.length == 0) {
      return F.objectNode();
    }
    try {
      return JSON.readTree(body);
    } catch (JacksonException e) {
      throw new IOException("not JSON: " + e.getOriginalMessage(), e);
    }
  }

  static String header(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> h : headers.entrySet()) {
      if (h.getKey() != null && h.getKey().equalsIgnoreCase(name) && !h.getValue().isEmpty()) {
        return h.getValue().getFirst();
      }
    }
    return null;
  }

  /** The error of a non-2xx answer, as well as it can be read. */
  static Error error(ApiModel m, int status, Map<String, List<String>> headers, byte[] body) {
    String requestId = header(headers, "x-amzn-requestid");
    if (requestId == null) {
      requestId = header(headers, "x-amz-request-id");
    }
    String code = header(headers, "x-amzn-errortype");
    String message = null;
    boolean xml = m.protocol.equals("awsQuery") || m.protocol.equals("ec2Query") || m.protocol.equals("restXml");
    if (xml) {
      try {
        Element root = ApiXml.parse(body);
        Element error = ApiXml.child(root, "Error");
        if (error == null && ApiXml.child(root, "Errors") != null) {
          error = ApiXml.child(ApiXml.child(root, "Errors"), "Error");
        }
        if (error == null && ApiXml.localName(root).equals("Error")) {
          error = root;
        }
        if (error != null) {
          code = text(error, "Code", code);
          message = text(error, "Message", null);
        }
        requestId = text(root, "RequestId", text(root, "RequestID", requestId));
      } catch (IOException e) {
        // Not XML: the status says what is known.
      }
    } else {
      try {
        JsonNode tree = json(body);
        if (code == null) {
          code = tree.path("__type").asString(tree.path("code").asString(tree.path("Code").asString(null)));
        }
        message = tree.path("message").asString(tree.path("Message").asString(
            tree.path("errorMessage").asString(null)));
      } catch (IOException e) {
        // Not JSON: the status says what is known.
      }
    }
    if (code != null) {
      int colon = code.indexOf(':');
      code = colon >= 0 ? code.substring(0, colon) : code;
      code = code.substring(code.lastIndexOf('#') + 1).strip();
    }
    if (code == null || code.isEmpty()) {
      code = "HTTP" + status;
    }
    return new Error(code, message == null ? "" : message, requestId);
  }

  private static String text(Element e, String child, String dflt) {
    Element c = ApiXml.child(e, child);
    return c == null ? dflt : c.getTextContent().strip();
  }
}
