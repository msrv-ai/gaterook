package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Locale;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisClusterException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.exceptions.JedisProtocolNotSupportedException;
import redis.clients.jedis.exceptions.JedisRedirectionException;

/**
 * What went wrong between the gateway and a Redis server, in the kit's tone (report 05 §5): what happened and what
 * to do, with a stable code for Test connection and the right {@link ConnectorException.Kind} for a call — only an
 * unreachable or silent server counts against the circuit breaker. Texts name the host and the user, never a value.
 */
final class RedisErrors {

  /** A failure described once for both callers. */
  record Problem(String code, ConnectorException.Kind kind, String message) {
    ConnectorException exception(Throwable cause) {
      return new ConnectorException(kind, message, cause);
    }

    TestResult test(String technical) {
      return TestResult.failure(code, message, technical);
    }
  }

  private RedisErrors() {}

  /** A failure to open or authenticate a connection. */
  static ConnectorException connect(JedisException e, RedisSettings s) {
    return of(e, s).exception(e);
  }

  /** A failure of a command of a call. */
  static ConnectorException call(JedisException e, RedisSettings s) {
    return of(e, s).exception(e);
  }

  static Problem of(Throwable e, RedisSettings s) {
    if (e instanceof ReadOnlyCommandExecutor.RefusedCommandException r) {
      return new Problem("redis.refused-command", ConnectorException.Kind.INTERNAL, r.getMessage());
    }
    if (e instanceof ReadOnlyCommandExecutor.CallDeadlineException) {
      return new Problem("redis.timeout", ConnectorException.Kind.TIMEOUT, s.hostPort()
          + " didn't answer before the call's time ran out.");
    }
    if (e instanceof JedisProtocolNotSupportedException) {
      return new Problem("redis.too-old", ConnectorException.Kind.RESOURCE_ERROR,
          "This server doesn't know HELLO: Redis 6.0 or later is required.");
    }
    if (e instanceof JedisAccessControlException a) {
      String m = a.getMessage() == null ? "" : a.getMessage();
      if (m.startsWith("WRONGPASS")) {
        return new Problem("redis.auth", ConnectorException.Kind.RESOURCE_ERROR, "Wrong user name or password for "
            + s.userOrDefault() + " on " + s.hostPort() + ".");
      }
      if (m.startsWith("NOAUTH")) {
        return new Problem("redis.noauth", ConnectorException.Kind.RESOURCE_ERROR,
            "This server needs a password: set Password in Credentials.");
      }
      return new Problem("redis.noperm", ConnectorException.Kind.RESOURCE_ERROR, "The user " + s.userOrDefault()
          + " may not do this: " + firstLine(m));
    }
    if (e instanceof JedisRedirectionException || e instanceof JedisClusterException) {
      return new Problem("redis.cluster", ConnectorException.Kind.RESOURCE_ERROR, CLUSTER);
    }
    if (e instanceof JedisDataException d) {
      String m = d.getMessage() == null ? "" : d.getMessage();
      if (m.startsWith("LOADING")) {
        return new Problem("redis.loading", ConnectorException.Kind.RESOURCE_UNAVAILABLE,
            "Redis is loading its data into memory. Try again in a moment.");
      }
      if (m.startsWith("ERR DB index is out of range") || m.contains("SELECT is not allowed")) {
        return new Problem("redis.database", ConnectorException.Kind.RESOURCE_ERROR, "Database " + s.database()
            + " doesn't exist on " + s.hostPort() + ".");
      }
      return new Problem("redis.error", ConnectorException.Kind.RESOURCE_ERROR, "Redis answered: " + firstLine(m));
    }
    for (Throwable c = e; c != null; c = c.getCause()) {
      if (c instanceof GuardedSocketFactory.AddressRefusedException r) {
        return new Problem("redis.address", ConnectorException.Kind.DENIED, r.getMessage());
      }
      if (c instanceof UnknownHostException) {
        return new Problem("redis.unknown-host", ConnectorException.Kind.RESOURCE_UNAVAILABLE, "Can't resolve "
            + s.host() + ". Check the VPN or the host name.");
      }
      if (c instanceof ConnectException) {
        return new Problem("redis.refused", ConnectorException.Kind.RESOURCE_UNAVAILABLE, "Connection refused on port "
            + s.port() + " of " + s.host() + ". Is redis-server running there?");
      }
      if (c instanceof GuardedSocketFactory.TlsHandshakeTimeoutException) {
        return new Problem("redis.tls", ConnectorException.Kind.TIMEOUT, "TLS: " + s.hostPort() + " didn't answer the "
            + "TLS handshake within " + seconds(s.commandTimeoutMs()) + ". If this port doesn't speak TLS, turn off "
            + "Use TLS.");
      }
      if (c instanceof SocketTimeoutException t) {
        boolean connecting = t.getMessage() != null && t.getMessage().toLowerCase(Locale.ROOT).contains("connect");
        return new Problem("redis.timeout", ConnectorException.Kind.TIMEOUT, s.hostPort() + " didn't "
            + (connecting ? "accept a connection within " + seconds(s.connectTimeoutMs())
                : "answer within " + seconds(s.commandTimeoutMs())) + ". Check the VPN or the host.");
      }
      if (c instanceof SSLException ssl) {
        return tls(ssl, s);
      }
    }
    String m = e.getMessage() == null ? "" : e.getMessage();
    // A TLS port drops a client that speaks plain RESP: an end of stream, or a reset when bytes were still unread.
    if (e instanceof JedisConnectionException && (m.contains("Unexpected end of stream")
        || m.contains("Connection reset"))) {
      return new Problem("redis.closed", ConnectorException.Kind.RESOURCE_UNAVAILABLE, s.hostPort()
          + " closed the connection." + (s.tls() ? "" : " If this is a TLS port, turn on Use TLS."));
    }
    return new Problem("redis.unreachable", ConnectorException.Kind.RESOURCE_UNAVAILABLE, "Can't talk to "
        + s.hostPort() + ": " + firstLine(m.isEmpty() ? e.getClass().getSimpleName() : m));
  }

