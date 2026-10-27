package ai.msrv.gaterook.plugin.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * NDJSON framing at the byte level (report 16 §4.2): one frame per {@code \n}, read into a reusable buffer that the
 * caller zeroes after use. Bytes are never decoded into a {@code String} here, so a secret travelling in a frame
 * exists only in buffers we wipe (and in whatever the JSON parser copies out of it on purpose).
 *
 * <p>A frame longer than the limit is a protocol violation: {@link #next()} throws {@link FrameTooLarge}, and the peer
 * that sees it stops talking to the other side. A trailing {@code \r} is dropped, empty lines are skipped.
 */
public final class FrameReader {

  /** A frame over the limit: the other side broke the protocol. */
  public static final class FrameTooLarge extends IOException {
    private static final long serialVersionUID = 1L;

    FrameTooLarge(int limit) {
      super("A frame is larger than " + limit + " bytes");
    }
  }

  private static final int CHUNK = 64 * 1024;

  private final InputStream in;
  private final int maxFrameBytes;
  private final byte[] chunk = new byte[CHUNK];
  private int chunkPos;
  private int chunkEnd;
  private byte[] frame = new byte[4096];

  public FrameReader(InputStream in, int maxFrameBytes) {
    this.in = in;
    this.maxFrameBytes = maxFrameBytes;
  }

  /**
   * Reads the next frame into {@link #buffer()}.
   *
   * @return its length in bytes, or {@code -1} at the end of the stream (a partial last line is dropped: the writer
   *     died in the middle of it)
   * @throws FrameTooLarge when a frame exceeds the limit
   */
  public int next() throws IOException {
    while (true) {
      int length = 0;
      while (true) {
        if (chunkPos == chunkEnd) {
          int n = in.read(chunk, 0, CHUNK);
          if (n < 0) {
            wipe(length);
            return -1;
          }
          chunkPos = 0;
          chunkEnd = n;
          continue;
        }
        int newline = -1;
        for (int i = chunkPos; i < chunkEnd; i++) {
          if (chunk[i] == '\n') {
            newline = i;
            break;
          }
        }
        int end = newline < 0 ? chunkEnd : newline;
        int part = end - chunkPos;
        if ((long) length + part > maxFrameBytes) {
          wipe(length);
          Arrays.fill(chunk, (byte) 0);
          chunkPos = chunkEnd = 0;
          throw new FrameTooLarge(maxFrameBytes);
        }
        ensure(length + part);
        System.arraycopy(chunk, chunkPos, frame, length, part);
        // The chunk held frame bytes too: wipe what was copied out of it.
        Arrays.fill(chunk, chunkPos, end, (byte) 0);
        length += part;
        chunkPos = newline < 0 ? chunkEnd : newline + 1;
        if (newline >= 0) {
          break;
        }
      }
      if (length > 0 && frame[length - 1] == '\r') {
        frame[--length] = 0;
      }
      if (length > 0) {
        return length;
      }
    }
  }

  /** The bytes of the last frame (valid up to the length {@link #next()} returned, until the next call). */
  public byte[] buffer() {
    return frame;
  }

  /** Zeroes the first {@code length} bytes of the buffer. */
  public void wipe(int length) {
    Arrays.fill(frame, 0, Math.min(Math.max(length, 0), frame.length), (byte) 0);
  }

  private void ensure(int capacity) {
    if (capacity <= frame.length) {
      return;
    }
    int size = Math.min(Math.max(capacity, frame.length * 2), maxFrameBytes);
    byte[] bigger = Arrays.copyOf(frame, size);
    Arrays.fill(frame, (byte) 0);
    frame = bigger;
  }
}
