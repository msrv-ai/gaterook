package ai.msrv.gaterook.core.audit.export;

import ai.msrv.gaterook.connectors.support.TrustStores;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.Cidr;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Syslog over TLS to a SIEM (RFC 5425): one TLS connection per batch, every record an RFC 5424 message framed by its
 * octet count ({@code MSG-LEN SP SYSLOG-MSG}), the message's {@code MSG} the record as one line of JSON (the core sends
 * JSON lines, not CEF). The server's certificate must chain to the platform's CAs or the configured one and name the
 * host; a client certificate is offered when one is set (mutual TLS). The name resolves here and every address is
 * judged before the connection (§9 "SSRF": never metadata, link-local or the core's own ports; private networks are
 * where SIEMs live).
 *
 * <p>Syslog has no acknowledgement: a batch counts as delivered once it was written and the connection closed
 * cleanly. Every message carries the row's id and hash, so a receiver that saw a batch twice (a stop before the cursor
 * moved) can tell the copies apart.
 */
public final class SiemSender implements ExportSender {

  /** {@code MSGID} of a row's message, of the head's and of Test's. */
  static final String ROW_MSGID = "tool-call";
  static final String HEAD_MSGID = "audit-head";
  static final String TEST_MSGID = "connection-test";

  /** How long a delivered batch waits for a late refusal of the server. */
  private static final int SETTLE_MS = 250;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> PRIVATE = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
      "192.168.0.0/16", "100.64.0.0/10", "198.18.0.0/15", "fc00::/7", "fec0::/10");
  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("siem-watchdog").factory());
  private static final String PROCESS_ID = Long.toString(ProcessHandle.current().pid());

  private final ExportSettings.Siem settings;
  private final PrivateKey clientKey;
  private final String hostname;
  private final Set<Integer> corePorts;
  private final Duration timeout;

  /**
   * @param clientKey the private key of the client certificate, or {@code null} without mutual TLS
   * @param hostname  this computer's name for the {@code HOSTNAME} field
   * @param timeout   for the connection and the handshake; a batch gets this much more per thousand records
   */
  public SiemSender(ExportSettings.Siem settings, PrivateKey clientKey, String hostname, Set<Integer> corePorts,
      Duration timeout) {
    this.settings = settings;
    this.clientKey = clientKey;
    this.hostname = header(hostname, 255);
    this.corePorts = Set.copyOf(corePorts);
    this.timeout = timeout;
  }

  @Override
  public long send(Batch batch) throws ExportException {
    List<ObjectNode> records = batch.records();
    long written = 0;
    Duration deadline = timeout.plus(timeout.multipliedBy(1 + records.size() / 1_000));
    try (SSLSocket socket = connect()) {
      ScheduledFuture<?> stuck = WATCHDOG.schedule(() -> closeQuietly(socket), deadline.toMillis(),
          TimeUnit.MILLISECONDS);
      try {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
        for (ObjectNode r : records) {
          boolean head = ExportRecords.HEAD.equals(r.path("type").asString());
          byte[] frame = frame(message(r, head ? HEAD_MSGID : ROW_MSGID, severity(r), at(r)));
          out.write(frame);
          written += frame.length;
        }
        out.flush();
        settled(socket);
      } finally {
        stuck.cancel(false);
      }
    } catch (IOException e) {
      throw new ExportException("Couldn't send to " + settings.describe() + ": " + reason(e), e);
    }
    return written;
  }

  @Override
  public String test() throws ExportException {
    try (SSLSocket socket = connect()) {
      ObjectNode probe = JsonNodeFactory.instance.objectNode();
      probe.put("type", TEST_MSGID);
      probe.put("at", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
      OutputStream out = socket.getOutputStream();
      out.write(frame(message(probe, TEST_MSGID, 6, Instant.now())));
      out.flush();
      settled(socket);
      String peer = socket.getSession().getPeerCertificates()[0] instanceof X509Certificate x
          ? x.getSubjectX500Principal().getName() : "the server";
      return "Connected to " + settings.describe() + " over " + socket.getSession().getProtocol() + " (" + peer
          + ") and sent a test message";
    } catch (IOException e) {
      throw new ExportException("Couldn't connect to " + settings.describe() + ": " + reason(e), e);
    }
  }

  /**
   * Gives the server a moment to object before the batch counts as delivered. A syslog receiver never writes back, so
   * a read can only time out — or bring the alert of a server that refused the connection after the handshake: with
   * TLS 1.3 a client certificate the server doesn't accept is reported only then, after the client already wrote — or
   * the end of the stream: a receiver "unprepared to receive any more data" closes (RFC 5425 §4.4), and what was
   * written after its close is gone. Then the batch isn't delivered: its retry sends it whole, and the ids and hashes
   * in the messages tell a receiver the copies apart.
   */
  private static void settled(SSLSocket socket) throws IOException {
    socket.setSoTimeout(SETTLE_MS);
    try {
      if (socket.getInputStream().read() < 0) {
        throw new IOException("the SIEM closed the connection before the batch was through");
      }
    } catch (java.net.SocketTimeoutException quiet) {
      // Nothing to say: the usual answer of a syslog receiver.
    }
  }

  /** {@code <PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID MSGID - MSG} (RFC 5424, {@code MSG} without a BOM). */
  String message(ObjectNode record, String msgid, int severity, Instant at) {
    int pri = settings.facility() * 8 + severity;
    return "<" + pri + ">1 " + at.truncatedTo(ChronoUnit.MILLIS) + " " + hostname + " "
        + header(settings.appName(), 48) + " " + PROCESS_ID + " " + msgid + " - " + JSON.writeValueAsString(record);
  }

  /** RFC 5425 §4.3 octet counting: {@code MSG-LEN SP SYSLOG-MSG}, the length in bytes of the UTF-8 message. */
  static byte[] frame(String message) {
    byte[] body = message.getBytes(StandardCharsets.UTF_8);
    byte[] length = (body.length + " ").getBytes(StandardCharsets.US_ASCII);
    byte[] out = new byte[length.length + body.length];
    System.arraycopy(length, 0, out, 0, length.length);
    System.arraycopy(body, 0, out, length.length, body.length);
    return out;
  }

  /**
   * RFC 5424 severity by what the call came to: 6 informational (allowed, approved, waiting, the head), 5 notice (a
   * person rejected it or nobody answered), 4 warning (a policy denied it), 3 error (it failed).
   */
  static int severity(ObjectNode record) {
    return switch (record.path("decision").asString("")) {
      case "rejected", "expired" -> 5;
      case "denied" -> 4;
      case "error" -> 3;
      default -> 6;
    };
  }

  private static Instant at(ObjectNode record) {
    try {
      return Instant.parse(record.path("at").asString());
    } catch (RuntimeException e) {
      return Instant.now();
    }
  }

  private SSLSocket connect() throws IOException {
    InetAddress address = resolve();
    Socket plain = new Socket(java.net.Proxy.NO_PROXY);
    try {
      int ms = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
      plain.connect(new InetSocketAddress(address, settings.port()), ms);
      plain.setSoTimeout(ms);
      SSLSocket tls = (SSLSocket) context().getSocketFactory().createSocket(plain, settings.host(), settings.port(),
          true);
      SSLParameters parameters = tls.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      if (AddressPolicy.canonicalIp(settings.host()).isEmpty()) {
        parameters.setServerNames(List.of(new SNIHostName(settings.host())));
      }
      tls.setSSLParameters(parameters);
      tls.startHandshake();
      return tls;
    } catch (IOException | RuntimeException e) {
      closeQuietly(plain);
      throw e;
    }
  }

  /** The SIEM's address, judged as it is connected to: the name a person typed may not lead anywhere forbidden. */
  private InetAddress resolve() throws IOException {
    AddressPolicy policy = AddressPolicy.builder().allowPrivate(PRIVATE.stream().map(Cidr::parse).toList())
        .corePorts(corePorts).build();
    InetAddress[] all;
    try {
      all = InetAddress.getAllByName(settings.host());
    } catch (UnknownHostException e) {
      throw new IOException(settings.host() + " doesn't resolve", e);
    }
    String refused = null;
    for (InetAddress a : all) {
      AddressPolicy.Verdict v = policy.check(settings.host(), a, settings.port());
      if (v.allowed()) {
        return a;
      }
      refused = v.text();
    }
    throw new IOException(refused == null ? settings.host() + " has no address" : refused);
  }

  private SSLContext context() throws IOException {
    try {
      X509TrustManager trust = TrustStores.of(settings.ca(), true);
      KeyManager[] keys = null;
      if (clientKey != null && !settings.clientCert().isEmpty()) {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        char[] none = new char[0];
        store.setKeyEntry("client", clientKey, none, settings.clientCert().toArray(Certificate[]::new));
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, none);
        keys = factory.getKeyManagers();
      }
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keys, new TrustManager[] {trust}, null);
      return context;
    } catch (GeneralSecurityException e) {
      throw new IOException("Can't set up TLS: " + e.getMessage(), e);
    }
  }

  /** What went wrong, in words: TLS problems say so, a timeout says how long. */
  private String reason(IOException e) {
    if (e instanceof java.net.SocketTimeoutException) {
      return "no answer within " + timeout.toSeconds() + " s";
    }
    if (e instanceof SSLException) {
      return "TLS failed (" + e.getMessage() + ")";
    }
    if (e instanceof java.net.ConnectException) {
      return "connection refused";
    }
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  /** A header field of RFC 5424: printable US-ASCII without spaces, at most {@code max} characters, else "-". */
  private static String header(String value, int max) {
    if (value == null || value.isEmpty()) {
      return "-";
    }
    StringBuilder out = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (c > 32 && c < 127 && out.length() < max) {
        out.append(c);
      }
    }
    return out.isEmpty() ? "-" : out.toString();
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // Closing is all that's left to do.
    }
  }
}
