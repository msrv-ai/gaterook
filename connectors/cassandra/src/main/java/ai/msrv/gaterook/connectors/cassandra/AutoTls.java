package ai.msrv.gaterook.connectors.cassandra;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.connectors.support.TrustStores;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Connection type Auto: whether the nodes speak TLS, found on the first contact point that answers before a session
 * signs in — once per session of a saved connection ({@link CassandraSessions}) and on every Test connection.
 * Explicit choices never enter here.
 *
 * <p>A TLS handshake comes first, with the connection's trust and name check, as the driver's engines do it. A node
 * that completes it gets TLS (a node of {@code client_encryption_options.optional} speaks both and gets TLS). A
 * certificate, a name, an alert or a timeout is the answer: never a reason to drop encryption. Only a node that closes
 * the connection at once or answers bytes that aren't TLS — Cassandra reads a ClientHello as a frame of an unknown
 * protocol version and answers an error or closes — is asked {@code OPTIONS} without credentials on a connection of its
 * own, and only a CQL answer to it ({@code SUPPORTED}, or an {@code ERROR} frame) gets a session without TLS. With a CA
 * certificate in the form the nodes must speak TLS. As with MongoDB's Auto, someone who controls the network path can
 * still make a TLS node look plain; prod connections default to TLS for that reason.
 */
final class AutoTls {

  /** {@code OPTIONS} in protocol v4: version 4, no flags, stream 0, opcode 5, no body. */
  private static final byte[] OPTIONS = {0x04, 0, 0, 0, 0x05, 0, 0, 0, 0};
  private static final int SUPPORTED = 0x06;
  private static final int ERROR = 0x00;

  private AutoTls() {}

