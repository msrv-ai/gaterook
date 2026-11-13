package com.gaterook.connectors.sql;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * The first bytes of a PostgreSQL server on 127.0.0.1, for Auto's tests: it answers an SSLRequest as configured ('S'
 * and TLS with {@link TestCertificate}, 'N', an error, other bytes, a closed connection or silence) and refuses every
 * startup message with an error of its choice, as a server refuses a wrong password. Each connection's events are
 * recorded in order, so a test sees whether the startup message (the user, and the password after it) ever went
 * unencrypted.
 *
 * <p>Events: {@code ssl-request}, {@code tls} (a handshake completed), {@code tls-failed}, {@code startup plain} or
 * {@code startup tls} with {@code user=… database=…}, {@code closed} (the client went before a startup message).
 */
final class FakePostgres implements AutoCloseable {

  /** How the server answers an SSLRequest. */
  enum Answer { TLS, PLAIN, ERROR, OTHER, CLOSE, SILENT }

  private static final int SSL_REQUEST = 80877103;
  private static final int PROTOCOL_3 = 196608;

  private final ServerSocket server;
  private final Answer answer;
  private final SSLContext tls;
  private volatile Function<Map<String, String>, String[]> refusal =
      startup -> new String[] {"28P01", "password authentication failed for user \"" + startup.get("user") + "\""};
  final List<List<String>> connections = new CopyOnWriteArrayList<>();

  FakePostgres(Answer answer) throws IOException {
    this.answer = answer;
    this.tls = answer == Answer.TLS ? TestCertificate.localhost().server : null;
    this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread.ofVirtual().name("fake-postgres").start(this::accept);
  }

  int port() {
    return server.getLocalPort();
  }

  /** The SQLSTATE and message each startup message gets, from its parameters ({@code user}, {@code database}). */
  FakePostgres refuse(Function<Map<String, String>, String[]> refusal) {
    this.refusal = refusal;
    return this;
  }

  /** Every event of every connection, in order. */
  List<String> events() {
    List<String> all = new ArrayList<>();
    connections.forEach(all::addAll);
    return all;
  }

  private void accept() {
    while (!server.isClosed()) {
      Socket socket;
      try {
        socket = server.accept();
      } catch (IOException e) {
        return;
      }
      List<String> events = new CopyOnWriteArrayList<>();
      connections.add(events);
      Thread.ofVirtual().name("fake-postgres-connection").start(() -> serve(socket, events));
    }
  }

  private void serve(Socket plain, List<String> events) {
    try (Socket socket = plain) {
      socket.setSoTimeout(10_000);
      DataInputStream in = new DataInputStream(socket.getInputStream());
      OutputStream out = socket.getOutputStream();
      int length = in.readInt();
      int code = in.readInt();
      if (code == SSL_REQUEST && length == 8) {
        events.add("ssl-request");
        switch (answer) {
          case SILENT -> {
            in.read();
            events.add("closed");
            return;
          }
          case CLOSE -> {
            return;
          }
          case OTHER -> {
            out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return;
          }
          case ERROR -> {
            out.write(error("53300", "sorry, too many clients already"));
            out.flush();
            return;
          }
          case PLAIN -> {
            out.write('N');
            out.flush();
            startup(in, out, events, "plain");
            return;
          }
          case TLS -> {
            out.write('S');
            out.flush();
            SSLSocket secured;
            try {
              secured = (SSLSocket) tls.getSocketFactory().createSocket(socket, null, socket.getPort(), false);
              secured.setUseClientMode(false);
              secured.startHandshake();
            } catch (IOException e) {
              events.add("tls-failed");
              return;
            }
            events.add("tls");
            try (SSLSocket s = secured) {
              startup(new DataInputStream(s.getInputStream()), s.getOutputStream(), events, "tls");
            }
            return;
          }
        }
      }
      // A startup message without an SSLRequest before it: the client chose no TLS itself.
      startup(in, out, events, "plain", length, code);
    } catch (IOException e) {
      // The client went away: what it sent is recorded.
    }
  }

  private void startup(DataInputStream in, OutputStream out, List<String> events, String transport)
      throws IOException {
    int length;
    try {
      length = in.readInt();
    } catch (EOFException e) {
      events.add("closed");
      return;
    } catch (IOException e) {
      // A TLS client that closed after its handshake (a host name it refused) ends the stream abruptly.
      events.add("closed");
      return;
    }
    startup(in, out, events, transport, length, in.readInt());
  }

  private void startup(DataInputStream in, OutputStream out, List<String> events, String transport, int length,
      int code) throws IOException {
    if (code != PROTOCOL_3 || length < 8 || length > 10_000) {
      events.add("unexpected " + code);
      return;
    }
    byte[] body = in.readNBytes(length - 8);
    Map<String, String> params = new LinkedHashMap<>();
    String[] parts = new String(body, StandardCharsets.UTF_8).split("\0");
    for (int i = 0; i + 1 < parts.length; i += 2) {
      params.put(parts[i], parts[i + 1]);
    }
    events.add("startup " + transport + " user=" + params.get("user") + " database=" + params.get("database"));
    String[] refused = refusal.apply(params);
    out.write(error(refused[0], refused[1]));
    out.flush();
  }

  /** A protocol 3 ErrorResponse: severity, SQLSTATE and message. */
  static byte[] error(String state, String message) {
    StringBuilder fields = new StringBuilder();
    fields.append('S').append("FATAL").append('\0');
    fields.append('V').append("FATAL").append('\0');
    fields.append('C').append(state).append('\0');
    fields.append('M').append(message).append('\0');
    fields.append('\0');
    byte[] f = fields.toString().getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[1 + 4 + f.length];
    out[0] = 'E';
    int length = 4 + f.length;
    out[1] = (byte) (length >>> 24);
    out[2] = (byte) (length >>> 16);
    out[3] = (byte) (length >>> 8);
    out[4] = (byte) length;
    System.arraycopy(f, 0, out, 5, f.length);
    return out;
  }

  @Override
  public void close() throws IOException {
    server.close();
  }
}
