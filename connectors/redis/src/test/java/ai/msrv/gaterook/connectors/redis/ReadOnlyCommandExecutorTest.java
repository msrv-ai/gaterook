package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connectors.redis.ReadOnlyCommandExecutor.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.BuilderFactory;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.args.Rawable;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * The executor refuses every command outside its read allowlist before a byte is written (report 05 §1, §8:
 * {@code SET}, {@code FLUSHALL}, {@code KEYS}, {@code EVAL}, {@code CONFIG}, {@code CLIENT KILL}), in single commands
 * and in batches, whatever object names the command — and the probes of Test connection only in their exact shape.
 * {@code RedisIT} checks the same on a real server's command statistics.
 */
class ReadOnlyCommandExecutorTest {

  /** A connection without a socket that records what would go on the wire. */
  static final class RecordingConnection extends Connection {
    final List<String> wire = new ArrayList<>();
    int soTimeout = -1;

    RecordingConnection() {
      super(() -> {
        throw new JedisConnectionException("no socket in this test");
      });
    }

    @Override
    public <T> T executeCommand(CommandObject<T> c) {
      wire.add(words(c.getArguments()));
      return c.getBuilder().build("OK".getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public void sendCommand(CommandArguments args) {
      wire.add(words(args));
    }

    @Override
    public List<Object> getMany(int count) {
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        out.add("OK".getBytes(StandardCharsets.US_ASCII));
      }
      return out;
    }

    @Override
    public void setSoTimeout(int soTimeout) {
      this.soTimeout = soTimeout;
    }

    private static String words(CommandArguments args) {
      List<String> out = new ArrayList<>();
      for (Rawable r : args) {
        out.add(new String(r.getRaw(), StandardCharsets.UTF_8));
      }
      return String.join(" ", out);
    }
  }

  private final RecordingConnection connection = new RecordingConnection();

  private ReadOnlyCommandExecutor executor(Mode mode) {
    return new ReadOnlyCommandExecutor(connection, mode, Clock.systemUTC(), Instant.now().plusSeconds(10), 2_000);
  }