  /**
   * The transport Auto finds, as settings without Auto; settings without Auto as they are. Contact points are tried
   * in order within {@code budget}, each with its share of what is left; the first that answers decides.
   */
  static CassandraSettings resolve(CassandraSettings s, List<InetSocketAddress> points, Duration budget)
      throws ConnectorException {
    if (!s.autoTls()) {
      return s;
    }
    long deadline = System.nanoTime() + budget.toNanos();
    String unreachable = null;
    for (int i = 0; i < points.size(); i++) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        break;
      }
      InetSocketAddress point = points.get(i);
      // A dead contact point must not take the time of the ones after it.
      long pointDeadline = System.nanoTime() + remaining / (points.size() - i);
      Socket transport = new Socket(Proxy.NO_PROXY);
      try {
        transport.connect(point, millis(pointDeadline));
      } catch (IOException e) {
        closeQuietly(transport);
        unreachable = shown(point) + ": " + reason(e);
        continue;
      }
      try {
        return s.withTls(detect(s, point, transport, pointDeadline));
      } catch (UndetectedException e) {
        throw ConnectorException.unavailable("Auto: " + shown(point) + " answered neither a TLS handshake nor CQL "
            + "without TLS. Check the contact points.", e);
      } catch (SocketTimeoutException e) {
        throw ConnectorException.timeout("Auto: " + shown(point) + " didn't finish a TLS handshake in time. If the "
            + "nodes don't speak TLS, choose Without TLS under Connection type.");
      } catch (IOException e) {
        throw ConnectorException.unavailable("TLS to " + shown(point) + " failed: " + OutputSanitizer.sanitize(
            CassandraErrors.cut(CassandraErrors.firstLine(String.valueOf(e.getMessage())), 300)) + ". Check the CA "
            + "certificate and the host name.", e);
      } finally {
        closeQuietly(transport);
      }
    }
    throw ConnectorException.unavailable(unreachable == null ? "Auto: no time was left to find out whether the nodes "
        + "speak TLS." : "No contact point answered (" + unreachable + "). Check the contact points and the "
        + "network.", null);
  }

  /** Whether {@code point} speaks TLS: the handshake on {@code transport}, else OPTIONS on a connection of its own. */
  private static boolean detect(CassandraSettings s, InetSocketAddress point, Socket transport, long deadline)
      throws IOException {
    try {
      handshake(s, point, transport, deadline);
      return true;
    } catch (IOException e) {
      if (!s.caCertificates().isEmpty() || !mayBePlaintext(e)) {
        throw e;
      }
      if (answersOptions(point, deadline)) {
        return false;
      }
      throw new UndetectedException(e);
    }
  }

  private static void handshake(CassandraSettings s, InetSocketAddress point, Socket transport, long deadline)
      throws IOException {
    SSLContext context = TrustStores.sslContext(s.caCertificates().isEmpty() ? TrustStores.platform()
        : TrustStores.of(s.caCertificates(), false));
    String host = s.tlsServerName() != null ? s.tlsServerName() : point.getHostString();
    transport.setSoTimeout(millis(deadline));
    try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(transport, host, point.getPort(),
        true)) {
      SSLParameters p = socket.getSSLParameters();
      if (s.verifyHostname()) {
        p.setEndpointIdentificationAlgorithm("HTTPS");
      }
      if (s.tlsServerName() != null) {
        p.setServerNames(List.of(new SNIHostName(s.tlsServerName())));
      }
      socket.setSSLParameters(p);
      socket.startHandshake();
    }
  }

  /** {@code OPTIONS} without credentials over plain TCP: whether a CQL response frame answers it. */
  static boolean answersOptions(InetSocketAddress point, long deadline) {
    try (Socket socket = new Socket(Proxy.NO_PROXY)) {
      socket.connect(point, millis(deadline));
      socket.setSoTimeout(millis(deadline));
      OutputStream out = socket.getOutputStream();
      out.write(OPTIONS);
      out.flush();
      InputStream in = socket.getInputStream();
      byte[] header = in.readNBytes(9);
      if (header.length < 9) {
        return false;
      }
      int version = header[0] & 0xFF;
      int opcode = header[4] & 0xFF;
      long length = ((header[5] & 0xFFL) << 24) | ((header[6] & 0xFFL) << 16) | ((header[7] & 0xFFL) << 8)
          | (header[8] & 0xFFL);
      // A response (high bit) of a protocol version Cassandra has used, SUPPORTED or ERROR, of a sane length.
      return (version & 0x80) != 0 && (version & 0x7F) >= 1 && (version & 0x7F) <= 6
          && (opcode == SUPPORTED || opcode == ERROR) && length <= 1 << 20;
    } catch (IOException e) {
      return false;
    }
  }

  /** Restrict fallback to transport closure or an actual non-TLS response, never certificates, alerts or timeouts. */
  static boolean mayBePlaintext(Throwable error) {
    boolean eligible = false;
    for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (cause instanceof CertificateException || cause instanceof SocketTimeoutException
          || message.contains("certificate") || message.contains("pkix") || message.contains("fatal alert")
          || message.contains("protocol_version") || message.contains("handshake_failure")) {
        return false;
      }
      eligible |= cause instanceof EOFException
          || cause instanceof SocketException && message.contains("connection reset")
          || cause instanceof SSLException && (message.contains("unrecognized ssl message")
              || message.contains("ssl peer shut down incorrectly"));
    }
    return eligible;
  }

  private static int millis(long deadline) throws SocketTimeoutException {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new SocketTimeoutException("Detecting the Cassandra connection type timed out");
    }
    return (int) Math.clamp(TimeUnit.NANOSECONDS.toMillis(nanos), 1, Integer.MAX_VALUE);
  }

  private static String shown(InetSocketAddress point) {
    String host = point.getHostString();
    return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + point.getPort();
  }

  private static String reason(IOException e) {
    if (e instanceof java.net.ConnectException) {
      return "connection refused";
    }
    if (e instanceof SocketTimeoutException) {
      return "no answer";
    }
    return OutputSanitizer.sanitize(CassandraErrors.cut(CassandraErrors.firstLine(String.valueOf(e.getMessage())),
        120));
  }

  private static void closeQuietly(Socket s) {
    try {
      s.close();
    } catch (IOException ignored) {
      // Nothing to do: the probe is over.
    }
  }

  /** The node answered neither a TLS handshake nor CQL without TLS. */
  static final class UndetectedException extends IOException {
    private static final long serialVersionUID = 1L;

    UndetectedException(Throwable cause) {
      super("Neither TLS nor CQL", cause);
    }
  }
}
