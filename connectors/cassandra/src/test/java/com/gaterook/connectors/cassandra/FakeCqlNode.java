package com.gaterook.connectors.cassandra;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * A stand-in for the native port of one node, enough for Connection type Auto: plain, it answers CQL's
 * {@code OPTIONS} with {@code SUPPORTED} and a TLS ClientHello, which it reads as a frame of protocol version 22, with
 * Cassandra's protocol error, then closes; with TLS (a certificate for {@code localhost} and {@code 127.0.0.1} from
 * {@code okhttp-tls}) it completes the handshake. A silent node and a web server stand for what is neither.
 */
final class FakeCqlNode implements AutoCloseable {

  enum Mode { PLAIN, TLS, SILENT, HTTP }

  final HeldCertificate certificate;
  final int port;
  final AtomicInteger connections = new AtomicInteger();
  /** {@code OPTIONS} frames that arrived over plain TCP. */
  final AtomicInteger options = new AtomicInteger();
  private final ServerSocket server;
  private final Mode mode;

  FakeCqlNode(Mode mode) throws IOException {
    this.mode = mode;
    this.certificate = new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1").build();
    if (mode == Mode.TLS) {
      SSLContext context = new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslContext();
      server = context.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
    } else {
      server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }
    port = server.getLocalPort();
    Thread.ofVirtual().name("fake-cql-" + port).start(this::accept);
  }

  String caPem() {
    return certificate.certificatePem();
  }

  String contactPoint(String host) {
    return host + ":" + port;
  }

  @Override
  public void close() throws IOException {
    server.close();
  }

  private void accept() {
    while (!server.isClosed()) {
      try {
        Socket s = server.accept();
        connections.incrementAndGet();
        Thread.ofVirtual().start(() -> serve(s));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket socket) {
    try (socket) {
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      byte[] header = in.readNBytes(9);
      if (header.length < 9) {
        return;
      }
      switch (mode) {
        case SILENT -> in.readAllBytes();
        case HTTP -> out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        case TLS -> in.readAllBytes();
        case PLAIN -> {
          if (header[0] == 0x04 && header[4] == 0x05) {
            options.incrementAndGet();
            // SUPPORTED with an empty string multimap.
            out.write(new byte[] {(byte) 0x84, 0, 0, 0, 0x06, 0, 0, 0, 2, 0, 0});
          } else {
            byte[] text = ("Invalid or unsupported protocol version (" + (header[0] & 0x7F) + "); supported versions "
                + "are (3/v3, 4/v4, 5/v5, 6/v6-beta)").getBytes(StandardCharsets.UTF_8);
            out.write(error(text));
          }
          out.flush();
        }
        default -> throw new IllegalStateException();
      }
    } catch (IOException e) {
      // The client went away, or the TLS port met a client that doesn't speak TLS.
    }
  }

  /** An ERROR frame (protocol error 0x000A) in protocol v4. */
  private static byte[] error(byte[] text) {
    int length = 4 + 2 + text.length;
    byte[] frame = new byte[9 + length];
    frame[0] = (byte) 0x84;
    frame[4] = 0x00;
    frame[5] = (byte) (length >>> 24);
    frame[6] = (byte) (length >>> 16);
    frame[7] = (byte) (length >>> 8);
    frame[8] = (byte) length;
    frame[12] = 0x0A;
    frame[13] = (byte) (text.length >>> 8);
    frame[14] = (byte) text.length;
    System.arraycopy(text, 0, frame, 15, text.length);
    return frame;
  }
}