  private static CommandObject<Object> command(Object command, Object... args) {
    CommandArguments a = switch (command) {
      case Cmd c -> new CommandArguments(c);
      case Protocol.Command c -> new CommandArguments(c);
      case String name -> new CommandArguments((ProtocolCommand) () -> name.getBytes(StandardCharsets.US_ASCII));
      default -> throw new IllegalArgumentException(command.toString());
    };
    for (Object o : args) {
      a.add(o instanceof byte[] b ? b : o.toString().getBytes(StandardCharsets.UTF_8));
    }
    return new CommandObject<>(a, BuilderFactory.RAW_OBJECT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SET k v", "SET k v NX", "FLUSHALL", "FLUSHDB", "KEYS *", "EVAL return 0", "DEL k",
      "CONFIG SET maxmemory 1", "CONFIG GET maxmemory", "CLIENT KILL 127.0.0.1:1", "CLIENT LIST", "CLIENT PAUSE 1000",
      "SHUTDOWN", "DEBUG SLEEP 1", "MEMORY PURGE", "HELLO 3 AUTH default x", "SCRIPT FLUSH", "ACL SETUSER x on",
      "ACL GETUSER default", "SET gw:probe:1 1 NX XX", "SORT k", "DUMP k", "XREAD STREAMS s 0", "RENAME a b",
      "EXPIRE k 1", "SELECT 2", "MONITOR", "SUBSCRIBE c", "JSON.SET k $ 1", "INFO server",
      "COMMAND LIST FILTERBY PATTERN x"})
  void writesAndAdminCommandsNeverReachTheWire(String line) {
    String[] w = line.split(" ");
    Object[] args = java.util.Arrays.copyOfRange(w, 1, w.length);
    ReadOnlyCommandExecutor x = executor(Mode.READS);
    assertThatThrownBy(() -> x.executeCommand(command(w[0], args)))
        .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class)
        .hasMessageContaining("not allowed by the gateway");
    // The same through Jedis' own command constants, when it has one.
    java.util.Optional<Protocol.Command> known = java.util.Arrays.stream(Protocol.Command.values())
        .filter(c -> c.name().equals(w[0])).findFirst();
    known.ifPresent(c -> assertThatThrownBy(() -> x.executeCommand(command(c, args)))
        .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class));
    assertThat(connection.wire).isEmpty();
  }

  @Test
  void aCommandOfOurOwnEnumIsCheckedByItsShapeToo() {
    ReadOnlyCommandExecutor x = executor(Mode.READS);
    // SET exists in the enum for the probe; the read executor refuses it in any shape.
    assertThatThrownBy(() -> x.executeCommand(command(Cmd.SET, "gw:probe:1", "1", "NX", "XX")))
        .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
    assertThatThrownBy(() -> x.executeCommand(command(Cmd.CLIENT, "KILL", "ID", "7")))
        .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
    assertThatThrownBy(() -> x.executeCommand(command(Cmd.MEMORY, "DOCTOR")))
        .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
    assertThatThrownBy(() -> x.executeCommand(command(Cmd.HELLO, "3", "AUTH", "default", "x")))
        .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
    assertThatThrownBy(() -> x.executeCommand(command(Cmd.SCAN, "0", "MATCH", "*", "MATCH", "x")))
        .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
    assertThat(connection.wire).isEmpty();
  }

  @Test
  void theReadsOfTheToolsGoThrough() {
    ReadOnlyCommandExecutor x = executor(Mode.READS);
    x.executeCommand(command(Cmd.TYPE, "cache:1"));
    x.executeCommand(command(Cmd.MEMORY, "USAGE", "cache:1"));
    x.executeCommand(command(Cmd.CLIENT, "NO-TOUCH", "ON"));
    x.executeCommand(command(Cmd.SCAN, "0", "MATCH", "cache:*", "COUNT", "100", "TYPE", "hash"));
    x.executeCommand(command(Cmd.ZRANGE, "z", "0", "9", "WITHSCORES"));
    x.executeCommand(command(Cmd.XREVRANGE, "s", "+", "-", "COUNT", "10"));
    x.executeCommand(command(Cmd.GETRANGE, "k", "0", "65535"));
    x.executeCommand(command(Cmd.HELLO, "2"));
    assertThat(connection.wire).hasSize(8);
    assertThat(connection.soTimeout).isBetween(1, 2_000);
  }

  @Test
  void oneRefusedCommandStopsTheWholeBatchBeforeAnyIsSent() {
    ReadOnlyCommandExecutor x = executor(Mode.READS);
    List<CommandArguments> batch = List.of(command(Cmd.TYPE, "a").getArguments(),
        command(Protocol.Command.FLUSHALL).getArguments(), command(Cmd.PTTL, "a").getArguments());
    assertThatThrownBy(() -> x.executeBatch(batch)).isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
    assertThat(connection.wire).isEmpty();

    assertThat(x.executeBatch(List.of(command(Cmd.TYPE, "a").getArguments(), command(Cmd.PTTL, "a").getArguments())))
        .hasSize(2);
    assertThat(connection.wire).containsExactly("TYPE a", "PTTL a");
  }

  @Test
  void theProbesPassOnlyInTheirExactShapeAndOnlyForTestConnection() {
    ReadOnlyCommandExecutor probe = executor(Mode.PROBE);
    probe.executeCommand(command(Cmd.SET, "gw:probe:7f", "1", "NX", "XX"));
    probe.executeCommand(command(Cmd.ACL, "GETUSER", "ro"));
    probe.executeCommand(command(Cmd.ACL, "DRYRUN", "ro", "SET", "gw:probe:7f", "1"));
    probe.executeCommand(command(Cmd.CONFIG, "GET", "maxmemory"));
    probe.executeCommand(command(Cmd.CONFIG, "GET", "databases"));
    probe.executeCommand(command(Cmd.COMMAND, "LIST", "FILTERBY", "PATTERN", RedisProbe.SIGN_PROBE));
    probe.executeCommand(command(Cmd.INFO, "server"));
    probe.executeCommand(command(Cmd.INFO, "keyspace"));
    assertThat(connection.wire).hasSize(8);
    for (Object[] bad : List.<Object[]>of(
        new Object[] {Cmd.SET, "gw:probe:7f", "1", "NX"},              // XX missing: it would create the key
        new Object[] {Cmd.SET, "user:1", "1", "NX", "XX"},             // not the probe key
        new Object[] {Cmd.SET, "gw:probe:7f", "2", "XX", "NX", "GET"},
        new Object[] {Cmd.ACL, "DRYRUN", "ro", "FLUSHALL"},
        new Object[] {Cmd.ACL, "SETUSER", "ro", "on"},
        new Object[] {Cmd.CONFIG, "GET", "requirepass"},
        new Object[] {Cmd.CONFIG, "SET", "maxmemory", "1"},
        new Object[] {Cmd.INFO},
        new Object[] {Cmd.INFO, "everything"},
        new Object[] {Cmd.COMMAND, "LIST"},
        new Object[] {Cmd.COMMAND, "GETKEYS", "SET", "k", "v"},
        new Object[] {Cmd.COMMAND, "LIST", "FILTERBY", "ACLCAT", "write", "x"})) {
      assertThatThrownBy(() -> probe.executeCommand(command(bad[0], java.util.Arrays.copyOfRange(bad, 1, bad.length))))
          .as(java.util.Arrays.toString(bad)).isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
    }
    assertThat(connection.wire).hasSize(8);
  }

  @Test
  void nothingIsSentOnceTheCallIsOutOfTime() {
    ReadOnlyCommandExecutor late = new ReadOnlyCommandExecutor(connection, Mode.READS, Clock.systemUTC(),
        Instant.now().minusMillis(1), 2_000);
    assertThatThrownBy(() -> late.executeCommand(command(Cmd.PING)))
        .isInstanceOf(ReadOnlyCommandExecutor.CallDeadlineException.class);
    assertThat(connection.wire).isEmpty();

    ReadOnlyCommandExecutor soon = new ReadOnlyCommandExecutor(connection, Mode.READS, Clock.systemUTC(),
        Instant.now().plus(Duration.ofMillis(300)), 2_000);
    soon.executeCommand(command(Cmd.PING));
    assertThat(connection.soTimeout).as("the command timeout cut to the call's deadline").isBetween(1, 300);
  }
}
