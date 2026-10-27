package ai.msrv.gaterook.connectors.redis;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.Connection;
import redis.clients.jedis.args.Rawable;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.executors.CommandExecutor;

/**
 * The only way this connector's commands reach a Redis server: every command — single or in a batch — is checked
 * against an allowlist of names <em>and</em> shapes before a byte is written (report 05 §1, §7). Anything else —
 * {@code SET}, {@code FLUSHALL}, {@code KEYS}, {@code EVAL}, {@code CONFIG SET}, {@code CLIENT KILL}, even a
 * {@code Protocol.Command} of Jedis — fails here with {@link RefusedCommandException}, never at the server.
 *
 * <p>One executor serves one borrowed connection for one call. Before every command it sets the socket's read timeout
 * to the connection's command timeout, cut to what is left of the call's deadline, so no command outlives the call.
 * Jedis' own pipelines would bypass a {@link CommandExecutor}; this connector never uses them — {@link #executeBatch}
 * is its pipeline, under the same checks.
 */
final class ReadOnlyCommandExecutor implements CommandExecutor {

  /** {@link #READS} for the tools, {@link #PROBE} for Test connection (reads plus the exact permission probes). */
  enum Mode { READS, PROBE }

  /** A command the gateway never sends. Indicates a bug in the connector: no agent input names a command. */
  static final class RefusedCommandException extends JedisException {
    private static final long serialVersionUID = 1L;

    RefusedCommandException(String message) {
      super(message);
    }
  }

  /** The prefix of the probe key: it is never created ({@code NX} and {@code XX} together write nothing). */
  static final String PROBE_PREFIX = "gw:probe:";

  private static final Set<String> KEY_ONLY = Set.of("TYPE", "PTTL", "STRLEN", "GET", "HLEN", "LLEN", "SCARD",
      "ZCARD", "XLEN", "JSON.GET");

  private final Connection connection;
  private final Mode mode;
  private final Clock clock;
  private final Instant deadline;
  private final int commandTimeoutMs;

  ReadOnlyCommandExecutor(Connection connection, Mode mode, Clock clock, Instant deadline, int commandTimeoutMs) {
    this.connection = connection;
    this.mode = mode;
    this.clock = clock;
    this.deadline = deadline;
    this.commandTimeoutMs = commandTimeoutMs;
  }

  @Override
  public <T> T executeCommand(CommandObject<T> commandObject) {
    check(commandObject.getArguments(), mode);
    applyTimeout();
    return connection.executeCommand(commandObject);
  }

  /**
   * Sends {@code commands} back to back and reads their replies, one round trip: a reply is the value, or the
   * {@code JedisDataException} the server answered for that command. Every command is checked before any is sent.
   */
  List<Object> executeBatch(List<CommandArguments> commands) {
    for (CommandArguments c : commands) {
      check(c, mode);
    }
    if (commands.isEmpty()) {
      return List.of();
    }
    applyTimeout();
    for (CommandArguments c : commands) {
      connection.sendCommand(c);
    }
    return new ArrayList<>(connection.getMany(commands.size()));
  }

  /** How long the next command may take: the command timeout, or what is left of the call if that is less. */
  int timeoutMs() {
    long left = Duration.between(clock.instant(), deadline).toMillis();
    return (int) Math.max(0, Math.min(commandTimeoutMs, left));
  }

  private void applyTimeout() {
    int ms = timeoutMs();
    if (ms <= 0) {
      throw new CallDeadlineException();
    }
    connection.setSoTimeout(ms);
  }

  /** The call ran out of time before the next command; nothing was sent. */
  static final class CallDeadlineException extends JedisException {
    private static final long serialVersionUID = 1L;

    CallDeadlineException() {
      super("The call reached its deadline");
    }
  }

  @Override
  public void close() {
    connection.close();
  }

  /** Refuses {@code args} unless it is a command of {@code mode} in a shape this connector builds. */
  static void check(CommandArguments args, Mode mode) {
    List<String> a = words(args);
    String name = a.getFirst().toUpperCase(Locale.ROOT);
    int n = a.size();
    boolean ok = switch (name) {
      case "PING", "DBSIZE" -> n == 1;
      case "HELLO" -> n == 2 && (a.get(1).equals("2") || a.get(1).equals("3"));
      case "CLIENT" -> n == 3 && is(a.get(1), "NO-TOUCH") && (is(a.get(2), "ON") || is(a.get(2), "OFF"));
      case "MEMORY" -> n == 3 && is(a.get(1), "USAGE");
      case "GETRANGE", "LRANGE" -> n == 4;
      case "ZRANGE" -> n == 4 || (n == 5 && is(a.get(4), "WITHSCORES"));
      case "XREVRANGE" -> n == 4 || (n == 6 && is(a.get(4), "COUNT"));
      case "HSCAN", "SSCAN" -> n == 3 || (n == 5 && is(a.get(3), "COUNT"));
      case "SCAN" -> n >= 2 && options(a, 2, Set.of("MATCH", "COUNT", "TYPE"));
      case "SET" -> mode == Mode.PROBE && n == 5 && a.get(1).startsWith(PROBE_PREFIX) && a.get(2).equals("1")
          && is(a.get(3), "NX") && is(a.get(4), "XX");
      case "ACL" -> mode == Mode.PROBE && ((n == 3 && is(a.get(1), "GETUSER"))
          || (n == 6 && is(a.get(1), "DRYRUN") && is(a.get(3), "SET") && a.get(4).startsWith(PROBE_PREFIX)
              && a.get(5).equals("1")));
      case "CONFIG" -> mode == Mode.PROBE && n == 3 && is(a.get(1), "GET")
          && (is(a.get(2), "maxmemory") || is(a.get(2), "databases"));
      // How the server compares range bytes of a glob (RedisProbe.chars): a match over command names, then INFO.
      case "COMMAND" -> mode == Mode.PROBE && n == 5 && is(a.get(1), "LIST") && is(a.get(2), "FILTERBY")
          && is(a.get(3), "PATTERN");
      case "INFO" -> mode == Mode.PROBE && n == 2 && (is(a.get(1), "server") || is(a.get(1), "keyspace"));
      default -> KEY_ONLY.contains(name) && n == 2;
    };
    if (!ok) {
      throw new RefusedCommandException("Command " + name + " is not allowed by the gateway"
          + (n > 1 ? " in this form" : ""));
    }
  }

  /** {@code [option value]…} from {@code from} on, each option once, from {@code allowed}. */
  private static boolean options(List<String> a, int from, Set<String> allowed) {
    if ((a.size() - from) % 2 != 0) {
      return false;
    }
    Set<String> seen = new java.util.HashSet<>();
    for (int i = from; i < a.size(); i += 2) {
      String o = a.get(i).toUpperCase(Locale.ROOT);
      if (!allowed.contains(o) || !seen.add(o)) {
        return false;
      }
    }
    return true;
  }

  private static boolean is(String word, String expected) {
    return word.equalsIgnoreCase(expected);
  }

  /** The command and its arguments as text (ISO-8859-1: one char per byte, so binary keys keep their length). */
  private static List<String> words(CommandArguments args) {
    List<String> out = new ArrayList<>(args.size());
    for (Rawable r : args) {
      out.add(new String(r.getRaw(), StandardCharsets.ISO_8859_1));
    }
    return out;
  }
}
