package ai.msrv.gaterook.core.audit.export;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLServerSocket;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A SIEM in the test: a syslog receiver over TLS (RFC 5425) on 127.0.0.1 with a certificate of its own test CA,
 * optionally asking for a client certificate. It reads octet-counted frames ({@code MSG-LEN SP SYSLOG-MSG}) exactly as
 * the RFC defines them and keeps every RFC 5424 message, its connection's number and whether a client certificate
 * came with it.
 */
public final class FakeSyslog implements AutoCloseable {

  private static final Pattern RFC5424 = Pattern.compile(
      "<(\\d{1,3})>1 (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) (-|\\[.*?]) (.*)", Pattern.DOTALL);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One message as received. */
  public record Message(int connection, int pri, String timestamp, String hostname, String appName, String procId,
      String msgId, String structuredData, String msg, boolean clientCertificate) {

    public int facility() {
      return pri / 8;
    }

    public int severity() {
      return pri % 8;
    }

    public JsonNode json() {
      return JSON.readTree(msg);
    }
  }

  private final HeldCertificate ca;
  private final SSLServerSocket server;
  private final List<Message> messages = new CopyOnWriteArrayList<>();
  private final List<String> problems = new CopyOnWriteArrayList<>();
  private final List<String> serverNames = new CopyOnWriteArrayList<>();
  private final Thread acceptor;
  private volatile int connections;
  private volatile int closeAfter = Integer.MAX_VALUE;

  /** @param requireClientCertificate mutual TLS: a client without a certificate of {@link #ca()} is refused */
  public FakeSyslog(boolean requireClientCertificate) throws IOException {
    this(requireClientCertificate, "localhost", "127.0.0.1");
  }

  /** @param names the names (and addresses) the receiver's certificate is for */
  public FakeSyslog(boolean requireClientCertificate, String... names) throws IOException {
    ca = new HeldCertificate.Builder().certificateAuthority(0).commonName("Fake SIEM CA").build();
    HeldCertificate.Builder builder = new HeldCertificate.Builder().signedBy(ca).commonName(names[0]);
    for (String name : names) {
      builder.addSubjectAlternativeName(name);
    }
    HeldCertificate cert = builder.build();
    HandshakeCertificates certificates = new HandshakeCertificates.Builder().heldCertificate(cert)
        .addTrustedCertificate(ca.certificate()).build();
    server = (SSLServerSocket) certificates.sslContext().getServerSocketFactory().createServerSocket(0, 50,
        InetAddress.getByName("127.0.0.1"));
    server.setNeedClientAuth(requireClientCertificate);
    acceptor = Thread.ofVirtual().name("fake-syslog").start(this::accept);
  }

  public int port() {
    return server.getLocalPort();
  }

  /** The test CA as PEM: the destination's {@code caCertificate}. */
  public String caPem() {
    return ca.certificatePem();
  }

  /** A client certificate of the test CA, for mutual TLS. */
  public HeldCertificate clientCertificate() {
    return new HeldCertificate.Builder().signedBy(ca).commonName("gaterook").build();
  }

  public List<Message> messages() {
    return List.copyOf(messages);
  }

  /** Messages of {@code msgId} as their JSON. */
  public List<JsonNode> json(String msgId) {
    List<JsonNode> out = new ArrayList<>();
    for (Message m : messages) {
      if (m.msgId().equals(msgId)) {
        out.add(m.json());
      }
    }
    return out;
  }

  /** Frames that broke the framing or the message format; empty when every byte was RFC 5425/5424. */
  public List<String> problems() {
    return List.copyOf(problems);
  }

  public int connections() {
    return connections;
  }

  /** The SNI host name each connection asked for ({@code -} without one), in order. */
  public List<String> serverNames() {
    return List.copyOf(serverNames);
  }

  /**
   * From now on the receiver keeps {@code messages} per connection and then closes its side (TLS close_notify), as
   * RFC 5425 §4.4 lets a receiver "unprepared to receive any more data" do; whatever comes after is read and dropped.
   */
  public void closeAfter(int messages) {
    closeAfter = messages;
  }

  private void accept() {
    while (!server.isClosed()) {
      try {
        Socket socket = server.accept();
        int number = ++connections;
        Thread.ofVirtual().start(() -> read(socket, number));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void read(Socket socket, int number) {
    try (socket; InputStream in = socket.getInputStream()) {
      javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) socket;
      boolean client;
      try {
        client = tls.getSession().getPeerCertificates().length > 0;
      } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
        client = false;
      }
      List<javax.net.ssl.SNIServerName> sni = tls.getSession() instanceof javax.net.ssl.ExtendedSSLSession x
          ? x.getRequestedServerNames() : List.of();
      serverNames.add(sni.isEmpty() ? "-" : ((javax.net.ssl.SNIHostName) sni.getFirst()).getAsciiName());
      int kept = 0;
      int limit = closeAfter;
      while (true) {
        if (kept >= limit) {
          tls.shutdownOutput();
          in.transferTo(java.io.OutputStream.nullOutputStream());
          return;
        }
        int length = length(in);
        if (length < 0) {
          return;
        }
        byte[] body = in.readNBytes(length);
        if (body.length != length) {
          problems.add("a frame announced " + length + " bytes and brought " + body.length);
          return;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        Matcher m = RFC5424.matcher(text);
        if (!m.matches()) {
          problems.add("not RFC 5424: " + text);
          continue;
        }
        messages.add(new Message(number, Integer.parseInt(m.group(1)), m.group(2), m.group(3), m.group(4),
            m.group(5), m.group(6), m.group(7), m.group(8), client));
        kept++;
      }
    } catch (IOException e) {
      // The client closed or the handshake failed: what came before is kept.
    }
  }

  /** {@code MSG-LEN SP}: digits, then one space; -1 at the end of the stream. */
  private int length(InputStream in) throws IOException {
    ByteArrayOutputStream digits = new ByteArrayOutputStream();
    while (true) {
      int b = in.read();
      if (b < 0) {
        if (digits.size() > 0) {
          throw new EOFException("cut inside a frame's length");
        }
        return -1;
      }
      if (b == ' ') {
        String text = digits.toString(StandardCharsets.US_ASCII);
        if (text.isEmpty() || text.startsWith("0")) {
          problems.add("a frame length of \"" + text + "\"");
        }
        return Integer.parseInt(text);
      }
      if (b < '0' || b > '9' || digits.size() > 9) {
        problems.add("a frame length with byte " + b);
        return -1;
      }
      digits.write(b);
    }
  }

  @Override
  public void close() throws IOException {
    server.close();
    acceptor.interrupt();
  }
}
