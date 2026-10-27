package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Connection configs and calls as the core hands them to the connector. */
final class Configs {

  private static final AtomicLong VERSIONS = new AtomicLong();

  private Configs() {}

  /** A builder of one connection's config. */
  static final class Cfg {
    final ObjectNode root = JsonNodeFactory.instance.objectNode();
    String id = "c-" + VERSIONS.incrementAndGet();
    String name = "cache-redis";
    Env env = Env.DEV;
    long version = 1;
    ObjectNode metadata;

    Cfg(String host, int port) {
      root.putObject("connection").put("host", host).put("port", port);
    }

    Cfg user(String user) {
      root.withObjectProperty("credentials").put("user", user);
      return this;
    }

    Cfg env(Env e) {
      this.env = e;
      return this;
    }

    Cfg id(String v) {
      this.id = v;
      return this;
    }

    Cfg version(long v) {
      this.version = v;
      return this;
    }

    Cfg database(int db) {
      root.withObjectProperty("connection").put("database", db);
      return this;
    }

    Cfg databases(int... databases) {
      var selection = root.withObjectProperty("connection").putArray("databases");
      for (int database : databases) {
        selection.add(database);
      }
      return this;
    }

    Cfg tls(boolean on, String verify, String caPem) {
      ObjectNode c = root.withObjectProperty("connection");
      c.put("tls", on);
      if (verify != null) {
        c.put("tlsVerify", verify);
      }
      if (caPem != null) {
        c.put("caCertificate", caPem);
      }
      return this;
    }

    Cfg advanced(String field, Object value) {
      ObjectNode a = root.withObjectProperty("advanced");
      switch (value) {
        case Integer i -> a.put(field, i);
        case Boolean b -> a.put(field, b);
        case String s -> a.put(field, s);
        case String[] list -> {
          var arr = a.putArray(field);
          for (String s : list) {
            arr.add(s);
          }
        }
        default -> throw new IllegalArgumentException(field);
      }
      return this;
    }

    /** What the last Test connection found about range bytes ({@code signed}, {@code unsigned}; null: none ran). */
    Cfg globChars(String chars) {
      metadata = chars == null ? null : JsonNodeFactory.instance.objectNode().put(RedisSettings.GLOB_CHARS, chars);
      return this;
    }

    /** The metadata of a Test connection, as the core stores it with the connection. */
    Cfg metadata(ObjectNode m) {
      metadata = m;
      return this;
    }

    ConnectionConfig build() {
      return new ConnectionConfig(id, name, RedisConnector.TYPE, env, version, root.deepCopy(),
          Set.of(RedisSettings.PASSWORD), metadata == null ? null : metadata.deepCopy());
    }
  }

  static Cfg redis(String host, int port) {
    return new Cfg(host, port);
  }

  static Cfg local(LocalRedis r) {
    return new Cfg("127.0.0.1", r.port);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".redis", null, env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  static ToolCall call(String tool, String argumentsJson) {
    return new ToolCall("tc_redis_test", tool, "cache-redis", (ObjectNode) TestCtx.JSON.readTree(argumentsJson),
        null);
  }
}
