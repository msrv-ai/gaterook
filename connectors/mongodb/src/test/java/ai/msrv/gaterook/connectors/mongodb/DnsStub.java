package ai.msrv.gaterook.connectors.mongodb;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A DNS server for tests on {@code 127.0.0.1}, UDP and TCP on one ephemeral port: SRV, TXT and CNAME records, and the
 * ways a real server misbehaves — NXDOMAIN, SERVFAIL, silence, a truncated UDP answer (the client must ask again over
 * TCP), a late answer with another id before the right one.
 */
final class DnsStub implements AutoCloseable {

  private final DatagramSocket udp;
  private final ServerSocket tcp;
  private final Map<String, List<Record>> records = new ConcurrentHashMap<>();
  private final AtomicInteger udpQueries = new AtomicInteger();
  private final AtomicInteger tcpQueries = new AtomicInteger();
  volatile boolean silent;
  volatile boolean serverFailure;
  volatile boolean truncateUdp;
  volatile boolean staleFirst;
  private volatile boolean closed;

  private record Record(int type, byte[] rdata) {}

  DnsStub() throws IOException {
    DatagramSocket u = null;
    ServerSocket t = null;
    for (int attempt = 0; attempt < 20 && t == null; attempt++) {
      u = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      try {
        t = new ServerSocket();
        t.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), u.getLocalPort()));
      } catch (IOException e) {
        u.close();
        t = null;
      }
    }
    if (t == null) {
      throw new IOException("No port free for UDP and TCP at once");
    }
    this.udp = u;
    this.tcp = t;
    Thread.ofPlatform().daemon().name("dns-stub-udp").start(this::serveUdp);
    Thread.ofPlatform().daemon().name("dns-stub-tcp").start(this::serveTcp);
  }

  InetSocketAddress address() {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), udp.getLocalPort());
  }

  int udpQueries() {
    return udpQueries.get();
  }

  int tcpQueries() {
    return tcpQueries.get();
  }

  DnsStub srv(String name, int priority, int weight, int port, String target) {
    ByteArrayOutputStream rdata = new ByteArrayOutputStream();
    u16(rdata, priority);
    u16(rdata, weight);
    u16(rdata, port);
    name(rdata, target);
    add(name, DnsMessage.TYPE_SRV, rdata.toByteArray());
    return this;
  }

  DnsStub txt(String name, String... strings) {
    ByteArrayOutputStream rdata = new ByteArrayOutputStream();
    for (String s : strings) {
      byte[] b = s.getBytes(StandardCharsets.US_ASCII);
      rdata.write(b.length);
      rdata.writeBytes(b);
    }
    add(name, DnsMessage.TYPE_TXT, rdata.toByteArray());
    return this;
  }

  DnsStub cname(String name, String target) {
    ByteArrayOutputStream rdata = new ByteArrayOutputStream();
    name(rdata, target);
    add(name, DnsMessage.TYPE_CNAME, rdata.toByteArray());
    return this;
  }

  private void add(String name, int type, byte[] rdata) {
    records.computeIfAbsent(key(name), k -> new ArrayList<>()).add(new Record(type, rdata));
  }

  private static String key(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
  }

  private void serveUdp() {
    byte[] buf = new byte[4_096];
    while (!closed) {
      try {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        udp.receive(packet);
        udpQueries.incrementAndGet();
        if (silent) {
          continue;
        }
        byte[] query = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
        if (staleFirst) {
          byte[] stale = answer(query, false);
          stale[0] ^= 0x55;
          udp.send(new DatagramPacket(stale, stale.length, packet.getSocketAddress()));
        }
        byte[] answer = answer(query, truncateUdp);
        udp.send(new DatagramPacket(answer, answer.length, packet.getSocketAddress()));
      } catch (IOException e) {
        if (closed) {
          return;
        }
      }
    }
  }

  private void serveTcp() {
    while (!closed) {
      try (Socket s = tcp.accept()) {
        tcpQueries.incrementAndGet();
        DataInputStream in = new DataInputStream(s.getInputStream());
        int length = in.readUnsignedShort();
        byte[] query = new byte[length];
        in.readFully(query);
        byte[] answer = answer(query, false);
        OutputStream out = s.getOutputStream();
        out.write(new byte[] {(byte) (answer.length >>> 8), (byte) answer.length});
        out.write(answer);
        out.flush();
      } catch (SocketException e) {
        if (closed) {
          return;
        }
      } catch (IOException ignored) {
        // A client that went away.
      }
    }
  }

  /** An answer to {@code query}: its id and question, then the records of the asked type (and CNAMEs). */
  private byte[] answer(byte[] query, boolean truncated) {
    int pos = 12;
    StringBuilder name = new StringBuilder();
    while (query[pos] != 0) {
      int len = query[pos] & 0xFF;
      if (!name.isEmpty()) {
        name.append('.');
      }
      name.append(new String(query, pos + 1, len, StandardCharsets.US_ASCII));
      pos += len + 1;
    }
    pos++;
    int type = ((query[pos] & 0xFF) << 8) | (query[pos + 1] & 0xFF);
    int questionEnd = pos + 4;
    List<byte[]> answers = new ArrayList<>();
    int rcode = 0;
    if (serverFailure) {
      rcode = DnsMessage.RCODE_SERVER_FAILURE;
    } else {
      String current = key(name.toString());
      List<Record> list = records.get(current);
      if (list == null) {
        rcode = DnsMessage.RCODE_NAME_ERROR;
      } else if (!truncated) {
        for (Record r : list) {
          if (r.type() == type || r.type() == DnsMessage.TYPE_CNAME) {
            answers.add(rr(current, r));
          }
          if (r.type() == DnsMessage.TYPE_CNAME) {
            String target = decodeName(r.rdata());
            for (Record t : records.getOrDefault(key(target), List.of())) {
              if (t.type() == type) {
                answers.add(rr(key(target), t));
              }
            }
          }
        }
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(query[0]);
    out.write(query[1]);
    u16(out, 0x8180 | (truncated ? 0x0200 : 0) | rcode);
    u16(out, 1);
    u16(out, answers.size());
    u16(out, 0);
    u16(out, 0);
    out.write(query, 12, questionEnd - 12);
    answers.forEach(out::writeBytes);
    return out.toByteArray();
  }

  private static byte[] rr(String owner, Record r) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    name(out, owner);
    u16(out, r.type());
    u16(out, DnsMessage.CLASS_IN);
    u16(out, 0);
    u16(out, 60);
    u16(out, r.rdata().length);
    out.writeBytes(r.rdata());
    return out.toByteArray();
  }

  private static String decodeName(byte[] rdata) {
    StringBuilder name = new StringBuilder();
    int pos = 0;
    while (rdata[pos] != 0) {
      int len = rdata[pos] & 0xFF;
      if (!name.isEmpty()) {
        name.append('.');
      }
      name.append(new String(rdata, pos + 1, len, StandardCharsets.US_ASCII));
      pos += len + 1;
    }
    return name.toString();
  }

  private static void name(ByteArrayOutputStream out, String name) {
    String n = key(name);
    if (!n.isEmpty()) {
      for (String label : n.split("\\.")) {
        out.write(label.length());
        out.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
      }
    }
    out.write(0);
  }

  private static void u16(ByteArrayOutputStream out, int v) {
    out.write((v >>> 8) & 0xFF);
    out.write(v & 0xFF);
  }

  @Override
  public void close() {
    closed = true;
    udp.close();
    try {
      tcp.close();
    } catch (IOException ignored) {
      // Closing anyway.
    }
  }
}
