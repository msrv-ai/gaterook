package ai.msrv.gaterook.connectors.kubernetes;

import java.io.IOException;
import java.io.InputStream;

/**
 * The tail of a log stream in a ring buffer: the newest {@code max} bytes, however long the stream (report 08 §3.2).
 * The API server's {@code limitBytes} keeps the <em>start</em> of the output, which would lose the newest lines, so
 * it is only an upper bound for the transfer and the cut happens here, at the head.
 */
final class LogTail {

  /** What was read: the kept bytes, how many arrived in all, and whether reading stopped early. */
  record Tail(byte[] bytes, long total, boolean headCut, boolean stopped) {}

  private LogTail() {}

  /**
   * Reads until the end of the stream, or until {@code stopped} says the stream was closed under it (deadline,
   * cancellation): what arrived so far is the answer.
   */
  static Tail read(InputStream in, int max, java.util.function.BooleanSupplier stopped) throws IOException {
    byte[] ring = new byte[max];
    int start = 0;
    int size = 0;
    long total = 0;
    byte[] buf = new byte[16 * 1024];
    while (true) {
      int n;
      try {
        n = in.read(buf);
      } catch (IOException e) {
        if (stopped.getAsBoolean()) {
          break;
        }
        throw e;
      }
      if (n < 0) {
        break;
      }
      total += n;
      for (int i = 0; i < n; i++) {
        if (size < max) {
          ring[(start + size) % max] = buf[i];
          size++;
        } else {
          ring[start] = buf[i];
          start = (start + 1) % max;
        }
      }
    }
    byte[] out = new byte[size];
    for (int i = 0; i < size; i++) {
      out[i] = ring[(start + i) % max];
    }
    boolean cut = total > size;
    if (cut) {
      // Start at a line: a half line at the top would read as a different message.
      int nl = indexOf(out, (byte) '\n');
      if (nl >= 0 && nl + 1 < out.length) {
        byte[] trimmed = new byte[out.length - nl - 1];
        System.arraycopy(out, nl + 1, trimmed, 0, trimmed.length);
        out = trimmed;
      }
    }
    return new Tail(out, total, cut, stopped.getAsBoolean());
  }

  private static int indexOf(byte[] b, byte v) {
    for (int i = 0; i < b.length; i++) {
      if (b[i] == v) {
        return i;
      }
    }
    return -1;
  }
}
