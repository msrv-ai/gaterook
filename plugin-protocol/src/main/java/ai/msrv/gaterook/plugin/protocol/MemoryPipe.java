package ai.msrv.gaterook.plugin.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * An in-memory pipe: what the output side writes, the input side reads, from any threads (unlike
 * {@code PipedInputStream}, which fails once the thread that last wrote has ended — every GPP/1 answer comes from a
 * thread of its own). Closing the output gives the reader EOF after the buffered bytes; closing the input makes
 * writes fail, as a dead process would. The host's AOT training run ({@code Main --train}) talks to itself through
 * two of these, the core's tests to the fake host. Bytes are zeroed once read.
 */
public final class MemoryPipe {

  private final Object lock = new Object();
  private byte[] buffer = new byte[64 * 1024];
  private int head;
  private int size;
  private boolean writerClosed;
  private boolean readerClosed;

  private final InputStream in = new InputStream() {
    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) {
        return 0;
      }
      synchronized (lock) {
        while (size == 0) {
          if (writerClosed || readerClosed) {
            return -1;
          }
          try {
            lock.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
          }
        }
        int n = Math.min(len, size);
        for (int i = 0; i < n; i++) {
          int at = (head + i) % buffer.length;
          b[off + i] = buffer[at];
          buffer[at] = 0;
        }
        head = (head + n) % buffer.length;
        size -= n;
        lock.notifyAll();
        return n;
      }
    }

    @Override
    public void close() {
      synchronized (lock) {
        readerClosed = true;
        lock.notifyAll();
      }
    }
  };

  private final OutputStream out = new OutputStream() {
    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      synchronized (lock) {
        if (writerClosed || readerClosed) {
          throw new IOException("Broken pipe");
        }
        if (size + len > buffer.length) {
          byte[] bigger = new byte[Math.max(buffer.length * 2, size + len)];
          for (int i = 0; i < size; i++) {
            bigger[i] = buffer[(head + i) % buffer.length];
          }
          java.util.Arrays.fill(buffer, (byte) 0);
          buffer = bigger;
          head = 0;
        }
        for (int i = 0; i < len; i++) {
          buffer[(head + size + i) % buffer.length] = b[off + i];
        }
        size += len;
        lock.notifyAll();
      }
    }

    @Override
    public void close() {
      synchronized (lock) {
        writerClosed = true;
        lock.notifyAll();
      }
    }
  };

  public InputStream in() {
    return in;
  }

  public OutputStream out() {
    return out;
  }
}
