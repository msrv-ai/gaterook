package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.spi.dns.DnsClient;
import com.mongodb.spi.dns.DnsException;
import com.mongodb.spi.dns.DnsWithResponseCodeException;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Hashtable;
import java.util.List;
import java.util.function.Supplier;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;

/**
 * The SRV and TXT lookups of {@code mongodb+srv://} (ARCHITECTURE.md §1 A8): the driver's default goes through JNDI's
 * DNS provider, which the native image doesn't register, so the gateway asks the DNS servers itself — UDP, then TCP
 * when the answer is truncated, a random id per question, answers only from the server asked and only for the
 * question asked. NXDOMAIN is {@link DnsWithResponseCodeException} with code 3 (the driver then treats a missing TXT
 * record as none); a server that fails or doesn't answer is skipped for the next one. Without resolv.conf (Windows)
 * it is JNDI after all, the way the driver would do it.
 *
 * <p>The whole lookup is bounded ({@value #BUDGET_MS} ms): the TXT lookup runs on the caller's thread when the client
 * is built, and a dead DNS server must not eat Test connection's budget.
 */
final class SrvDns implements DnsClient {

  static final long BUDGET_MS = 5_000;
  private static final int UDP_MAX = 4_096;

  private final Supplier<Nameservers> servers;
  private final SecureRandom random = new SecureRandom();

  SrvDns(Supplier<Nameservers> servers) {
    this.servers = servers;
  }

  /** The servers this client asks now (for texts: "asked 10.0.0.2"). */
  Nameservers current() {
    return servers.get();
  }

  @Override
  public List<String> getResourceRecordData(String name, String typeName) throws DnsException {
    int type;
    try {
      type = DnsMessage.type(typeName);
    } catch (IllegalArgumentException e) {
      throw new DnsException(e.getMessage(), e);
    }
    Nameservers ns = servers.get();
    if (ns.jndi()) {
      return jndi(name, typeName);
    }
    long deadline = System.nanoTime() + BUDGET_MS * 1_000_000;
    DnsException last = null;
    for (int attempt = 0; attempt < ns.attempts(); attempt++) {
      for (InetSocketAddress server : ns.servers()) {
        long left = (deadline - System.nanoTime()) / 1_000_000;
        if (left <= 0) {
          throw last != null ? last : new DnsException("No DNS answer for " + name + " within " + BUDGET_MS
              + " ms", null);
        }
        if (Thread.currentThread().isInterrupted()) {
          throw new DnsException("The lookup of " + name + " was cancelled", null);
        }
        Duration timeout = Duration.ofMillis(Math.min(ns.timeout().toMillis(), left));
        try {
          DnsMessage.Answer a = udp(server, name, type, timeout);
          if (a.truncated()) {
            a = tcp(server, name, type, timeout);
          }
          if (a.rcode() == 0) {
            return a.records();
          }
          if (a.rcode() == DnsMessage.RCODE_NAME_ERROR) {
            throw new DnsWithResponseCodeException(name + " doesn't exist (NXDOMAIN from "
                + server.getHostString() + ")", DnsMessage.RCODE_NAME_ERROR, null);
          }
          // SERVFAIL, REFUSED, FORMERR…: the C resolver moves on to the next server too.
          last = new DnsWithResponseCodeException("DNS server " + server.getHostString() + " answered " + rcode(
              a.rcode()) + " for " + name, a.rcode(), null);
        } catch (SocketTimeoutException e) {
          last = new DnsException("DNS server " + server.getHostString() + " didn't answer for " + name + " within "
              + timeout.toMillis() + " ms", e);
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
          last = new DnsException("DNS lookup of " + name + " at " + server.getHostString() + " failed: "
              + e.getMessage(), e);
        }
      }
    }
    throw last != null ? last : new DnsException("No DNS server to ask for " + name, null);
  }

  private DnsMessage.Answer udp(InetSocketAddress server, String name, int type, Duration timeout)
      throws IOException {
    int id = random.nextInt(1 << 16);
    byte[] query = DnsMessage.query(id, name, type);
    try (DatagramSocket socket = new DatagramSocket()) {
      // Connected: the socket takes datagrams from that server only.
      socket.connect(server);
      socket.send(new DatagramPacket(query, query.length));
      long until = System.nanoTime() + timeout.toNanos();
      byte[] buf = new byte[UDP_MAX];
      while (true) {
        long left = (until - System.nanoTime()) / 1_000_000;
        if (left <= 0) {
          throw new SocketTimeoutException("no answer");
        }
        socket.setSoTimeout((int) Math.max(1, left));
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        try {
          return DnsMessage.parse(buf, packet.getLength(), id, name, type);
        } catch (DnsMessage.NotOurs late) {
          // A late answer to an earlier question: keep waiting for ours until the timeout.
        }
      }
    }
  }

  private DnsMessage.Answer tcp(InetSocketAddress server, String name, int type, Duration timeout)
      throws IOException {
    int id = random.nextInt(1 << 16);
    byte[] query = DnsMessage.query(id, name, type);
    try (Socket socket = new Socket()) {
      socket.connect(server, (int) Math.max(1, timeout.toMillis()));
      socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
      OutputStream out = socket.getOutputStream();
      out.write(new byte[] {(byte) (query.length >>> 8), (byte) query.length});
      out.write(query);
      out.flush();
      InputStream in = socket.getInputStream();
      DataInputStream data = new DataInputStream(in);
      int length = data.readUnsignedShort();
      byte[] msg = new byte[length];
      data.readFully(msg);
      DnsMessage.Answer a = DnsMessage.parse(msg, length, id, name, type);
      if (a.truncated()) {
        throw new IllegalStateException("DNS answer over TCP is truncated");
      }
      return a;
    }
  }

  private static String rcode(int code) {
    return switch (code) {
      case 1 -> "FORMERR";
      case 2 -> "SERVFAIL";
      case 4 -> "NOTIMP";
      case 5 -> "REFUSED";
      default -> "RCODE " + code;
    };
  }

  /** The JDK's DNS provider with the system's servers: what the driver does on its own (Windows). */
  private static List<String> jndi(String name, String type) throws DnsException {
    Hashtable<String, String> env = new Hashtable<>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
    env.put(Context.PROVIDER_URL, "dns:");
    env.put("com.sun.jndi.dns.timeout.initial", "1000");
    env.put("com.sun.jndi.dns.timeout.retries", "2");
    InitialDirContext ctx;
    try {
      ctx = new InitialDirContext(env);
    } catch (NamingException e) {
      throw new DnsException("The system's DNS client isn't available: " + e.getMessage(), e);
    }
    try {
      Attribute attribute = ctx.getAttributes(name, new String[] {type}).get(type);
      if (attribute == null) {
        return List.of();
      }
      List<String> values = new java.util.ArrayList<>();
      NamingEnumeration<?> all = attribute.getAll();
      while (all.hasMore()) {
        values.add(String.valueOf(all.next()));
      }
      return List.copyOf(values);
    } catch (NameNotFoundException e) {
      throw new DnsWithResponseCodeException(e.getMessage(), DnsMessage.RCODE_NAME_ERROR, e);
    } catch (NamingException e) {
      throw new DnsException(e.getMessage(), e);
    } finally {
      try {
        ctx.close();
      } catch (NamingException ignored) {
        // Nothing to release that matters.
      }
    }
  }
}
