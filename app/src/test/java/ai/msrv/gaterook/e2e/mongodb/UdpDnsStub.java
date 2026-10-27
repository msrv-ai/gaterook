package ai.msrv.gaterook.e2e.mongodb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The smallest DNS server a {@code mongodb+srv://} needs: SRV and TXT answers over UDP on 127.0.0.1, NXDOMAIN for
 * every other name. The core gets its address through {@code -Dgateway.test.dns-server}; what it asked is kept, so a
 * test sees that the lookups went through the gateway's own DNS client (the image has no JNDI DNS provider).
 */
final class UdpDnsStub implements AutoCloseable {

  private static final int SRV = 33;
  private static final int TXT = 16;

  private final DatagramSocket socket;
  private final Map<String, byte[]> srv = new ConcurrentHashMap<>();
  private final Map<String, byte[]> txt = new ConcurrentHashMap<>();
  final List<String> asked = new CopyOnWriteArrayList<>();

  UdpDnsStub() throws SocketException {
    socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    Thread t = new Thread(this::serve, "e2e-dns-stub");
    t.setDaemon(true);
    t.start();
  }

  String address() {
    return "127.0.0.1:" + socket.getLocalPort();
  }

  UdpDnsStub srv(String name, int port, String target) {
    ByteArrayOutputStream rdata = new ByteArrayOutputStream();
    short16(rdata, 0);
    short16(rdata, 0);
    short16(rdata, port);
    name(rdata, target);
    srv.put(name.toLowerCase(Locale.ROOT), rdata.toByteArray());
    return this;
  }

  UdpDnsStub txt(String name, String text) {
    ByteArrayOutputStream rdata = new ByteArrayOutputStream();
    byte[] b = text.getBytes(StandardCharsets.US_ASCII);
    rdata.write(b.length);
    rdata.writeBytes(b);
    txt.put(name.toLowerCase(Locale.ROOT), rdata.toByteArray());
    return this;
  }

  private void serve() {
    byte[] buf = new byte[512];
    while (!socket.isClosed()) {
      DatagramPacket in = new DatagramPacket(buf, buf.length);
      try {
        socket.receive(in);
        byte[] answer = answer(buf, in.getLength());
        if (answer != null) {
          socket.send(new DatagramPacket(answer, answer.length, in.getSocketAddress()));
        }
      } catch (IOException | RuntimeException e) {
        // A closed socket ends the loop; a malformed query is dropped.
      }
    }
  }

  private byte[] answer(byte[] q, int len) {
    if (len < 17) {
      return null;
    }
    StringBuilder name = new StringBuilder();
    int i = 12;
    while (i < len && q[i] != 0) {
      int l = q[i] & 0xff;
      if (l > 63 || i + 1 + l > len) {
        return null;
      }
      if (!name.isEmpty()) {
        name.append('.');
      }
      name.append(new String(q, i + 1, l, StandardCharsets.US_ASCII));
      i += 1 + l;
    }
    int questionEnd = i + 5;
    if (questionEnd > len) {
      return null;
    }
    int type = ((q[i + 1] & 0xff) << 8) | (q[i + 2] & 0xff);
    String key = name.toString().toLowerCase(Locale.ROOT);
    asked.add((type == SRV ? "SRV " : type == TXT ? "TXT " : type + " ") + key);
    byte[] rdata = type == SRV ? srv.get(key) : type == TXT ? txt.get(key) : null;
    boolean known = srv.containsKey(key) || txt.containsKey(key);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(q[0]);
    out.write(q[1]);
    // QR, opcode 0, AA, RD as asked, RA; NXDOMAIN (3) for a name it doesn't know, an empty answer otherwise.
    out.write(0x84 | (q[2] & 0x01));
    out.write(0x80 | (known ? 0 : 3));
    short16(out, 1);
    short16(out, rdata == null ? 0 : 1);
    short16(out, 0);
    short16(out, 0);
    out.write(q, 12, questionEnd - 12);
    if (rdata != null) {
      short16(out, 0xC00C);
      short16(out, type);
      short16(out, 1);
      short16(out, 0);
      short16(out, 60);
      short16(out, rdata.length);
      out.writeBytes(rdata);
    }
    return out.toByteArray();
  }

  private static void name(ByteArrayOutputStream out, String name) {
    for (String label : name.split("\\.")) {
      byte[] b = label.getBytes(StandardCharsets.US_ASCII);
      out.write(b.length);
      out.writeBytes(b);
    }
    out.write(0);
  }

  private static void short16(ByteArrayOutputStream out, int v) {
    out.write((v >> 8) & 0xff);
    out.write(v & 0xff);
  }

  @Override
  public void close() {
    socket.close();
  }
}
