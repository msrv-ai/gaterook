package com.gaterook.connectors.redis;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Connection type Auto: whether the server speaks TLS, found before anything that signs in is sent — once per version
 * of a connection ({@link RedisClients}) and on every Test connection. Explicit choices never enter here.
 *
 * <ol>
 *   <li>A TLS handshake with the connection's trust and name check. A server that completes it gets TLS. A certificate,
 *       a name or an alert that fails it is the answer too: never a reason to drop encryption.</li>
 *   <li>Only a handshake that met a server not speaking TLS — the connection closed at once, bytes that aren't TLS, or
 *       not one byte back within a short wait — leads to {@code PING} without credentials on a plain connection of its
 *       own. {@code +PONG} or a RESP error line ({@code -NOAUTH}, {@code -DENIED}) is a plain Redis, which then gets a
 *       connection without TLS. Silence counts because a plain Redis port reads a ClientHello as the start of an inline
 *       command and waits for a line end its parser never finds (it stops at the hello's first NUL byte).</li>
 *   <li>A server that stayed silent but doesn't answer the PING as Redis is taken for a slow TLS server: the handshake
 *       is tried again with the time that is left.</li>
 * </ol>
 * With a CA certificate in the form the server must speak TLS, and nothing is sent in plain text. As with MongoDB's
 * Auto, someone who controls the network path can still make a TLS server look plain; prod connections default to
 * TLS for that reason.
 */
final class AutoTls {

  /** The shortest wait for a TLS server's first bytes; eight times the TCP connect when that is longer. */
  static final Duration SILENCE = Duration.ofMillis(300);
  /** The longest PING answer read: one RESP line. */
  static final int MAX_LINE = 512;
  private static final byte[] PING = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
  /** {@code +PONG}, or a RESP error: an upper-case code ({@code NOAUTH}, {@code DENIED}, {@code ERR}) and its text. */
  private static final Pattern RESP = Pattern.compile("\\+PONG|-[A-Z]{2,32}(?: [^\\r\\n]*)?");

  private AutoTls() {}

  /**
   * The transport Auto finds, as settings without Auto; settings without Auto as they are. Bounded by
   * {@code deadline} and the connection's connect and command timeouts together.
   */
  static RedisSettings resolve(RedisSettings s, Instant deadline) throws JedisConnectionException {
    if (!s.autoTls()) {
      return s;
    }
    Instant budget = Instant.now().plusMillis((long) s.connectTimeoutMs() + s.commandTimeoutMs());
    Instant end = deadline == null || budget.isBefore(deadline) ? budget : deadline;
    try {
      return ScopedValue.where(GuardedSocketFactory.DEADLINE, end).call(() -> detect(s, end));
    } catch (JedisConnectionException e) {
      throw e;
    } catch (IOException e) {
      throw new JedisConnectionException(e);
    }
  }

  private static RedisSettings detect(RedisSettings s, Instant end) throws IOException {
    GuardedSocketFactory tls = new GuardedSocketFactory(s.withTls(true));
    long started = System.nanoTime();
    Watched watched = new Watched();
    Socket transport = tls.connect(() -> watched);
    Duration window = Duration.ofNanos(Math.max(SILENCE.toNanos(), 8 * (System.nanoTime() - started)));
    IOException failure;
    try {
      transport.setSoTimeout(millis(min(window, left(end))));
      tls.tls(transport).close();
      return s.withTls(true);
    } catch (IOException e) {
      failure = e;
    } finally {
      GuardedSocketFactory.closeQuietly(transport);
    }
    boolean silent = failure instanceof SocketTimeoutException && watched.received() == 0;
    boolean plain = s.caCertificates().isEmpty() && (silent || mayBePlaintext(failure));
    if (!plain && !silent) {
      throw failure;
    }
    if (plain && (watched.sentRespError() || answersPing(s, end))) {
      return s.withTls(false);
    }
    if (!silent) {
      throw new UndetectedException(s.hostPort() + " answered neither a TLS handshake nor PING without TLS. Check "
          + "the host and port.", failure);
    }
    // Silent, and not a plain Redis: a slow TLS server gets the rest of the time.
    Socket again = tls.connect(() -> new Socket(Proxy.NO_PROXY));
    try {
      again.setSoTimeout(millis(left(end)));
      tls.tls(again).close();
      return s.withTls(true);
    } catch (SocketTimeoutException e) {
      throw new UndetectedException(s.hostPort() + " answered neither a TLS handshake nor PING without TLS in time. "
          + "Check the host and port.", e);
    } finally {
      GuardedSocketFactory.closeQuietly(again);
    }
  }

  /** {@code PING} without credentials on a plain connection of its own: whether a RESP line answers it. */
  static boolean answersPing(RedisSettings s, Instant end) {
    GuardedSocketFactory plain = new GuardedSocketFactory(s.withTls(false));
    Socket socket = null;
    try {
      socket = plain.connect(() -> new Socket(Proxy.NO_PROXY));
      socket.setSoTimeout(millis(left(end)));
      OutputStream out = socket.getOutputStream();
      out.write(PING);
      out.flush();
      String line = line(socket.getInputStream());
      return line != null && RESP.matcher(line).matches();
    } catch (IOException | JedisConnectionException e) {
      return false;
    } finally {
      if (socket != null) {
        GuardedSocketFactory.closeQuietly(socket);
      }
    }
  }

  /** One line up to CR LF, at most {@link #MAX_LINE} bytes; {@code null} when the server stops before its end. */
  static String line(InputStream in) throws IOException {
    byte[] buf = new byte[MAX_LINE];
    int n = 0;
    while (n < buf.length) {
      int b = in.read();
      if (b < 0) {
        return null;
      }
      if (b == '\n' && n > 0 && buf[n - 1] == '\r') {
        return new String(buf, 0, n - 1, StandardCharsets.ISO_8859_1);
      }
      buf[n++] = (byte) b;
    }
    return null;
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

  private static Duration left(Instant end) throws SocketTimeoutException {
    Duration left = Duration.between(Instant.now(), end);
    if (left.isNegative() || left.isZero()) {
      throw new SocketTimeoutException("Detecting the Redis connection type timed out");
    }
    return left;
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  private static int millis(Duration d) {
    return (int) Math.clamp(d.toMillis(), 1, Integer.MAX_VALUE);
  }

  /** Neither transport answered as Redis. */
  static final class UndetectedException extends IOException {
    private static final long serialVersionUID = 1L;

    UndetectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The TCP socket of the TLS attempt: it counts what the server sent and keeps the first bytes, so silence tells
   * from a stalled handshake and a RESP error line (a plain Redis that refuses every client, such as {@code -DENIED}
   * in protected mode) from other answers.
   */
  static final class Watched extends Socket {
    private final byte[] head = new byte[8];
    private int kept;
    private long received;
    private InputStream in;

    Watched() {
      super(Proxy.NO_PROXY);
    }

    @Override
    public synchronized InputStream getInputStream() throws IOException {
      if (in == null) {
        in = new FilterInputStream(super.getInputStream()) {
          @Override
          public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
              seen(new byte[] {(byte) b}, 0, 1);
            }
            return b;
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
              seen(b, off, n);
            }
            return n;
          }
        };
      }
      return in;
    }

    private synchronized void seen(byte[] b, int off, int n) {
      received += n;
      for (int i = 0; i < n && kept < head.length; i++) {
        head[kept++] = b[off + i];
      }
    }

    synchronized long received() {
      return received;
    }

    /** Whether the server's first bytes were a RESP error: {@code -}, then an upper-case code. */
    synchronized boolean sentRespError() {
      if (kept < 5 || head[0] != '-') {
        return false;
      }
      for (int i = 1; i < kept; i++) {
        byte c = head[i];
        if (c == ' ' || c == '\r') {
          return i >= 3;
        }
        if (c < 'A' || c > 'Z') {
          return false;
        }
      }
      return true;
    }
  }
}
