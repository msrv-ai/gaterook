package ai.msrv.gaterook.connectors.mongodb;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The wire format of one DNS question and its answer (RFC 1035 §4, SRV RFC 2782, TXT RFC 1035 §3.3.14): just what
 * {@code mongodb+srv://} needs. Names are ASCII (an SRV name is Punycode already), compression pointers are followed
 * with a bound on jumps, every length is checked against the message: a hostile or broken answer is an exception,
 * never an out-of-bounds read or a loop.
 */
final class DnsMessage {

  static final int TYPE_CNAME = 5;
  static final int TYPE_TXT = 16;
  static final int TYPE_SRV = 33;
  static final int CLASS_IN = 1;

  /** NXDOMAIN: the name doesn't exist (the driver ignores it for TXT). */
  static final int RCODE_NAME_ERROR = 3;
  static final int RCODE_SERVER_FAILURE = 2;

  private DnsMessage() {}

  static int type(String name) {
    return switch (name.toUpperCase(Locale.ROOT)) {
      case "SRV" -> TYPE_SRV;
      case "TXT" -> TYPE_TXT;
      default -> throw new IllegalArgumentException("Only SRV and TXT records are looked up, not " + name);
    };
  }

  /** A query with recursion desired. */
  static byte[] query(int id, String name, int type) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(64);
    short16(out, id);
    short16(out, 0x0100); // RD
    short16(out, 1); // QDCOUNT
    short16(out, 0);
    short16(out, 0);
    short16(out, 0);
    writeName(out, name);
    short16(out, type);
    short16(out, CLASS_IN);
    return out.toByteArray();
  }

  /** What came back for one question. */
  record Answer(int id, boolean truncated, int rcode, List<String> records) {}

  /**
   * Reads an answer to {@link #query}: the id and the question must match it; the records of {@code type} whose
   * owner is the name or a CNAME of it, as the driver's JNDI client gives them ({@code "priority weight port
   * target."} for SRV, the character-strings of one record joined for TXT).
   */
  static Answer parse(byte[] msg, int length, int expectedId, String name, int type) {
    Reader r = new Reader(msg, length);
    int id = r.u16();
    int flags = r.u16();
    if (id != expectedId) {
      throw new NotOurs("DNS answer for another query");
    }
    if ((flags & 0x8000) == 0) {
      throw new NotOurs("DNS message is not an answer");
    }
    boolean truncated = (flags & 0x0200) != 0;
    int rcode = flags & 0x000F;
    int qd = r.u16();
    int an = r.u16();
    r.u16();
    r.u16();
    if (qd != 1) {
      throw new IllegalStateException("DNS answer with " + qd + " questions");
    }
    String qname = r.name();
    int qtype = r.u16();
    int qclass = r.u16();
    if (!same(qname, name) || qtype != type || qclass != CLASS_IN) {
      throw new NotOurs("DNS answer to another question");
    }
    if (truncated || rcode != 0) {
      return new Answer(id, truncated, rcode, List.of());
    }
    record Rr(String owner, int type, String data) {}
    List<Rr> all = new ArrayList<>();
    for (int i = 0; i < an; i++) {
      String owner = r.name();
      int rtype = r.u16();
      int rclass = r.u16();
      r.u32();
      int rdlength = r.u16();
      int end = r.pos + rdlength;
      if (end > length) {
        throw new IllegalStateException("DNS record longer than the message");
      }
      String data = null;
      if (rclass == CLASS_IN) {
        if (rtype == TYPE_SRV) {
          int priority = r.u16();
          int weight = r.u16();
          int port = r.u16();
          String target = r.name();
          // A target of "." says the service is decidedly not there (RFC 2782): no host to add.
          data = target.isEmpty() ? null : priority + " " + weight + " " + port + " " + target + ".";
        } else if (rtype == TYPE_TXT) {
          StringBuilder text = new StringBuilder();
          while (r.pos < end) {
            int len = r.u8();
            text.append(r.string(len));
          }
          data = text.toString();
        } else if (rtype == TYPE_CNAME) {
          data = r.name();
        }
      }
      if (r.pos > end) {
        throw new IllegalStateException("DNS record data overruns its length");
      }
      r.pos = end;
      if (data != null) {
        all.add(new Rr(owner, rtype, data));
      }
    }
    // Owners: the name and whatever it is a CNAME of, in the order the server chained them.
    Set<String> owners = new HashSet<>();
    owners.add(name.toLowerCase(Locale.ROOT));
    boolean grew = true;
    while (grew) {
      grew = false;
      for (Rr rr : all) {
        if (rr.type() == TYPE_CNAME && owners.contains(rr.owner().toLowerCase(Locale.ROOT))
            && owners.add(rr.data().toLowerCase(Locale.ROOT))) {
          grew = true;
        }
      }
    }
    List<String> records = new ArrayList<>();
    for (Rr rr : all) {
      if (rr.type() == type && owners.contains(rr.owner().toLowerCase(Locale.ROOT))) {
        records.add(rr.data());
      }
    }
    return new Answer(id, false, rcode, List.copyOf(records));
  }

  static boolean same(String a, String b) {
    return strip(a).equalsIgnoreCase(strip(b));
  }

  private static String strip(String name) {
    return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
  }

  private static void writeName(ByteArrayOutputStream out, String name) {
    String n = strip(name);
    if (n.isEmpty() || n.length() > 253) {
      throw new IllegalArgumentException("Not a DNS name: " + name);
    }
    for (String label : n.split("\\.", -1)) {
      byte[] b = label.getBytes(StandardCharsets.US_ASCII);
      if (b.length == 0 || b.length > 63 || !label.chars().allMatch(c -> c > 32 && c < 127)) {
        throw new IllegalArgumentException("Not a DNS name: " + name);
      }
      out.write(b.length);
      out.writeBytes(b);
    }
    out.write(0);
  }

  private static void short16(ByteArrayOutputStream out, int v) {
    out.write((v >>> 8) & 0xFF);
    out.write(v & 0xFF);
  }

  /** A well-formed message that doesn't answer this question (a late answer to an earlier one): wait on. */
  static final class NotOurs extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    NotOurs(String message) {
      super(message);
    }
  }

  /** Bounds-checked reads over one message. */
  private static final class Reader {
    private final byte[] msg;
    private final int length;
    int pos;

    Reader(byte[] msg, int length) {
      this.msg = msg;
      this.length = length;
    }

    int u8() {
      if (pos >= length) {
        throw new IllegalStateException("DNS message ends early");
      }
      return msg[pos++] & 0xFF;
    }

    int u16() {
      return (u8() << 8) | u8();
    }

    long u32() {
      return ((long) u16() << 16) | u16();
    }

    String string(int len) {
      if (pos + len > length) {
        throw new IllegalStateException("DNS message ends early");
      }
      String s = new String(msg, pos, len, StandardCharsets.ISO_8859_1);
      pos += len;
      return s;
    }

    /** A name at the current position, compression pointers followed (at most 32 jumps, never forward loops). */
    String name() {
      StringBuilder out = new StringBuilder();
      int p = pos;
      int jumps = 0;
      boolean jumped = false;
      while (true) {
        if (p >= length) {
          throw new IllegalStateException("DNS name runs past the message");
        }
        int len = msg[p] & 0xFF;
        if ((len & 0xC0) == 0xC0) {
          if (p + 1 >= length) {
            throw new IllegalStateException("DNS name runs past the message");
          }
          int target = ((len & 0x3F) << 8) | (msg[p + 1] & 0xFF);
          if (!jumped) {
            pos = p + 2;
            jumped = true;
          }
          if (++jumps > 32 || target >= p) {
            throw new IllegalStateException("DNS name with a bad compression pointer");
          }
          p = target;
          continue;
        }
        if ((len & 0xC0) != 0) {
          throw new IllegalStateException("DNS name with an unknown label type");
        }
        p++;
        if (len == 0) {
          break;
        }
        if (p + len > length) {
          throw new IllegalStateException("DNS label runs past the message");
        }
        if (!out.isEmpty()) {
          out.append('.');
        }
        out.append(new String(msg, p, len, StandardCharsets.ISO_8859_1));
        p += len;
        if (out.length() > 255) {
          throw new IllegalStateException("DNS name longer than 255");
        }
      }
      if (!jumped) {
        pos = p;
      }
      return out.toString();
    }
  }
}
