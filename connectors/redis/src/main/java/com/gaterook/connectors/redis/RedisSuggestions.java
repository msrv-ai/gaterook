package com.gaterook.connectors.redis;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.SecretUnavailableException;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import redis.clients.jedis.Connection;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;

/**
 * Help for the Redis form ({@link RedisConnector#suggest}). On this computer: {@code localhost} for an empty Host when a
 * server answers on 6379, offered and never filled in. Signed in on a connection of its own, as Test connection does:
 * the server and the user, the transport Auto chose, and the logical databases with their keys for Database. Nothing
 * is filled in: database 0, the default, is what most servers use.
 */
final class RedisSuggestions {

  static final int PORT = 6379;
  private static final Pattern KEYSPACE = Pattern.compile("^db(\\d{1,4}):keys=(\\d+)", Pattern.MULTILINE);

  private RedisSuggestions() {}

  /** @param listening whether something accepts a TCP connection on a loopback port ({@code LocalPorts::listening}) */
  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      IntPredicate listening) {
    Suggestions.Builder out = Suggestions.builder();
    if (cfg.config().at("/connection/host").asString("").isBlank() && listening.test(PORT)) {
      out.options("/connection/host", "this computer", List.of(new Option("localhost", null,
          "A Redis server answers on port " + PORT)));
    }
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, out);
    }
    return out.build();
  }

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Builder out) {
    RedisSettings chosen;
    try {
      chosen = RedisSettings.parse(cfg);
    } catch (RedisSettings.InvalidSettingsException e) {
      out.note(e.getMessage());
      return;
    }
    Instant deadline = earlier(ctx.clock().instant().plus(RedisProbe.BUDGET), ctx.deadline());
    try (Credentials login = Credentials.resolve(chosen, secrets)) {
      RedisSettings s = AutoTls.resolve(chosen, deadline);
      Connection c = RedisProbe.connect(s, login, deadline);
      try {
        RedisCommandExecutor x = new RedisCommandExecutor(c, RedisCommandExecutor.Mode.PROBE, ctx.clock(), deadline,
            s.commandTimeoutMs());
        Map<String, Object> hello = Replies.pairs(RedisProbe.run(x, Cmd.HELLO, "2"));
        String server = Replies.text(hello.get("server"));
        String version = Replies.text(hello.get("version"));
        String product = "valkey".equals(server) ? "Valkey" : server == null || "redis".equals(server) ? "Redis"
            : server;
        Map<Integer, Long> keys = keys(x);
        RedisProbe.DatabaseListing listing = RedisProbe.databases(x, s.database());
        List<Option> options = new ArrayList<>();
        for (int db : listing.databases()) {
          Long n = keys.get(db);
          options.add(new Option(Integer.toString(db), null, n == null ? "no keys" : count(n)));
        }
        out.field("/connection/database", new Suggestions.Field(options, null, "the server", listing.complete()));
        if (chosen.autoTls()) {
          out.note(s.tls() ? "The server speaks TLS: Auto connects with TLS."
              : "The server doesn't speak TLS: Auto connects without it, so the traffic isn't encrypted.");
        }
        out.note("Signed in as " + login.userOrDefault() + " on " + product + (version == null ? "" : " " + version)
            + "." + where(s.database(), keys));
      } finally {
        c.close();
      }
    } catch (SecretUnavailableException e) {
      out.note(e.getMessage());
    } catch (JedisException e) {
      out.note(RedisErrors.of(e, chosen).message());
    }
  }

  /** Keys per logical database from {@code INFO keyspace}, which lists only databases that hold keys. */
  private static Map<Integer, Long> keys(RedisCommandExecutor x) {
    Map<Integer, Long> out = new TreeMap<>();
    try {
      Matcher m = KEYSPACE.matcher(String.valueOf(Replies.text(RedisProbe.run(x, Cmd.INFO, "keyspace"))));
      while (m.find()) {
        int db = Integer.parseInt(m.group(1));
        if (db <= 1_023) {
          out.put(db, Long.parseLong(m.group(2)));
        }
      }
    } catch (JedisDataException | NumberFormatException e) {
      // A user without INFO: the databases come without their keys.
    }
    return out;
  }

  /** Where the keys are, when the chosen database has none and others have some. */
  private static String where(int database, Map<Integer, Long> keys) {
    if (keys.containsKey(database) || keys.isEmpty()) {
      return keys.containsKey(database) ? " Database " + database + " holds " + count(keys.get(database)) + "." : "";
    }
    List<String> others = new ArrayList<>();
    keys.forEach((db, n) -> others.add(db + " (" + count(n) + ")"));
    return " Database " + database + " has no keys; database" + (others.size() == 1 ? " " : "s ")
        + String.join(", ", others.subList(0, Math.min(5, others.size()))) + (others.size() > 5 ? ", …" : "")
        + (others.size() == 1 ? " has" : " have") + " some: choose one under Database.";
  }

  private static String count(long n) {
    return String.format(Locale.US, "%,d %s", n, n == 1 ? "key" : "keys");
  }

  private static Instant earlier(Instant a, Instant b) {
    return b == null || a.isBefore(b) ? a : b;
  }
}
