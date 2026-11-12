package ai.msrv.gaterook.connectors.neo4j;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Locale;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * What a Neo4j port of this computer speaks, for the form's local suggestions: a TLS handshake with the system's trust
 * and a host name check, then, only when that met a server that doesn't speak TLS (closed at once, or bytes that aren't
 * TLS), Bolt's handshake on a connection of its own: its magic number and four version ranges, answered by the version
 * the server picked. Nothing that signs in is sent, and only loopback addresses are tried.
 */
final class LoopbackBolt {

  /** TLS verified, TLS with a certificate this computer doesn't trust, Bolt without TLS, or nothing that answers. */
  enum Transport { TLS, UNTRUSTED, PLAIN, NONE }

  /** Bolt's magic number, then 5.0 to 5.8, 4.0 to 4.4, 3.0 and nothing. */
  private static final byte[] HELLO = {0x60, 0x60, (byte) 0xB0, 0x17, 0, 8, 8, 5, 0, 4, 4, 4, 0, 0, 0, 3, 0, 0, 0, 0};

  private LoopbackBolt() {}

  static Transport probe(String host, int port, Duration timeout) {
    int millis = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
    if (!Neo4jSettings.loopback(host)) {
      return Transport.NONE;
    }
    try (Socket transport = connect(host, port, millis)) {
      if (transport == null) {
        return Transport.NONE;
      }
      transport.setSoTimeout(millis);
      try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(transport,
          host, port, true)) {
        SSLParameters p = socket.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(p);
        socket.startHandshake();
        return Transport.TLS;
      } catch (IOException e) {
        if (untrusted(e)) {
          return Transport.UNTRUSTED;
        }
        if (!mayBePlaintext(e)) {
          return Transport.NONE;
        }
      }
    } catch (IOException e) {
      return Transport.NONE;
    }
    try (Socket plain = connect(host, port, millis)) {
      if (plain == null) {
        return Transport.NONE;
      }
      plain.setSoTimeout(millis);
      OutputStream out = plain.getOutputStream();
      out.write(HELLO);
      out.flush();
      InputStream in = plain.getInputStream();
      return in.readNBytes(4).length == 4 ? Transport.PLAIN : Transport.NONE;
    } catch (IOException e) {
      return Transport.NONE;
    }
  }

  /** A TCP connection to the first loopback address of {@code host} that takes one, or {@code null}. */
  private static Socket connect(String host, int port, int millis) {
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (IOException e) {
      return null;
    }
    for (InetAddress a : addresses) {
      if (!a.isLoopbackAddress()) {
        continue;
      }
      Socket s = new Socket(Proxy.NO_PROXY);
      try {
        s.connect(new InetSocketAddress(a, port), millis);
        return s;
      } catch (IOException e) {
        try {
          s.close();
        } catch (IOException ignored) {
          // Closed either way.
        }
      }
    }
    return null;
  }

  /** The handshake reached the certificate check and failed it: the server speaks TLS. */
  static boolean untrusted(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (cause instanceof CertificateException || message.contains("pkix") || message.contains("certificate")
          || message.contains("no subject alternative") || message.contains("no name matching")) {
        return true;
      }
    }
    return false;
  }

  /** A closed transport or a non-TLS answer, never an alert or a timeout (as the other connectors' Auto). */
  static boolean mayBePlaintext(Throwable error) {
    boolean eligible = false;
    for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (cause instanceof SocketTimeoutException || message.contains("fatal alert")
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
}
