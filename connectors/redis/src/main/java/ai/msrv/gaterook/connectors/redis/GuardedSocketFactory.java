package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connectors.support.TrustStores;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import redis.clients.jedis.JedisSocketFactory;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Opens the TCP (and TLS) socket of one Redis connection. It resolves the host itself and refuses link-local, cloud
 * metadata and reserved addresses even when a name points there (§9: the connector never reaches what the egress
 * guard always closes); one such address among several refuses the name, as the guard's DNS does. No proxy, no JVM-wide
 * TLS defaults: the trust is the connection's private CA or the platform's, and {@code tlsVerify=full} checks the
 * certificate's name against the host (SNI for names).
 *
 * <p>The connect timeout is the connection's, cut to what is left of the call when a call opens the socket
 * ({@link #DEADLINE}): a pooled connection is opened on the caller's thread. Only a failed TCP connect moves on to
 * the host's next address; once one answered, its TLS or timeout failure is the answer.
 */
final class GuardedSocketFactory implements JedisSocketFactory {

  /** The deadline of the call that is opening a connection, if any. */
  static final ScopedValue<Instant> DEADLINE = ScopedValue.newInstance();

  private static final AddressPolicy ALWAYS = AddressPolicy.builder().build();

  private final RedisSettings settings;
  private final SSLContext ssl;

  GuardedSocketFactory(RedisSettings settings) {
    this.settings = settings;
    this.ssl = settings.tls()
        ? TrustStores.sslContext(settings.caCertificates().isEmpty() ? TrustStores.platform()
            : TrustStores.of(settings.caCertificates(), false))
        : null;
  }

  @Override
  public Socket createSocket() throws JedisConnectionException {
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(settings.host());
    } catch (UnknownHostException e) {
      throw new JedisConnectionException(e);
    }
    for (InetAddress a : addresses) {
      AddressPolicy.Verdict v = ALWAYS.check(settings.host(), a, settings.port());
      if (v.reason() == AddressPolicy.Reason.ALWAYS_BLOCKED) {
        throw new JedisConnectionException(new AddressRefusedException(v.text()));
      }
    }
    IOException last = null;
    for (InetAddress a : addresses) {
      Socket socket = new Socket(Proxy.NO_PROXY);
      try {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(a, settings.port()), connectTimeoutMs());
      } catch (IOException e) {
        closeQuietly(socket);
        last = e;
        continue;
      } catch (RuntimeException e) {
        closeQuietly(socket);
        throw e;
      }
      try {
        // Jedis takes the read timeout for the handshake (TLS, HELLO … AUTH) from the socket: cut to the call too.
        socket.setSoTimeout(readTimeoutMs());
        return ssl == null ? socket : tls(socket);
      } catch (IOException e) {
        closeQuietly(socket);
        throw new JedisConnectionException(e);
      } catch (RuntimeException e) {
        closeQuietly(socket);
        throw e;
      }
    }
    throw new JedisConnectionException(last == null ? new IOException("No address for " + settings.host()) : last);
  }

  private Socket tls(Socket plain) throws IOException {
    SSLSocket s = (SSLSocket) ssl.getSocketFactory().createSocket(plain, settings.host(), settings.port(), true);
    SSLParameters p = s.getSSLParameters();
    if (settings.verifyHost()) {
      p.setEndpointIdentificationAlgorithm("HTTPS");
    }
    if (AddressPolicy.canonicalIp(settings.host()).isEmpty()) {
      p.setServerNames(List.of(new SNIHostName(settings.host())));
    }
    s.setSSLParameters(p);
    try {
      s.startHandshake();
    } catch (java.net.SocketTimeoutException e) {
      // A plain Redis port reads a ClientHello as the start of a command and waits for its end, silently.
      throw new TlsHandshakeTimeoutException(e);
    }
    return s;
  }

  private int connectTimeoutMs() {
    return cutToDeadline(settings.connectTimeoutMs());
  }

  private int readTimeoutMs() {
    return cutToDeadline(settings.commandTimeoutMs());
  }

  private static int cutToDeadline(int ms) {
    Optional<Instant> deadline = DEADLINE.isBound() ? Optional.of(DEADLINE.get()) : Optional.empty();
    if (deadline.isPresent()) {
      long left = Duration.between(Instant.now(), deadline.get()).toMillis();
      return (int) Math.max(1, Math.min(ms, left));
    }
    return ms;
  }

  /**
   * One socket per Jedis connection. Jedis opens a closed connection again on its next command — without
   * {@code HELLO … AUTH} and {@code SELECT}, which only its constructor sends — so after a cancelled call cut the socket
   * a racing command would run as the server's default user on database 0. Here it fails instead.
   */
  static JedisSocketFactory oneSocket(JedisSocketFactory sockets) {
    AtomicBoolean opened = new AtomicBoolean();
    return () -> {
      if (!opened.compareAndSet(false, true)) {
        throw new JedisConnectionException("The connection to Redis was closed; it isn't reopened without signing in");
      }
      return sockets.createSocket();
    };
  }

  private static void closeQuietly(Socket s) {
    try {
      s.close();
    } catch (IOException ignored) {
      // Nothing to do: the socket never carried anything.
    }
  }

  /** The server took the TCP connection but never answered the TLS handshake. */
  static final class TlsHandshakeTimeoutException extends java.net.SocketTimeoutException {
    private static final long serialVersionUID = 1L;

    TlsHandshakeTimeoutException(java.net.SocketTimeoutException cause) {
      super("TLS handshake: " + cause.getMessage());
      initCause(cause);
    }
  }

  /** The host resolves to an address the gateway never connects to. */
  static final class AddressRefusedException extends IOException {
    private static final long serialVersionUID = 1L;

    AddressRefusedException(String message) {
      super(message);
    }
  }
}
