package com.gaterook.connectors.redis;

import java.util.ArrayList;
import java.util.List;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisSocketFactory;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.PushConsumerChain;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.RedisInputStream;

/**
 * A Jedis connection whose replies can keep only the head of each long bulk string. Jedis allocates a bulk string
 * whole as soon as it reads its length, so a page of large list elements would take their full size from the core's
 * heap even though the answer shows a few kilobytes of each. While {@link #keep} is set, a longer bulk string is read
 * off the socket in small pieces and only its head is kept, as a {@link Cut} that knows the whole length. The reply
 * stays aligned, so the connection stays usable; error replies are read by Jedis itself.
 */
final class BoundedConnection extends Connection {

  /** The first bytes of a bulk string of {@code length} bytes. */
  record Cut(byte[] head, long length) {}

  private static final int SKIP_CHUNK = 8 * 1024;

  /**
   * Bytes kept of each bulk string; 0 or less: whole, read by Jedis. No initializer: Jedis' constructor already reads
   * the handshake's replies, before this class' fields are set. One session uses a connection at a time.
   */
  private int keep;

  BoundedConnection(JedisSocketFactory sockets, JedisClientConfig config) {
    super(sockets, config);
  }

  /** Bulk strings of the next replies keep at most {@code bytes} bytes; 0 or less: whole, as Jedis reads them. */
  void keep(int bytes) {
    keep = bytes;
  }

  @Override
  protected Object protocolRead(RedisInputStream is, PushConsumerChain consumer) {
    int k = keep;
    return k <= 0 || is.peek(Protocol.MINUS_BYTE) ? super.protocolRead(is, consumer) : read(is, k);
  }

  /**
   * A RESP2 reply as {@link Protocol#read} returns it, except that a bulk string longer than {@code keep} bytes is a
   * {@link Cut}. The connection asks for RESP2, so other types are a broken stream.
   */
  static Object read(RedisInputStream is, int keep) {
    byte b = is.readByte();
    return switch (b) {
      case Protocol.PLUS_BYTE -> is.readLineBytes();
      case Protocol.COLON_BYTE -> is.readLongCrLf();
      case Protocol.DOLLAR_BYTE -> bulk(is, keep);
      case Protocol.ASTERISK_BYTE -> array(is, keep);
      default -> throw new JedisConnectionException("Unknown reply: " + (char) b);
    };
  }

  private static List<Object> array(RedisInputStream is, int keep) {
    int n = is.readIntCrLf();
    if (n < 0) {
      return null;
    }
    // The count is the server's: room grows with what actually arrives.
    List<Object> out = new ArrayList<>(Math.min(n, 1_024));
    for (int i = 0; i < n; i++) {
      if (is.peek(Protocol.MINUS_BYTE)) {
        try {
          out.add(Protocol.read(is));
        } catch (JedisDataException e) {
          out.add(e);
        }
      } else {
        out.add(read(is, keep));
      }
    }
    return out;
  }

  private static Object bulk(RedisInputStream is, int keep) {
    long length = is.readLongCrLf();
    if (length == -1) {
      return null;
    }
    if (length < 0) {
      throw new JedisConnectionException("Invalid bulk length " + length);
    }
    byte[] head = new byte[(int) Math.min(length, keep)];
    fill(is, head, head.length);
    long left = length - head.length;
    if (left > 0) {
      byte[] scratch = new byte[(int) Math.min(left, SKIP_CHUNK)];
      while (left > 0) {
        left -= fill(is, scratch, (int) Math.min(left, scratch.length));
      }
    }
    is.readByte();
    is.readByte();
    return head.length == length ? head : new Cut(head, length);
  }

  private static int fill(RedisInputStream is, byte[] into, int length) {
    int offset = 0;
    while (offset < length) {
      int n = is.read(into, offset, length - offset);
      if (n < 0) {
        throw new JedisConnectionException("It seems like server has closed the connection.");
      }
      offset += n;
    }
    return length;
  }
}
