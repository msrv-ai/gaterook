package com.gaterook.connectors.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The form body of awsQuery and ec2Query requests: {@code Action=<Operation>&Version=<api version>} and the
 * parameters flattened into keys, as the protocols define them.
 *
 * <ul>
 *   <li>awsQuery: a member's key is its {@code xmlName} or name; lists are {@code Key.member.N} ({@code member} is the
 *       list member's {@code xmlName} when it has one) or, flattened, {@code Key.N}; an empty list is {@code Key=};
 *       maps are {@code Key.entry.N.key}/{@code .value} or, flattened, {@code Key.N.key}/{@code .value};</li>
 *   <li>ec2Query: a member's key is its {@code ec2QueryName}, else its {@code xmlName} or name with an upper-case
 *       first letter; every list is {@code Key.N}; an empty list is left out.</li>
 * </ul>
 * Timestamps are ISO-8601 unless the model says otherwise; numbers and booleans are written as text.
 */
final class ApiQuery {

  private ApiQuery() {}

  /** The key=value pairs, in order; {@code ec2}: ec2Query's rules. */
  static List<String[]> pairs(ApiModel m, ApiModel.Operation op, JsonNode params, boolean ec2) {
    List<String[]> out = new ArrayList<>();
    out.add(new String[] {"Action", op.name()});
    out.add(new String[] {"Version", m.version});
    if (op.input() != null) {
      structure(m, m.shape(op.input()), params, "", ec2, out);
    }
    return out;
  }

  /** The form body: pairs joined with {@code &}, both sides percent-encoded. */
  static String body(List<String[]> pairs) {
    StringBuilder b = new StringBuilder();
    for (String[] p : pairs) {
      if (!b.isEmpty()) {
        b.append('&');
      }
      b.append(ApiRest.encode(p[0], false)).append('=').append(ApiRest.encode(p[1], false));
    }
    return b.toString();
  }

  private static void structure(ApiModel m, JsonNode shape, JsonNode value, String prefix, boolean ec2,
      List<String[]> out) {
    for (Map.Entry<String, JsonNode> e : ApiModel.members(shape)) {
      JsonNode v = value.get(e.getKey());
      if (v == null || v.isNull()) {
        continue;
      }
      JsonNode member = e.getValue();
      String key = prefix + key(e.getKey(), member, ec2);
      member(m, member, v, key, ec2, out);
    }
  }

  private static String key(String name, JsonNode member, boolean ec2) {
    if (ec2) {
      if (member.hasNonNull("ec2")) {
        return member.get("ec2").asString();
      }
      String n = member.hasNonNull("xml") ? member.get("xml").asString() : name;
      return n.isEmpty() ? n : n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1);
    }
    return member.hasNonNull("xml") ? member.get("xml").asString() : name;
  }

  private static void member(ApiModel m, JsonNode member, JsonNode value, String key, boolean ec2,
      List<String[]> out) {
    JsonNode target = m.shape(member.path("s").asString());
    switch (ApiModel.type(target)) {
      case "structure", "union" -> structure(m, target, value, key + ".", ec2, out);
      case "list" -> {
        JsonNode item = target.path("member");
        if (value.isEmpty()) {
          if (!ec2) {
            out.add(new String[] {key, ""});
          }
          return;
        }
        boolean flat = ec2 || member.path("flat").asInt(0) == 1;
        String base = flat ? key : key + "." + (item.hasNonNull("xml") ? item.get("xml").asString() : "member");
        int i = 1;
        for (JsonNode v : value) {
          member(m, item, v, base + "." + i++, ec2, out);
        }
      }
      case "map" -> {
        JsonNode k = target.path("key");
        JsonNode val = target.path("value");
        String kn = k.hasNonNull("xml") ? k.get("xml").asString() : "key";
        String vn = val.hasNonNull("xml") ? val.get("xml").asString() : "value";
        boolean flat = member.path("flat").asInt(0) == 1;
        String base = flat ? key : key + ".entry";
        int i = 1;
        for (Map.Entry<String, JsonNode> e : value.properties()) {
          out.add(new String[] {base + "." + i + "." + kn, e.getKey()});
          member(m, val, e.getValue(), base + "." + i + "." + vn, ec2, out);
          i++;
        }
      }
      default -> out.add(new String[] {key, ApiXml.text(target, value, member)});
    }
  }
}
