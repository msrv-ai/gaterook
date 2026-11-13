package com.gaterook.connectors.sql;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Auto for PostgreSQL's TLS ({@code sslMode} {@code auto}): before any credential is sent, an SSLRequest asks the
 * server whether it speaks TLS, and its one-byte answer decides. {@code S}: it does, so the connection verifies the
 * certificate and the host name ({@code verify-full}); a certificate that fails is an error, never a reason to go
 * without TLS. {@code N}: it doesn't speak TLS at all, so the connection is unencrypted ({@code disable}). Anything
 * else (an error, a closed connection, other bytes, no answer in time) is an error: nothing goes in plain text on a
 * guess.
 *
 * <p>Only the 8 bytes of the request are sent, and the socket closes after the answer: no handshake, no startup
 * message, no user name. The server logs an incomplete startup packet for it.
 */
final class PostgresTls {

  /** What Auto chose for a server that speaks TLS: the certificate and the host name are verified. */
  static final String TLS = "verify-full";
  /** What Auto chose for a server that doesn't speak TLS. */
  static final String PLAIN = "disable";

  /** The SSLRequest: its length (8) and the code 80877103 (1234 in the high 16 bits, 5679 in the low). */
  private static final byte[] SSL_REQUEST = {0, 0, 0, 8, 0x04, (byte) 0xd2, 0x16, 0x2f};

  private PostgresTls() {}

  /**
   * Whether the server at {@code host:port} speaks TLS. The connection and the answer share {@code timeoutMs}.
   *
   * @throws SQLException SQLSTATE class 08, with the network failure as its cause where there is one (an unknown host,
   *     a refused connection, a timeout), as pgjdbc reports them
   */
  static boolean offered(String host, int port, int timeoutMs) throws SQLException {
    if (host == null || host.isBlank() || port < 1 || port > 65_535) {
      throw new SQLException("the connection names no host and port to ask", "08001");
    }
    String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(name, port), remaining(deadline));
      socket.setSoTimeout(remaining(deadline));
      socket.setTcpNoDelay(true);
      OutputStream out = socket.getOutputStream();
      out.write(SSL_REQUEST);
      out.flush();
      InputStream in = socket.getInputStream();
      int answer = in.read();
      return switch (answer) {
        case 'S' -> true;
        case 'N' -> false;
        case 'E' -> throw new SQLException(refusal(in), "08004");
        case -1 -> throw new SQLException("it closed the connection when asked whether it speaks TLS", "08006");
        default -> throw new SQLException("it doesn't speak PostgreSQL's protocol", "08P01");
      };
    } catch (SQLException e) {
      throw e;
    } catch (IOException e) {
      throw new SQLException("asking whether it speaks TLS failed: " + e.getMessage(), "08001", e);
    }
  }

  /**
   * The server's words when it answers the request with an error (a full server, a failed fork): the message of a
   * protocol 3 ErrorResponse, or the text of an older one, at most 300 characters.
   */
  private static String refusal(InputStream in) {
    byte[] bytes;
    try {
      bytes = in.readNBytes(1_024);
    } catch (IOException e) {
      bytes = new byte[0];
    }
    String message = null;
    // Protocol 3: a length, then fields of a type byte and a NUL-terminated string; 'M' is the message.
    for (int i = 4; i < bytes.length; ) {
      byte type = bytes[i++];
      int end = i;
      while (end < bytes.length && bytes[end] != 0) {
        end++;
      }
      if (type == 'M') {
        message = new String(bytes, i, end - i, StandardCharsets.UTF_8);
        break;
      }
      if (type == 0) {
        break;
      }
      i = end + 1;
    }
    if (message == null) {
      message = new String(bytes, StandardCharsets.UTF_8).replaceAll("\\p{Cntrl}", " ").strip();
    }
    message = message.length() > 300 ? message.substring(0, 300) + "…" : message;
    return "the server refused the connection" + (message.isBlank() ? "" : ": " + message);
  }

  private static int remaining(long deadline) throws SocketTimeoutException {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new SocketTimeoutException("timed out asking whether it speaks TLS");
    }
    return (int) Math.clamp(TimeUnit.NANOSECONDS.toMillis(nanos), 1, Integer.MAX_VALUE);
  }
}
