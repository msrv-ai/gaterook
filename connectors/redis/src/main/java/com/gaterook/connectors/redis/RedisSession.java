package com.gaterook.connectors.redis;

import com.gaterook.connector.api.ConnectorException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import redis.clients.jedis.BuilderFactory;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * One call's connection of a pool: the commands of the call go one after the other through its
 * {@link RedisCommandExecutor}. A socket that served an earlier call and turns out dead on the first read is
 * replaced once. Writes use a separate executor mode and are never retried. {@link #abort()} cuts a cancelled
 * call; {@link #close()} returns a healthy connection to its pool and drops a broken one.
 */
final class RedisSession implements AutoCloseable {

  private final RedisClients.Pool pool;
  private final Clock clock;
  private final Instant deadline;
  private BoundedConnection connection;
  private RedisCommandExecutor executor;
  private int keep = -1;
  private boolean reused;
  private boolean sentAny;
  private volatile boolean aborted;

  RedisSession(RedisClients.Pool pool, RedisClients.Borrowed borrowed, Clock clock, Instant deadline)
      throws ConnectorException {
    this.pool = pool;
    this.clock = clock;
    this.deadline = deadline;
    attach(borrowed);
  }

  RedisSettings settings() {
    return pool.settings;
  }

  /** A command on the wire, as its raw reply (RESP2: {@code byte[]}, {@code Long}, {@code List}, {@code null}). */
  Object call(Cmd command, Object... args) throws ConnectorException {
    CommandArguments a = args(command, args);
    while (true) {
      connection.keep(keep);
      try {
        Object reply = executor.executeCommand(new CommandObject<>(a, BuilderFactory.RAW_OBJECT));
        sentAny = true;
        return reply;
      } catch (JedisConnectionException e) {
        if (!retry(e)) {
          throw e;
        }
      }
    }
  }

  /**
   * A write is never retried: losing its reply does not mean Redis did not apply it, so a connection that fails once
   * the write was sent is an unknown outcome ({@code OUTCOME_UNKNOWN}: the core never runs the request again, and the
   * circuit breaker doesn't count it). A connection that served an earlier call is checked with a {@code PING} first,
   * which {@link #call} replaces once if it went stale, so that a dead pooled socket is an ordinary failure before
   * anything was written rather than an unknown outcome.
   */
  Object write(RedisWrite write) throws ConnectorException {
    if (aborted) {
      throw ConnectorException.timeout("The call was stopped before the write was sent.");
    }
    if (reused && !sentAny) {
      call(Cmd.PING);
    }
    RedisCommandExecutor writer = new RedisCommandExecutor(connection, RedisCommandExecutor.Mode.WRITES, clock,
        deadline, pool.settings.commandTimeoutMs());
    sentAny = true;
    try {
      return writer.executeCommand(new CommandObject<>(args(write.command(), write.arguments().toArray()),
          BuilderFactory.RAW_OBJECT));
    } catch (JedisConnectionException e) {
      abort();
      throw ConnectorException.outcomeUnknown("Outcome unknown: Redis did not confirm the write on "
          + pool.settings.hostPort() + "; it may have been applied. Read the key before retrying.", e);
    }
  }

  /**
   * A read of values: like {@link #call}, but each bulk string of the reply keeps at most {@code bytes} bytes. A longer
   * one is a {@link BoundedConnection.Cut}, its rest read off the socket without being held.
   */
  Object read(int bytes, Cmd command, Object... args) throws ConnectorException {
    keep = bytes;
    try {
      return call(command, args);
    } finally {
      keep = -1;
      if (connection != null) {
        connection.keep(-1);
      }
    }
  }

  /** {@code commands} in one round trip: each reply, or the {@link JedisDataException} the server answered. */
  List<Object> batch(List<CommandArguments> commands) throws ConnectorException {
    while (true) {
      try {
        List<Object> replies = executor.executeBatch(commands);
        sentAny = true;
        return replies;
      } catch (JedisConnectionException e) {
        if (!retry(e)) {
          throw e;
        }
      }
    }
  }

  static CommandArguments args(Cmd command, Object... args) {
    CommandArguments a = new CommandArguments(command);
    for (Object o : args) {
      if (o instanceof byte[] b) {
        a.add(b);
      } else {
        a.add(String.valueOf(o));
      }
    }
    return a;
  }

  /** Cuts the socket: a cancelled call's command returns at once with an error, and the connection is dropped. */
  void abort() {
    aborted = true;
    BoundedConnection c = connection;
    if (c != null) {
      RedisClients.disconnect(c);
    }
  }

  @Override
  public void close() {
    BoundedConnection c = connection;
    connection = null;
    if (c != null) {
      pool.release(c, aborted);
    }
  }

  private void attach(RedisClients.Borrowed borrowed) throws ConnectorException {
    connection = borrowed.connection();
    reused = borrowed.reused();
    executor = new RedisCommandExecutor(connection, RedisCommandExecutor.Mode.READS, clock, deadline,
        pool.settings.commandTimeoutMs());
    if (!reused && pool.settings.noTouch() && !pool.noTouchUnsupported()) {
      // Reads of the agent shouldn't change what the server evicts next (Redis 7.2+, report 05 §6).
      try {
        executor.executeCommand(new CommandObject<>(args(Cmd.CLIENT, "NO-TOUCH", "ON"), BuilderFactory.RAW_OBJECT));
      } catch (JedisDataException e) {
        pool.noTouchUnsupported(true);
      } catch (RuntimeException e) {
        close();
        throw e;
      }
    }
  }

  private boolean retry(JedisConnectionException e) throws ConnectorException {
    if (aborted || sentAny || !reused || e.getCause() instanceof SocketTimeoutException) {
      return false;
    }
    pool.release(connection, true);
    connection = null;
    attach(pool.borrow(deadline));
    return true;
  }
}