  static final String CLUSTER = "Redis Cluster isn't supported yet. Connect to a standalone server or a replica.";

  private static Problem tls(SSLException e, RedisSettings s) {
    String all = chain(e);
    if (all.contains("No subject alternative") || all.contains("No name matching")) {
      return new Problem("redis.tls", ConnectorException.Kind.RESOURCE_ERROR, "TLS: the certificate doesn't match "
          + s.host() + ".");
    }
    if (all.contains("PKIX") || hasCause(e, CertificateException.class)) {
      return new Problem("redis.tls", ConnectorException.Kind.RESOURCE_ERROR,
          "TLS: the server certificate isn't trusted. Add its CA certificate.");
    }
    if (all.contains("Unsupported or unrecognized SSL message") || all.contains("plaintext")) {
      return new Problem("redis.tls", ConnectorException.Kind.RESOURCE_ERROR, "TLS: " + s.hostPort()
          + " doesn't speak TLS. Turn off Use TLS, or use the server's TLS port.");
    }
    return new Problem("redis.tls", ConnectorException.Kind.RESOURCE_ERROR, "TLS failed with " + s.hostPort() + ": "
        + firstLine(e instanceof SSLHandshakeException && e.getMessage() != null ? e.getMessage() : all));
  }

  private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
    for (Throwable c = e; c != null; c = c.getCause()) {
      if (type.isInstance(c)) {
        return true;
      }
    }
    return false;
  }

  private static String chain(Throwable e) {
    StringBuilder b = new StringBuilder();
    for (Throwable c = e; c != null; c = c.getCause()) {
      if (c.getMessage() != null) {
        b.append(c.getMessage()).append(" | ");
      }
    }
    return b.toString();
  }

  static String seconds(long ms) {
    return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
  }

  private static String firstLine(String m) {
    String line = m.lines().findFirst().orElse("").strip();
    return line.length() <= 300 ? line : line.substring(0, 300) + "…";
  }
}
