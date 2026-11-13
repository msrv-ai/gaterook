package com.gaterook.connectors.aws;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The request of one AWS API call ({@code aws_api_read}, {@code aws_api_change}) as it goes on the wire, built from the
 * model and the canonical parameters only: method, path and query, the headers the protocol needs, the body. Nothing of
 * it comes from the agent but the parameters' values; the host is the endpoint table's, a host prefix's labels are
 * checked as DNS labels.
 *
 * <ul>
 *   <li>awsJson1_0/1_1: {@code POST /}, {@code X-Amz-Target: <service shape>.<Operation>}, the input as JSON;</li>
 *   <li>restJson1, restXml: the operation's method and URI with the bound labels, query and headers, the rest of the
 *       input as JSON or XML (or the {@code httpPayload} member as the body);</li>
 *   <li>awsQuery, ec2Query: {@code POST /} with the form {@code Action=…&Version=…&…}.</li>
 * </ul>
 */
final class ApiWire {

  /** One request without its host. */
  record Request(String method, String path, String query, Map<String, String> headers, byte[] body) {

    String target() {
      return query.isEmpty() ? path : path + "?" + query;
    }

    String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern HOST_LABEL = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");

  private ApiWire() {}

  static Request build(ApiModel m, ApiModel.Operation op, ObjectNode params) throws Refusal {
    Map<String, String> headers = new LinkedHashMap<>();
    return switch (m.protocol) {
      case "awsJson1_0", "awsJson1_1" -> {
        headers.put("Content-Type", "application/x-amz-json-" + (m.protocol.equals("awsJson1_0") ? "1.0" : "1.1"));
        headers.put("X-Amz-Target", m.serviceShape + "." + op.name());
        JsonNode body = op.input() == null ? JsonNodeFactory.instance.objectNode()
            : ApiJson.write(m, m.shape(op.input()), params, false, null);
        yield new Request("POST", "/", "", headers, JSON.writeValueAsBytes(body));
      }
      case "awsQuery", "ec2Query" -> {
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        String form = ApiQuery.body(ApiQuery.pairs(m, op, params, m.protocol.equals("ec2Query")));
        yield new Request("POST", "/", "", headers, form.getBytes(StandardCharsets.UTF_8));
      }
      case "restJson1", "restXml" -> rest(m, op, params, headers);
      default -> throw new Refusal("unsupported", m.sdkId + " speaks " + m.protocol + ", which the AWS API tools don't "
          + "send.", null);
    };
  }

  private static Request rest(ApiModel m, ApiModel.Operation op, ObjectNode params, Map<String, String> headers)
      throws Refusal {
    ApiRest.Bound b = ApiRest.bind(m, op, params);
    headers.putAll(b.headers());
    boolean json = m.protocol.equals("restJson1");
    byte[] body = new byte[0];
    JsonNode input = op.input() == null ? null : m.shape(op.input());
    if (b.payloadMember() != null) {
      JsonNode member = input.path("m").path(b.payloadMember());
      JsonNode target = m.shape(member.path("s").asString());
      JsonNode v = params.get(b.payloadMember());
      String type = ApiModel.type(target);
      if (v != null && !v.isNull()) {
        switch (type) {
          case "blob" -> {
            body = Base64.getDecoder().decode(v.asString());
            headers.putIfAbsent("Content-Type", target.path("media").asString("application/octet-stream"));
          }
          case "string" -> {
            body = v.asString().getBytes(StandardCharsets.UTF_8);
            headers.putIfAbsent("Content-Type", target.path("media").asString("text/plain"));
          }
          case "structure", "union", "document" -> {
            if (json) {
              body = JSON.writeValueAsBytes(ApiJson.write(m, target, v, true, null));
              headers.putIfAbsent("Content-Type", "application/json");
            } else {
              String root = member.hasNonNull("xml") ? member.get("xml").asString()
                  : target.path("xml").asString(member.path("s").asString());
              body = ApiXml.document(m, root, target, v, member.path("ns")).getBytes(StandardCharsets.UTF_8);
              headers.putIfAbsent("Content-Type", "application/xml");
            }
          }
          default -> throw new Refusal("unsupported", op.name() + " carries its " + b.payloadMember() + " as "
              + type + ", which the AWS API tools don't send.", null);
        }
      }
    } else if (!b.body().isEmpty()) {
      ObjectNode members = JsonNodeFactory.instance.objectNode();
      for (String name : b.body()) {
        if (params.has(name)) {
          members.set(name, params.get(name));
        }
      }
      boolean send = !members.isEmpty() || !(op.method().equals("GET") || op.method().equals("DELETE")
          || op.method().equals("HEAD"));
      if (send) {
        ObjectNode bodyShape = JsonNodeFactory.instance.objectNode();
        bodyShape.put("t", "structure");
        ObjectNode bodyMembers = bodyShape.putObject("m");
        for (String name : b.body()) {
          bodyMembers.set(name, input.path("m").path(name));
        }
        if (json) {
          body = JSON.writeValueAsBytes(ApiJson.write(m, bodyShape, members, true, null));
          headers.putIfAbsent("Content-Type", "application/json");
        } else if (!members.isEmpty()) {
          String root = input.path("xml").asString(op.input());
          body = ApiXml.document(m, root, bodyShape, members, input.path("ns")).getBytes(StandardCharsets.UTF_8);
          headers.putIfAbsent("Content-Type", "application/xml");
        }
      }
    }
    return new Request(op.method(), b.path(), b.query(), headers, body);
  }

  /** The host prefix of {@code op} with its labels filled ({@code "{AccountId}."} → {@code "412398765012."}). */
  static String hostPrefix(ApiModel m, ApiModel.Operation op, JsonNode params) throws Refusal {
    String prefix = op.hostPrefix();
    if (prefix == null || prefix.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < prefix.length()) {
      int open = prefix.indexOf('{', i);
      if (open < 0) {
        out.append(prefix, i, prefix.length());
        break;
      }
      out.append(prefix, i, open);
      int close = prefix.indexOf('}', open);
      String name = prefix.substring(open + 1, close);
      String v = params.path(name).asString("");
      if (!HOST_LABEL.matcher(v).matches()) {
        throw new Refusal("bad_input", "parameters." + name + " becomes part of the host name, so it is one DNS "
            + "label (letters, digits, hyphens).", null);
      }
      out.append(v);
      i = close + 1;
    }
    // The prefix's literal parts are the model's ("data-", "{AccountId}."): only the filled labels are the agent's.
    return out.toString();
  }

  /** The headers worth showing in a preview: the protocol's own and the bound ones, never credentials. */
  static List<String> previewHeaders(Request r) {
    return r.headers().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
  }
}
