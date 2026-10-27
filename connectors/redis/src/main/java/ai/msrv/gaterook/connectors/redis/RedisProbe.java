package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Check;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Level;
import ai.msrv.gaterook.connector.api.TestResult.Permissions;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import redis.clients.jedis.BuilderFactory;
import redis.clients.jedis.ClientSetInfoConfig;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection (report 05 §5): one connection of its own, never the pool, nothing saved, gives up after 5 s. It
 * connects ({@code HELLO 2 AUTH …}), measures the median of three {@code PING}s, reads {@code HELLO} (server, version,
 * mode, role, modules), finds out how the server compares range bytes of a glob ({@link #chars}, written to the
 * metadata as {@code globChars} for the key lists) and then what the account may really do — a read-only user can't
 * ask, so it probes:
 * <ol>
 *   <li>{@code ACL GETUSER <user>}, when the user may run it: the rules as the server keeps them;</li>
 *   <li>{@code ACL DRYRUN <user> SET gw:probe:<uuid> 1} (Redis 7.0+, needs admin);</li>
 *   <li>{@code SET gw:probe:<uuid> 1 NX XX}: the server checks ACLs before it parses the flags, and {@code NX} with
 *       {@code XX} is a syntax error — {@code ERR syntax error} means the user can write, {@code NOPERM} that it
 *       can't, {@code READONLY} a replica; even a server that took both flags would write nothing ({@code XX} on a
 *       key that doesn't exist). Sent only to Redis and Valkey ({@code HELLO.server});</li>
 *   <li>{@code CONFIG GET maxmemory}: allowed means an administrator;</li>
 *   <li>reads: {@code DBSIZE}, {@code SCAN 0 COUNT 10} and {@code TYPE} of a key — {@code NOPERM} there means key
 *       patterns limit the user.</li>
 * </ol>
 * The refused probes show up in the server's {@code ACL LOG}; the UI says so. Hints are in the kit's tone, with a
 * read-only ACL line for the server's version as a snippet.
 */
final class RedisProbe {

  static final Duration BUDGET = Duration.ofSeconds(5);
  private static final Set<String> PROBED_SERVERS = Set.of("redis", "valkey");
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private RedisProbe() {}

  static TestResult run(ConnectionConfig cfg, RedisSettings s, Credentials login, ExecContext ctx) {
    Clock clock = ctx.clock();
    Instant deadline = earlier(clock.instant().plus(BUDGET), ctx.deadline());
    List<Check> checks = new ArrayList<>();
    long t0 = System.nanoTime();
    Connection c;
    try {
      c = ScopedValue.where(GuardedSocketFactory.DEADLINE, deadline).call(() -> new Connection(
          GuardedSocketFactory.oneSocket(new GuardedSocketFactory(s)), DefaultJedisClientConfig.builder()
              .protocol(RedisProtocol.RESP2)
              .connectionTimeoutMillis(s.connectTimeoutMs())
              .socketTimeoutMillis(s.commandTimeoutMs())
              .database(s.database())
              .clientName("mcp-gateway")
              .clientSetInfoConfig(ClientSetInfoConfig.withLibNameSuffix("mcp-gateway"))
              .credentialsProvider(login::asRedisCredentials)
              .build()));
    } catch (JedisException e) {
      return RedisErrors.of(e, s).test(technical(e));
    } catch (RuntimeException e) {
      return RedisErrors.of(e, s).test(technical(e));
    }
    checks.add(new Check("connect", true, ms(t0), (s.tls() ? "TLS · " : "") + s.hostPort()));
    try {
      ReadOnlyCommandExecutor x = new ReadOnlyCommandExecutor(c, ReadOnlyCommandExecutor.Mode.PROBE, clock,
          deadline, s.commandTimeoutMs());
      return probe(cfg, s, login, x, checks, clock);
    } catch (JedisException e) {
      return RedisErrors.of(e, s).test(technical(e)).withChecks(checks);
    } finally {
      c.close();
    }
  }

  private static TestResult probe(ConnectionConfig cfg, RedisSettings s, Credentials login,
      ReadOnlyCommandExecutor x, List<Check> checks, Clock clock) {
    long[] pings = new long[3];
    for (int i = 0; i < pings.length; i++) {
      long t = System.nanoTime();
      run(x, Cmd.PING);
      pings[i] = System.nanoTime() - t;
    }
    Arrays.sort(pings);
    long latencyMs = Math.max(0, pings[1] / 1_000_000);
    Map<String, Object> hello = Replies.pairs(run(x, Cmd.HELLO, "2"));
    String server = text(hello.get("server"), "redis");
    String version = text(hello.get("version"), "");
    String mode = text(hello.get("mode"), "standalone");
    String role = text(hello.get("role"), "master");
    List<String> modules = new ArrayList<>();
    for (Object m : Replies.list(hello.get("modules"))) {
      Object name = Replies.pairs(m).get("name");
      if (name != null) {
        modules.add(Replies.text(name));
      }
    }
    if (mode.equals("cluster")) {
      return TestResult.failure("redis.cluster", RedisErrors.CLUSTER, "HELLO mode=cluster").withChecks(checks);
    }
    if (mode.equals("sentinel")) {
      return TestResult.failure("redis.sentinel", "This is a Sentinel, not a data server. Connect to the server it "
          + "watches (or one of its replicas).", "HELLO mode=sentinel").withChecks(checks);
    }
    String product = server.equals("valkey") ? "Valkey" : server.equals("redis") ? "Redis" : server;
    String user = login.userOrDefault();
    RedisGlob.Chars chars = chars(x);

    // What the account may do.
    String rules = null;
    String keys = null;
    boolean admin = false;
    try {
      Map<String, Object> u = Replies.pairs(run(x, Cmd.ACL, "GETUSER", user));
      rules = words(u.get("commands"));
      keys = words(u.get("keys"));
      // "+@all -@dangerous" isn't an administrator: CONFIG, FLUSHALL and ACL are in both @admin and @dangerous.
      admin = rules != null && (rules.contains("+@all") || rules.contains("+@admin"))
          && !rules.contains("-@admin") && !rules.contains("-@dangerous");
    } catch (JedisDataException e) {
      // NOPERM for a reader: the next probes tell.
    }
    Boolean write = null;
    String writeHow = null;
    if (admin || rules != null) {
      try {
        String dry = Replies.text(run(x, Cmd.ACL, "DRYRUN", user, "SET", probeKey(), "1"));
        write = "OK".equals(dry);
        writeHow = write ? "ACL DRYRUN SET: allowed" : "ACL DRYRUN SET: " + dry;
      } catch (JedisDataException e) {
        // Redis 6.2 has no DRYRUN; a user may not run it.
      }
    }
    boolean replica = role.equals("slave") || role.equals("replica");
    if (write == null && PROBED_SERVERS.contains(server)) {
      try {
        Object r = run(x, Cmd.SET, probeKey(), "1", "NX", "XX");
        // A server that accepted NX with XX wrote nothing (XX on a key that doesn't exist) but would write.
        write = true;
        writeHow = "SET … NX XX was accepted (" + (r == null ? "nil" : Replies.text(r)) + ")";
      } catch (JedisAccessControlException e) {
        write = false;
        writeHow = "SET: NOPERM";
      } catch (JedisDataException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.startsWith("READONLY")) {
          write = false;
          replica = true;
          writeHow = "SET: READONLY (a replica)";
        } else if (m.contains("syntax error")) {
          write = true;
          writeHow = "SET … NX XX: syntax error after the ACL check";
        }
      }
    }
    if (!admin) {
      try {
        run(x, Cmd.CONFIG, "GET", "maxmemory");
        admin = true;
      } catch (JedisDataException e) {
        // NOPERM, or CONFIG renamed (managed servers): not an administrator as far as we can tell.
      }
    }

    // Reads.
    long dbsize = -1;
    boolean keyPatterns = false;
    try {
      dbsize = Replies.integer(run(x, Cmd.DBSIZE), -1);
      List<Object> scan = Replies.list(run(x, Cmd.SCAN, "0", "COUNT", 10));
      List<Object> sample = Replies.list(scan.size() > 1 ? scan.get(1) : null);
      if (!sample.isEmpty()) {
        try {
          run(x, Cmd.TYPE, Replies.bytes(sample.getFirst()));
        } catch (JedisAccessControlException e) {
          keyPatterns = true;
        }
      }
      checks.add(new Check("read", true, 0, dbsize < 0 ? "keys" : String.format(Locale.US, "%,d keys in database %d",
          dbsize, s.database())));
    } catch (JedisDataException e) {
      checks.add(new Check("read", false, 0, firstLine(e.getMessage())));
    }

    Level level = admin ? Level.ADMIN : write == null ? Level.UNKNOWN : write ? Level.WRITE : Level.READ;
    String readText = rules != null ? rules + (keys == null ? "" : " " + keys)
        : keyPatterns ? "reads, limited by key patterns" : "reads";
    String writeText = write == null ? "unknown" : write ? "can write" : "can't write";
    ObjectNode details = JSON.objectNode();
    if (writeHow != null) {
      details.put("writeProbe", writeHow);
    }
    Permissions permissions = new Permissions(level, readText, writeText,
        admin ? List.of("CONFIG") : List.of(), details);
    TestResult result = TestResult.success(latencyMs, product + (version.isEmpty() ? "" : " " + version), user,
        permissions).withChecks(checks);

    ObjectNode snippets = JSON.objectNode();
    Env env = cfg.env();
    if (admin) {
      result = result.withHint(new Hint(Severity.DANGER, "redis.admin", "credentials.user", "This user can change "
          + "the server configuration and flush data. Use a read-only user."));
    }
    if (Boolean.TRUE.equals(write) || admin) {
      if (user.equals("default")) {
        result = result.withHint(new Hint(Severity.WARNING, "redis.default-user", "credentials.user", "You're "
            + "connected as default, which can run every command. Create a read-only user for MCP Gateway."));
      }
      result = result.withHint(new Hint(env == Env.DEV ? Severity.INFO : Severity.WARNING, "redis.user-can-write",
          "credentials.user", "This user can write. Agents only read, but a read-only user keeps it that way."));
      snippets.putObject("redis.user-can-write").put("language", "text").put("text", readOnlyUser(version));
    }
    if (replica) {
      result = result.withHint(new Hint(Severity.INFO, "redis.replica", null, "Read-only replica: good for agent "
          + "reads."));
    }
    if (keyPatterns) {
      result = result.withHint(new Hint(Severity.INFO, "redis.key-patterns", null, "Key patterns limit what this "
          + "user reads. SCAN still lists every key name, so the gateway hides the ones Advanced › Keys excludes."));
    }
    if (!login.hasPassword()) {
      result = result.withHint(new Hint(env == Env.PROD ? Severity.DANGER : Severity.WARNING, "redis.no-password",
          "credentials.password", "This server lets clients in without a password: anyone who reaches "
              + s.hostPort() + " can run commands."));
    }
    if (!s.tls() && env != Env.DEV) {
      result = result.withHint(new Hint(Severity.WARNING, "redis.tls-off", "connection.tls", "TLS is off: the "
          + "password and the values travel in clear text."));
    }
    if (version.startsWith("6.0") || version.startsWith("5") || version.startsWith("4")) {
      result = result.withHint(new Hint(Severity.INFO, "redis.old", null, product + " " + version
          + " is old; some reads (XREVRANGE pages, SCAN TYPE) need 6.2."));
    }
    if (chars == RedisGlob.Chars.UNKNOWN && s.listsDependOnSign()) {
      result = result.withHint(new Hint(Severity.INFO, "redis.glob-sign", "advanced.keyAllowlist", "The server "
          + "didn't say how it compares bytes from 0x80 on in [ranges] of key patterns, and a pattern of the key lists "
          + "has such a range: the gateway reads a key only if both ways allow it."));
    }
    ArrayNode moduleList = JSON.arrayNode();
    modules.forEach(moduleList::add);
    ObjectNode meta = JSON.objectNode()
        .put("serverVersion", product + (version.isEmpty() ? "" : " " + major(version)))
        .put("server", server).put("version", version).put("mode", mode).put("role", role);
    if (chars.id != null) {
      meta.put(RedisSettings.GLOB_CHARS, chars.id);
    }
    meta.set("modules", moduleList.deepCopy());
    ObjectNode d = JSON.objectNode();
    d.set("snippets", snippets);
    d.put("tls", s.tls());
    d.put("database", s.database());
    if (dbsize >= 0) {
      d.put("keys", dbsize);
    }
    d.set("modules", moduleList);
    d.put(RedisSettings.GLOB_CHARS, chars.id == null ? "unknown" : chars.id);
    d.put("probe", "The test sends SET … NX XX, which can't write; refused probes show in ACL LOG.");
    return result.withMetadata(meta).withDetails(d);
  }

  /**
   * How the server compares the bytes of glob ranges ({@link RedisGlob.Chars}), found without touching a key.
   * {@code COMMAND LIST FILTERBY PATTERN [\xff-a]et} (Redis 7.0+, any user with {@code @connection}) runs the same
   * {@code stringmatchlen} as {@code SCAN MATCH} over command names: with signed chars {@code 0xff} is -1 and the
   * range {@code [-1..'a']} holds neither {@code g} nor {@code s}, with unsigned chars the ends swap to
   * {@code ['a'..0xff]} and {@code get} and {@code set} match. On 6.2, or for a user without {@code COMMAND},
   * {@code INFO server} names the platform ({@code @dangerous}: administrators only); otherwise it stays unknown.
   */
  static RedisGlob.Chars chars(ReadOnlyCommandExecutor x) {
    try {
      return Replies.list(run(x, Cmd.COMMAND, "LIST", "FILTERBY", "PATTERN", SIGN_PROBE)).isEmpty()
          ? RedisGlob.Chars.SIGNED : RedisGlob.Chars.UNSIGNED;
    } catch (JedisDataException e) {
      // 6.2 has no COMMAND LIST; a server may rename or refuse COMMAND.
    }
    try {
      return RedisGlob.Chars.ofPlatform(infoField(Replies.text(run(x, Cmd.INFO, "server")), "os"));
    } catch (JedisDataException e) {
      return RedisGlob.Chars.UNKNOWN;
    }
  }

  /** The pattern of {@link #chars}: {@code [}, the byte 0xff, {@code -a]et}. */
  static final byte[] SIGN_PROBE = {'[', (byte) 0xff, '-', 'a', ']', 'e', 't'};

  /** {@code field:value} of an {@code INFO} section, or {@code null}. */
  static String infoField(String info, String field) {
    if (info == null) {
      return null;
    }
    for (String line : info.lines().toList()) {
      if (line.startsWith(field + ":")) {
        return line.substring(field.length() + 1).strip();
      }
    }
    return null;
  }

  /** A read-only ACL user for the server's version (report 05 §5): 6.2 needs CLIENT SETNAME and ID by name. */
  static String readOnlyUser(String version) {
    boolean v7 = !version.startsWith("6.");
    return "ACL SETUSER gw_reader on >CHANGE-ME ~* resetchannels -@all +@read +@connection -@dangerous "
        + (v7 ? "" : "+client|setname +client|id ") + "-dump -xread -lolwut";
  }

  private static Object run(ReadOnlyCommandExecutor x, Cmd command, Object... args) {
    return x.executeCommand(new CommandObject<>(RedisSession.args(command, args), BuilderFactory.RAW_OBJECT));
  }

  private static String probeKey() {
    return ReadOnlyCommandExecutor.PROBE_PREFIX + UUID.randomUUID();
  }

  private static String major(String version) {
    int dot = version.indexOf('.');
    return dot < 0 ? version : version.substring(0, dot);
  }

  /** A rule of ACL GETUSER: one string (7.0) or a list of patterns (6.2), as one line. */
  private static String words(Object o) {
    if (o instanceof List<?> l) {
      List<String> out = new ArrayList<>();
      for (Object x : l) {
        out.add(Replies.text(x));
      }
      return String.join(" ", out);
    }
    return Replies.text(o);
  }

  private static String text(Object o, String dflt) {
    String t = Replies.text(o);
    return t == null ? dflt : t;
  }

  private static long ms(long t0) {
    return (System.nanoTime() - t0) / 1_000_000;
  }

  private static Instant earlier(Instant a, Instant b) {
    return b == null || a.isBefore(b) ? a : b;
  }

  private static String technical(Throwable e) {
    StringBuilder b = new StringBuilder(e.getClass().getSimpleName());
    if (e.getMessage() != null) {
      b.append(": ").append(firstLine(e.getMessage()));
    }
    Throwable c = e.getCause();
    if (c != null && c != e) {
      b.append(" (").append(c.getClass().getSimpleName());
      if (c.getMessage() != null) {
        b.append(": ").append(firstLine(c.getMessage()));
      }
      b.append(')');
    }
    return b.toString();
  }

  private static String firstLine(String m) {
    return m == null ? "" : m.lines().findFirst().orElse("").strip();
  }
}
