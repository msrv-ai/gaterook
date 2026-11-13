package com.gaterook.connectors.logs;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * How an HTTP backend's TLS went, in words the person can act on: a certificate this computer doesn't trust (paste its
 * CA, never a reason to drop encryption), a certificate for another name, or a server without TLS.
 */
final class Tls {

  /**
   * What an address answered to a TLS hello: {@code OTHER} speaks TLS but refused the handshake for another reason (an
   * alert); {@code PLAIN} accepted the connection but answered no TLS.
   */
  enum Transport { VERIFIED, UNTRUSTED, WRONG_NAME, OTHER, PLAIN, UNREACHABLE }

  private Tls() {}

  /**
   * One TLS handshake with {@code host:port} at the first of {@code addresses} that accepts a connection: a TLS hello
   * and nothing else, no request and no credential. For the setup helpers only, which never send a credential on the
   * strength of it: a server that accepts the connection but gives no TLS answer counts as {@code PLAIN}.
   *
   * @param ssl the trust of the connection (its pasted CA), or {@code null} for the CAs this computer trusts
   */
  static Transport probe(String host, List<InetAddress> addresses, int port, SSLContext ssl, Duration timeout) {
    int millis = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
    for (InetAddress address : addresses) {
      try (Socket transport = new Socket()) {
        try {
          transport.connect(new InetSocketAddress(address, port), millis);
        } catch (IOException | IllegalArgumentException e) {
          continue;
        }
        transport.setSoTimeout(millis);
        SSLSocketFactory factory = ssl == null ? (SSLSocketFactory) SSLSocketFactory.getDefault()
            : ssl.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(transport, host, port, true)) {
          SSLParameters parameters = socket.getSSLParameters();
          parameters.setEndpointIdentificationAlgorithm("HTTPS");
          socket.setSSLParameters(parameters);
          socket.startHandshake();
          return Transport.VERIFIED;
        } catch (IOException e) {
          if (wrongName(e)) {
            return Transport.WRONG_NAME;
          }
          if (untrusted(e)) {
            return Transport.UNTRUSTED;
          }
          return alert(e) ? Transport.OTHER : Transport.PLAIN;
        }
      } catch (IOException e) {
        // Closing failed: the next address, if any.
      }
    }
    return Transport.UNREACHABLE;
  }

  /** The handshake reached the certificate check and the chain isn't trusted: the server speaks TLS. */
  static boolean untrusted(Throwable error) {
    for (Throwable cause : chain(error)) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (cause instanceof CertificateException || message.contains("pkix") || message.contains("certification path")
          || message.contains("certificate")) {
        return true;
      }
    }
    return false;
  }

  /** The chain is trusted (or not checked yet) but the certificate names another host. */
  static boolean wrongName(Throwable error) {
    for (Throwable cause : chain(error)) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains("no subject alternative") || message.contains("no name matching")) {
        return true;
      }
    }
    return false;
  }

  /** The server answered the hello with a TLS alert: it speaks TLS. */
  private static boolean alert(Throwable error) {
    for (Throwable cause : chain(error)) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains("fatal alert") || message.contains("protocol_version")
          || message.contains("handshake_failure")) {
        return true;
      }
    }
    return false;
  }

  /**
   * What to do about a certificate of {@code address} this computer doesn't trust.
   *
   * @param backend  {@code loki}, {@code opensearch} or {@code elasticsearch}: where such a server keeps its CA
   * @param caPasted whether the connection already names a CA certificate (then it is the wrong one)
   */
  static String untrustedNote(String address, String backend, boolean caPasted) {
    if (caPasted) {
      return "The pasted CA certificate doesn't verify the certificate of " + address + ": paste the CA that signed "
          + "it under Advanced settings.";
    }
    return address + " uses a certificate this computer doesn't trust: paste the CA certificate (PEM) that signed it "
        + "under Advanced settings." + where(backend);
  }

  /** Where a server of {@code backend} keeps the CA of its own certificates, as a sentence; empty when unknown. */
  static String where(String backend) {
    return switch (backend == null ? "" : backend) {
      case "elasticsearch" -> " Elasticsearch writes its own to config/certs/http_ca.crt.";
      case "opensearch" -> " OpenSearch's demo setup keeps it in config/root-ca.pem.";
      default -> "";
    };
  }

  /** What to do about a certificate of {@code address} that names another host. */
  static String wrongNameNote(String address, String host) {
    return "The certificate of " + address + " isn't issued for " + host + ": use the host name it was issued for in "
        + "the URL.";
  }

  /**
   * A failed handshake in words: a certificate for another name, one this computer doesn't trust, or what TLS said.
   * What to do about it depends on the connection (a CA field or none), so callers add that.
   */
  static String explain(String address, SSLException e) {
    if (wrongName(e)) {
      return "The certificate of " + address + " is issued for another host name.";
    }
    if (untrusted(e)) {
      return "The certificate of " + address + " isn't trusted on this computer: it is self-signed or from a private "
          + "CA.";
    }
    String m = e.getMessage();
    return "The TLS handshake with " + address + " failed: " + (m == null || m.isBlank() ? e.getClass().getSimpleName()
        : m) + ".";
  }

  private static List<Throwable> chain(Throwable error) {
    List<Throwable> out = new ArrayList<>();
    for (Throwable cause = error; cause != null && out.size() < 16; cause = cause.getCause() == cause ? null
        : cause.getCause()) {
      out.add(cause);
    }
    return out;
  }
}
