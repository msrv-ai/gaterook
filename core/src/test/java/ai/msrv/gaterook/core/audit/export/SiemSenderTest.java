package ai.msrv.gaterook.core.audit.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.core.platform.HmacKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Syslog over TLS to a SIEM (RFC 5425) against a receiver in the test: octet-counted frames, RFC 5424 messages with
 * the row as JSON and a severity by decision, the signed head last; the server's certificate checked against the
 * configured CA and the host; mutual TLS with the client certificate.
 */
class SiemSenderTest {

  /** HMAC-SHA256 under one fixed key: the shape of {@code secrets}' implementation, without the master key. */
  static final HmacKeys KEYS = (purpose, data) -> {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(("test-" + purpose.info()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(data);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  };

  @Test
  void aBatchArrivesAsOctetCountedRfc5424MessagesWithTheHeadLast() throws Exception {
    try (FakeSyslog siem = new FakeSyslog(false)) {
      SiemSender sender = sender(siem, siem.caPem(), null, null);
      Batch batch = batch();

      long bytes = sender.send(batch);

      await(() -> siem.messages().size() == 4, "four messages");
      assertThat(siem.problems()).isEmpty();
      assertThat(bytes).isPositive();
      List<FakeSyslog.Message> messages = siem.messages();
      assertThat(messages).extracting(FakeSyslog.Message::msgId).containsExactly("tool-call", "tool-call",
          "tool-call", "audit-head");
      assertThat(messages).extracting(FakeSyslog.Message::connection).containsOnly(1);
      assertThat(messages).allSatisfy(m -> {
        assertThat(m.facility()).as("log audit").isEqualTo(13);
        assertThat(m.appName()).isEqualTo("gaterook");
        assertThat(m.structuredData()).isEqualTo("-");
      });
      assertThat(messages).extracting(FakeSyslog.Message::severity).containsExactly(6, 4, 3, 6);
      assertThat(messages.get(0).timestamp()).isEqualTo("2026-10-22T12:29:12.123Z");
      assertThat(messages.get(1).json().path("rule").asString()).isEqualTo("prod.sql.block_ddl");
      assertThat(messages.get(1).json().path("args").path("sql").asString()).isEqualTo("DROP TABLE orders_archive");
      assertThat(ExportRecords.verify(KEYS, messages.get(3).json())).as("the head's MAC").isTrue();
      ObjectNode forged = (ObjectNode) messages.get(3).json().deepCopy();
      forged.put("rows", 2);
      assertThat(ExportRecords.verify(KEYS, forged)).isFalse();
    }
  }

  @Test
  void theFrameCountsBytesNotCharacters() {
    byte[] frame = SiemSender.frame("<110>1 - - - - - - {\"note\":\"Grüße\"}");
    String text = new String(frame, StandardCharsets.UTF_8);
    int space = text.indexOf(' ');
    assertThat(Integer.parseInt(text.substring(0, space))).isEqualTo(frame.length - space - 1);
  }

  @Test
  void aServerNobodyTrustsIsRefusedAndNothingIsSent() throws Exception {
    try (FakeSyslog siem = new FakeSyslog(false)) {
      SiemSender sender = sender(siem, null, null, null);

      assertThatThrownBy(() -> sender.send(batch())).isInstanceOf(ExportException.class)
          .hasMessageContaining("TLS failed");
      assertThat(siem.messages()).isEmpty();
    }
  }

  @Test
  void theServersCertificateMustNameTheHostAndANameGoesAsSni() throws Exception {
    try (FakeSyslog other = new FakeSyslog(false, "siem.acme.internal")) {
      // A CA the destination trusts, a certificate for another name: by address and by name, nothing is sent.
      assertThatThrownBy(() -> sender(other, other.caPem(), null, null).send(batch()))
          .isInstanceOf(ExportException.class).hasMessageContaining("TLS failed");
      assertThatThrownBy(() -> byName(other, "localhost").send(batch())).isInstanceOf(ExportException.class)
          .hasMessageContaining("TLS failed");
      assertThat(other.messages()).isEmpty();
    }
    try (FakeSyslog siem = new FakeSyslog(false, "localhost")) {
      byName(siem, "localhost").send(batch());

      await(() -> siem.messages().size() == 4, "four messages");
      assertThat(siem.serverNames()).as("SNI").containsExactly("localhost");
    }
  }

  @Test
  void aReceiverThatClosesBeforeTheBatchIsThroughLeavesItUndelivered() throws Exception {
    try (FakeSyslog siem = new FakeSyslog(false)) {
      siem.closeAfter(1);

      assertThatThrownBy(() -> sender(siem, siem.caPem(), null, null).send(batch()))
          .isInstanceOf(ExportException.class).hasMessageContaining("closed the connection");
      assertThat(siem.messages()).as("it kept one message of four").hasSize(1);
    }
  }

  @Test
  void mutualTlsSendsTheClientCertificateAndWithoutItTheServerRefuses() throws Exception {
    try (FakeSyslog siem = new FakeSyslog(true)) {
      HeldCertificate client = siem.clientCertificate();
      SiemSender withCertificate = sender(siem, siem.caPem(), client.certificatePem(),
          client.privateKeyPkcs8Pem());
      withCertificate.send(batch());
      await(() -> siem.messages().size() == 4, "four messages");
      assertThat(siem.messages()).allSatisfy(m -> assertThat(m.clientCertificate()).isTrue());

      // TLS 1.3 reports a refused client certificate after the handshake: the sender waits for it.
      SiemSender without = sender(siem, siem.caPem(), null, null);
      assertThatThrownBy(() -> without.send(batch())).isInstanceOf(ExportException.class)
          .hasMessageContaining("TLS failed");
      assertThat(siem.messages()).hasSize(4);
    }
  }

  @Test
  void testSendsOneTestMessage() throws Exception {
    try (FakeSyslog siem = new FakeSyslog(false)) {
      String found = sender(siem, siem.caPem(), null, null).test();

      assertThat(found).contains("127.0.0.1:" + siem.port()).contains("TLSv1.");
      await(() -> siem.messages().size() == 1, "the test message");
      assertThat(siem.messages().getFirst().msgId()).isEqualTo("connection-test");
    }
  }

  @Test
  void theCoresOwnPortsAndMetadataAreNeverAddressed() throws Exception {
    try (FakeSyslog siem = new FakeSyslog(false)) {
      SiemSender core = new SiemSender(new ExportSettings.Siem("127.0.0.1", siem.port(), 13, "gaterook",
          Pem.certificates(siem.caPem()), List.of()), null, "host", Set.of(siem.port()), Duration.ofSeconds(5));
      assertThatThrownBy(() -> core.send(batch())).isInstanceOf(ExportException.class);
      SiemSender metadata = new SiemSender(new ExportSettings.Siem("169.254.169.254", 6514, 13, "gaterook",
          List.of(), List.of()), null, "host", Set.of(), Duration.ofSeconds(2));
      assertThatThrownBy(() -> metadata.send(batch())).isInstanceOf(ExportException.class)
          .hasMessageContaining("169.254.169.254");
      assertThat(siem.connections()).isZero();
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  static SiemSender sender(FakeSyslog siem, String caPem, String clientCertPem, String clientKeyPem) {
    return new SiemSender(new ExportSettings.Siem("127.0.0.1", siem.port(), 13, "gaterook",
        caPem == null ? List.of() : Pem.certificates(caPem), clientCertPem == null ? List.of()
            : Pem.certificates(clientCertPem)), clientKeyPem == null ? null : Pem.privateKey(clientKeyPem),
        "ravi-mbp", Set.of(), Duration.ofSeconds(5));
  }

  /** A sender to {@code siem} by the name {@code host} (it resolves to the receiver's 127.0.0.1). */
  private static SiemSender byName(FakeSyslog siem, String host) {
    return new SiemSender(new ExportSettings.Siem(host, siem.port(), 13, "gaterook", Pem.certificates(
        siem.caPem()), List.of()), null, "ravi-mbp", Set.of(), Duration.ofSeconds(5));
  }

  /** Three rows — allowed, denied, failed — and their signed head. */
  static Batch batch() {
    ObjectNode allowed = row(41, "allowed", null);
    ObjectNode denied = row(42, "denied", "prod.sql.block_ddl");
    denied.putObject("args").put("sql", "DROP TABLE orders_archive");
    ObjectNode error = row(43, "error", null);
    ObjectNode head = ExportRecords.head(KEYS, 41L, 43, 3, "a".repeat(64), "c".repeat(64),
        Instant.parse("2026-10-22T12:30:00Z"));
    return new Batch("000000000041-000000000043", List.of(allowed, denied, error), head,
        Instant.parse("2026-10-22T12:29:12.123Z"));
  }

  private static ObjectNode row(long id, String decision, String rule) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("type", "tool-call");
    n.put("id", id);
    n.put("callId", "tc_" + id);
    n.put("at", Instant.parse("2026-10-22T12:29:12.123Z").plusSeconds(id - 41).toString());
    n.put("tool", "sql_execute");
    n.put("decision", decision);
    n.put("rule", rule);
    n.put("rowSha256", Long.toHexString(id).repeat(8));
    return n;
  }

  static void await(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }
}
