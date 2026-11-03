package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** One validated, bounded write to one key, executed as a single Redis command. */
record RedisWrite(Cmd command, RedisRequest.Key key, List<byte[]> arguments, int items, int database,
    ObjectNode payload) implements RedisRequest {

  static final int MAX_ITEMS = 1_000;
  static final int MAX_BYTES = 1_048_576;

  static RedisWrite parse(String tool, JsonNode a, ConnectionConfig cfg, RedisSettings s) throws Refusal {
    Key key = RedisRequest.key(a, cfg, s, "write");
    Set<String> allowed = new HashSet<>(Set.of("connection", "tool", "database", "key", "encoding"));
    allowed.addAll(switch (tool) {
      case "redis_set" -> Set.of("value", "valueEncoding", "condition", "ttlMs", "keepTtl");
      case "redis_hset" -> Set.of("fields");
      case "redis_zadd" -> Set.of("members");
      default -> Set.of("values");
    });
    for (String field : a.propertyNames()) {
      if (!allowed.contains(field)) {
        throw bad("Unknown argument: " + field + ".");
      }
    }
    ObjectNode p = JsonNodeFactory.instance.objectNode().put("tool", tool).put("key", key.text())
        .put("database", s.database());
    if (key.base64()) {
      p.put("encoding", "base64");
    }
    List<byte[]> args = new ArrayList<>();
    args.add(key.bytes());
    Cmd command;
    int items = 1;
    switch (tool) {
      case "redis_set" -> {
        command = Cmd.SET;
        String value = string(a.path("value"), "value");
        String encoding = a.has("valueEncoding") ? string(a.path("valueEncoding"), "valueEncoding") : "utf8";
        byte[] bytes;
        if (encoding.equals("base64")) {
          try {
            bytes = Base64.getDecoder().decode(value);
          } catch (IllegalArgumentException e) {
            throw bad("value isn't base64, but valueEncoding says it is.");
          }
        } else if (encoding.equals("utf8")) {
          bytes = value.getBytes(StandardCharsets.UTF_8);
        } else {
          throw bad("valueEncoding is utf8 or base64.");
        }
        valueSize(bytes, s);
        args.add(bytes);
        p.put("value", value).put("valueEncoding", encoding);
        String condition = a.has("condition") ? string(a.path("condition"), "condition") : "always";
        if (!Set.of("always", "NX", "XX").contains(condition)) {
          throw bad("condition is always, NX or XX.");
        }
        p.put("condition", condition);
        if (!condition.equals("always")) {
          args.add(utf8(condition));
        }
        if (a.has("keepTtl") && !a.path("keepTtl").isBoolean()) {
          throw bad("keepTtl is a boolean.");
        }
        boolean keep = a.path("keepTtl").asBoolean(false);
        if (keep && a.has("ttlMs")) {
          throw bad("Use ttlMs or keepTtl, not both.");
        }
        if (a.has("ttlMs")) {
          JsonNode ttl = a.path("ttlMs");
          if (!ttl.isIntegralNumber() || !ttl.canConvertToLong() || ttl.asLong() < 1
              || ttl.asLong() > 2_147_483_647_000L) {
            throw bad("ttlMs is a whole number from 1 to 2,147,483,647,000 milliseconds.");
          }
          p.put("ttlMs", ttl.asLong());
          args.add(utf8("PX"));
          args.add(utf8(Long.toString(ttl.asLong())));
        } else if (keep) {
          p.put("keepTtl", true);
          args.add(utf8("KEEPTTL"));
        }
      }
      case "redis_hset" -> {
        command = Cmd.HSET;
        JsonNode fields = a.path("fields");
        if (!fields.isObject()) {
          throw bad("fields is an object of field names and string values.");
        }
        items = count(fields.size());
        for (var field : fields.properties()) {
          addValue(args, field.getKey(), s);
          addValue(args, string(field.getValue(), "fields values"), s);
        }
        p.set("fields", fields.deepCopy());
      }
      case "redis_zadd" -> {
        command = Cmd.ZADD;
        JsonNode members = a.path("members");
        if (!members.isArray()) {
          throw bad("members is an array of objects with score and value.");
        }
        items = count(members.size());
        for (JsonNode member : members) {
          JsonNode score = member.path("score");
          if (!member.isObject() || member.size() != 2 || !score.isNumber()
              || !Double.isFinite(score.asDouble())) {
            throw bad("Each member has a finite numeric score and a string value.");
          }
          args.add(utf8(Double.toString(score.asDouble())));
          addValue(args, string(member.path("value"), "member value"), s);
        }
        p.set("members", members.deepCopy());
      }
      default -> {
        command = switch (tool) {
          case "redis_lpush" -> Cmd.LPUSH;
          case "redis_rpush" -> Cmd.RPUSH;
          case "redis_sadd" -> Cmd.SADD;
          default -> throw bad("Unknown write tool: " + tool + ".");
        };
        JsonNode values = a.path("values");
        if (!values.isArray()) {
          throw bad("values is an array of strings.");
        }
        items = count(values.size());
        for (JsonNode value : values) {
          addValue(args, string(value, "values entries"), s);
        }
        p.set("values", values.deepCopy());
      }
    }
    if (args.stream().mapToLong(b -> b.length).sum() > MAX_BYTES) {
      throw bad("A write may contain at most 1 MiB of key, fields and values.");
    }
    return new RedisWrite(command, key, List.copyOf(args), items, s.database(), p);
  }

  void checkLimits(ExecContext ctx) throws ConnectorException {
    if (ctx.limits().maxRows() > 0 && items > ctx.limits().maxRows()) {
      throw ConnectorException.denied("This write exceeds the call's limit of " + ctx.limits().maxRows()
          + " elements. Send a smaller write; no elements were written.");
    }
    if (ctx.limits().maxRequestBytes() > 0
        && payload.toString().getBytes(StandardCharsets.UTF_8).length > ctx.limits().maxRequestBytes()) {
      throw ConnectorException.denied("This write exceeds the call's request size limit. No elements were written.");
    }
    if (ctx.cancelled()) {
      throw ConnectorException.timeout("The call was stopped before the write was sent.");
    }
  }

  String preview() {
    return command.wire() + " " + String.join(" ", arguments.stream().map(RedisValues::quote).toList());
  }

  ToolResult result(Object reply) {
    ObjectNode out = JsonNodeFactory.instance.objectNode().put("tool", payload.path("tool").asString())
        .put("key", key.text()).put("database", database);
    if (key.base64()) {
      out.put("encoding", "base64");
    }
    String text;
    if (command == Cmd.SET) {
      boolean applied = reply != null;
      out.put("applied", applied);
      text = applied ? "Value written." : "Condition not met; value unchanged.";
    } else {
      long count = Replies.integer(reply, 0);
      String field = command == Cmd.LPUSH || command == Cmd.RPUSH ? "length" : "added";
      out.put(field, count);
      text = field.equals("length") ? "List length: " + count + "." : "New entries added: " + count + ".";
    }
    return ToolResult.json(out, text);
  }

  private static int count(int count) throws Refusal {
    if (count < 1 || count > MAX_ITEMS) {
      throw bad("A write requires 1 to 1,000 elements.");
    }
    return count;
  }

  private static String string(JsonNode n, String field) throws Refusal {
    if (!n.isString() || n.asString().length() > MAX_BYTES * 2) {
      throw bad(field + " must be a string of at most 2 Mi characters.");
    }
    return n.asString();
  }

  private static void addValue(List<byte[]> args, String value, RedisSettings s) throws Refusal {
    byte[] bytes = utf8(value);
    valueSize(bytes, s);
    args.add(bytes);
    if (args.stream().mapToLong(b -> b.length).sum() > MAX_BYTES) {
      throw bad("A write may contain at most 1 MiB of key, fields and values.");
    }
  }

  private static void valueSize(byte[] bytes, RedisSettings s) throws Refusal {
    if (bytes.length > s.maxValueBytes()) {
      throw bad("Each value or hash field must fit the connection's Max value size (" + s.maxValueBytes() + " bytes).");
    }
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static Refusal bad(String message) {
    return new Refusal("bad-argument", message, null);
  }
}
