package com.gaterook.connectors.aws;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The HTTP bindings of restJson1 and restXml requests ({@code smithy.api#http} and the member traits
 * {@code httpLabel}, {@code httpQuery}, {@code httpQueryParams}, {@code httpHeader}, {@code httpPrefixHeaders},
 * {@code httpPayload}): the path with its labels percent-encoded (a greedy {@code {Key+}} keeps its slashes), the
 * query string, the headers, and which members are left for the body.
 */
final class ApiRest {

  /** The bound parts of one request; {@code body} names the members left for the body. */
  record Bound(String path, String query, Map<String, String> headers, String payloadMember, List<String> body) {}

  /** A header name or query key an agent's map supplies: an HTTP token, never one of SigV4's own. */
  private static final java.util.regex.Pattern TOKEN =
      java.util.regex.Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$");

  private ApiRest() {}

  private static void agentKey(String key, String member) throws Refusal {
    if (!TOKEN.matcher(key).matches() || key.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-")
        || key.equalsIgnoreCase("authorization") || key.equalsIgnoreCase("host")) {
      throw new Refusal("bad_input", "parameters." + member + " can't name \"" + Requests.abbreviate(key)
          + "\": a key there becomes part of the request and is a plain name.", null);
    }
  }

  static Bound bind(ApiModel m, ApiModel.Operation op, JsonNode params) throws Refusal {
    String uri = op.uri();
    String literalQuery = null;
    int q = uri.indexOf('?');
    if (q >= 0) {
      literalQuery = uri.substring(q + 1);
      uri = uri.substring(0, q);
    }
    Map<String, String> headers = new LinkedHashMap<>();
    List<String[]> query = new ArrayList<>();
    Set<String> queryKeys = new HashSet<>();
    String payload = null;
    List<String> body = new ArrayList<>();
    JsonNode input = op.input() == null ? null : m.shape(op.input());
    StringBuilder path = new StringBuilder();
    // Labels first: the path template names them.
    int i = 0;
    while (i < uri.length()) {
      int open = uri.indexOf('{', i);
      if (open < 0) {
        path.append(uri, i, uri.length());
        break;
      }
      path.append(uri, i, open);
      int close = uri.indexOf('}', open);
      String label = uri.substring(open + 1, close);
      boolean greedy = label.endsWith("+");
      String name = greedy ? label.substring(0, label.length() - 1) : label;
      JsonNode v = params.get(name);
      if (v == null || v.isNull() || v.asString("").isEmpty()) {
        throw new Refusal("bad_input", op.name() + " needs parameters." + name + " (it is part of the path).", null);
      }
      JsonNode member = input.path("m").path(name);
      String text = ApiXml.text(m.shape(member.path("s").asString()), v, member);
      // "." and ".." would move the request to another route once the path is normalized: refused, as are empty
      // segments of a greedy label.
      for (String segment : greedy ? text.split("/", -1) : new String[] {text}) {
        if (segment.equals(".") || segment.equals("..") || (greedy && segment.isEmpty())) {
          throw new Refusal("bad_input", "parameters." + name + " can't hold the path segment \"" + segment
              + "\".", null);
        }
      }
      path.append(greedy ? encodeGreedy(text) : encode(text, true));
      i = close + 1;
    }
    if (input != null) {
      for (Map.Entry<String, JsonNode> e : ApiModel.members(input)) {
        JsonNode member = e.getValue();
        JsonNode v = params.get(e.getKey());
        if (member.has("label")) {
          continue;
        }
        if (member.has("payload")) {
          payload = e.getKey();
          continue;
        }
        if (v == null || v.isNull()) {
          if (!member.has("query") && !member.has("header") && !member.has("prefixHeaders")
              && !member.has("queryParams")) {
            body.add(e.getKey());
          }
          continue;
        }
        JsonNode target = m.shape(member.path("s").asString());
        if (member.has("query")) {
          String key = member.get("query").asString();
          queryKeys.add(key);
          if (ApiModel.type(target).equals("list")) {
            JsonNode item = target.path("member");
            JsonNode itemShape = m.shape(item.path("s").asString());
            for (JsonNode x : v) {
              query.add(new String[] {key, ApiXml.text(itemShape, x, item)});
            }
          } else {
            query.add(new String[] {key, ApiXml.text(target, v, member)});
          }
        } else if (member.has("header")) {
          headers.put(member.get("header").asString(), header(m, target, v, member));
        } else if (member.has("prefixHeaders")) {
          String prefix = member.get("prefixHeaders").asString();
          JsonNode valueShape = m.shape(target.path("value").path("s").asString());
          for (Map.Entry<String, JsonNode> h : v.properties()) {
            agentKey(h.getKey(), e.getKey());
            headers.put(prefix + h.getKey(), header(m, valueShape, h.getValue(), target.path("value")));
          }
        } else if (!member.has("queryParams")) {
          body.add(e.getKey());
        }
      }
      // httpQueryParams: a map of further keys; a key httpQuery already sets wins.
      for (Map.Entry<String, JsonNode> e : ApiModel.members(input)) {
        JsonNode member = e.getValue();
        JsonNode v = params.get(e.getKey());
        if (!member.has("queryParams") || v == null || v.isNull()) {
          continue;
        }
        JsonNode target = m.shape(member.path("s").asString());
        JsonNode valueShape = m.shape(target.path("value").path("s").asString());
        for (Map.Entry<String, JsonNode> p : v.properties()) {
          agentKey(p.getKey(), e.getKey());
          if (queryKeys.contains(p.getKey())) {
            continue;
          }
          if (ApiModel.type(valueShape).equals("list")) {
            for (JsonNode x : p.getValue()) {
              query.add(new String[] {p.getKey(), x.asString()});
            }
          } else {
            query.add(new String[] {p.getKey(), ApiXml.text(valueShape, p.getValue(), null)});
          }
        }
      }
    }
    StringBuilder qs = new StringBuilder();
    if (literalQuery != null && !literalQuery.isEmpty()) {
      qs.append(literalQuery);
    }
    for (String[] p : query) {
      if (!qs.isEmpty()) {
        qs.append('&');
      }
      qs.append(encode(p[0], true)).append('=').append(encode(p[1], true));
    }
    return new Bound(path.toString(), qs.toString(), headers, payload, body);
  }

  /** A header value: timestamps as HTTP dates, lists joined with commas, JSON media types as Base64. */
  private static String header(ApiModel m, JsonNode shape, JsonNode value, JsonNode member) throws Refusal {
    String type = ApiModel.type(shape);
    String text;
    if (type.equals("list")) {
      JsonNode item = shape.path("member");
      JsonNode itemShape = m.shape(item.path("s").asString());
      List<String> parts = new ArrayList<>();
      for (JsonNode x : value) {
        String s = header(m, itemShape, x, item);
        parts.add(ApiModel.type(itemShape).equals("string") && (s.contains(",") || s.contains("\""))
            ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : s);
      }
      text = String.join(", ", parts);
    } else if (type.equals("timestamp")) {
      Instant t = Instant.parse(value.asString());
      String format = member != null && member.hasNonNull("ts") ? member.get("ts").asString()
          : shape.path("ts").asString("http-date");
      text = switch (format) {
        case "epoch-seconds" -> ApiJson.epochSeconds(t).toPlainString();
        case "date-time" -> t.toString();
        default -> ApiJson.HTTP_DATE.format(t);
      };
    } else if (type.equals("string") && shape.hasNonNull("media")) {
      text = Base64.getEncoder().encodeToString(value.asString().getBytes(StandardCharsets.UTF_8));
    } else {
      text = ApiXml.text(shape, value, member);
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n' || c == 0) {
        throw new Refusal("bad_input", "A header value can't hold a line break.", null);
      }
    }
    return text;
  }

  /** RFC 3986 percent-encoding: unreserved characters stay; {@code strict}: as SigV4 and REST paths expect. */
  static String encode(String s, boolean strict) {
    StringBuilder out = new StringBuilder(s.length() + 16);
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
          || c == '.' || c == '~') {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return out.toString();
  }

  /** A greedy label: every segment encoded, the slashes between them kept. */
  static String encodeGreedy(String s) {
    String[] parts = s.split("/", -1);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        out.append('/');
      }
      out.append(encode(parts[i], true));
    }
    return out.toString();
  }
}
