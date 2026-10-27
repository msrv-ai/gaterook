package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.MongoConfigurationException;
import com.mongodb.spi.dns.DnsException;
import com.mongodb.spi.dns.DnsWithResponseCodeException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gateway's own SRV and TXT lookups (ARCHITECTURE.md §1 A8) against a local DNS server: the answer formats the
 * driver parses, the TCP retry of a truncated answer, NXDOMAIN, a failing or silent server and the next one, a late
 * answer to another question, malformed answers, and {@code /etc/resolv.conf}.
 */
class SrvDnsTest {

  private DnsStub stub;

  @BeforeEach
  void start() throws Exception {
    stub = new DnsStub();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  private SrvDns dns() {
    return new SrvDns(() -> Nameservers.fixed(stub.address()));
  }

  @Test
  void srvAndTxtComeBackAsTheDriverReadsThem() throws Exception {
    stub.srv("_mongodb._tcp.cluster0.example.net", 0, 5, 27017, "node1.cluster0.example.net")
        .srv("_mongodb._tcp.cluster0.example.net", 1, 0, 27018, "node2.cluster0.example.net")
        .txt("cluster0.example.net", "authSource=admin&", "replicaSet=rs0");

    assertThat(dns().getResourceRecordData("_mongodb._tcp.cluster0.example.net", "SRV"))
        .containsExactly("0 5 27017 node1.cluster0.example.net.", "1 0 27018 node2.cluster0.example.net.");
    assertThat(dns().getResourceRecordData("cluster0.example.net", "TXT"))
        .containsExactly("authSource=admin&replicaSet=rs0");
  }

  @Test
  void theDriverResolvesAConnectionStringThroughIt() {
    stub.srv("_mongodb._tcp.rs.example.net", 0, 0, 27017, "a.rs.example.net")
        .txt("rs.example.net", "replicaSet=rs0&authSource=admin");

    ConnectionString cs = new ConnectionString("mongodb+srv://rs.example.net/orders", dns());

    assertThat(cs.getRequiredReplicaSetName()).isEqualTo("rs0");
    assertThat(cs.getSslEnabled()).isTrue();
    assertThat(cs.getHosts()).containsExactly("rs.example.net");
  }

  @Test
  void aMissingNameIsNxdomainAndTheDriverTakesItAsNoTxt() {
    assertThatThrownBy(() -> dns().getResourceRecordData("nothing.example.net", "TXT"))
        .isInstanceOfSatisfying(DnsWithResponseCodeException.class, e -> assertThat(e.getResponseCode())
            .isEqualTo(3));
    // No TXT record is fine; no SRV record is the driver's configuration error.
    ConnectionString cs = new ConnectionString("mongodb+srv://nothing.example.net/orders", dns());
    assertThat(cs.getRequiredReplicaSetName()).isNull();
  }

  @Test
  void aTruncatedAnswerIsAskedAgainOverTcp() throws Exception {
    stub.truncateUdp = true;
    stub.srv("_mongodb._tcp.big.example.net", 0, 0, 27017, "n1.big.example.net");

    assertThat(dns().getResourceRecordData("_mongodb._tcp.big.example.net", "SRV"))
        .containsExactly("0 0 27017 n1.big.example.net.");
    assertThat(stub.tcpQueries()).isEqualTo(1);
  }

  @Test
  void aLateAnswerToAnotherQuestionIsSkipped() throws Exception {
    stub.staleFirst = true;
    stub.txt("late.example.net", "replicaSet=rs1");

    assertThat(dns().getResourceRecordData("late.example.net", "TXT")).containsExactly("replicaSet=rs1");
  }

  @Test
  void aCnameIsFollowedInTheAnswer() throws Exception {
    stub.cname("_mongodb._tcp.alias.example.net", "_mongodb._tcp.real.example.net")
        .srv("_mongodb._tcp.real.example.net", 0, 0, 27017, "n1.alias.example.net");

    assertThat(dns().getResourceRecordData("_mongodb._tcp.alias.example.net", "SRV"))
        .containsExactly("0 0 27017 n1.alias.example.net.");
  }

  @Test
  void aFailingOrSilentServerGivesWayToTheNextOne() throws Exception {
    stub.txt("rs.example.net", "replicaSet=rs0");
    try (DnsStub failing = new DnsStub(); DnsStub silent = new DnsStub()) {
      failing.serverFailure = true;
      silent.silent = true;
      SrvDns dns = new SrvDns(() -> new Nameservers(List.of(failing.address(), silent.address(), stub.address()),
          Duration.ofMillis(300), 1, false, "test"));

      assertThat(dns.getResourceRecordData("rs.example.net", "TXT")).containsExactly("replicaSet=rs0");
      assertThat(failing.udpQueries()).isEqualTo(1);
      assertThat(silent.udpQueries()).isEqualTo(1);
    }
  }

  @Test
  void whenNoServerAnswersTheLookupFailsInBoundedTime() throws Exception {
    stub.silent = true;
    SrvDns dns = new SrvDns(() -> new Nameservers(List.of(stub.address()), Duration.ofMillis(200), 2, false, "t"));

    long t = System.nanoTime();
    assertThatThrownBy(() -> dns.getResourceRecordData("rs.example.net", "SRV")).isInstanceOf(DnsException.class)
        .hasMessageContaining("didn't answer");
    assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(2_000);
    assertThat(stub.udpQueries()).isEqualTo(2);
  }

  @Test
  void theDriverSaysWhichSrvNameHasNoRecords() {
    stub.txt("empty.example.net", "replicaSet=rs0");
    SrvDns dns = dns();
    var resolver = new com.mongodb.internal.dns.DefaultDnsResolver(dns);

    assertThatThrownBy(() -> resolver.resolveHostFromSrvRecords("empty.example.net", "mongodb", null))
        .isInstanceOf(MongoConfigurationException.class).hasMessageContaining("_mongodb._tcp.empty.example.net");
  }

  @Test
  void onlySrvAndTxtAreAskedFor() {
    assertThatThrownBy(() -> dns().getResourceRecordData("example.net", "A")).isInstanceOf(DnsException.class);
    assertThatThrownBy(() -> dns().getResourceRecordData("bad name.example.net", "TXT"))
        .isInstanceOf(DnsException.class);
  }

  // ── the wire format ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void hostileAnswersAreErrorsNotLoopsOrOverreads() {
    byte[] query = DnsMessage.query(7, "x.example.net", DnsMessage.TYPE_TXT);
    // The question, then an answer whose owner points at itself.
    byte[] loop = answer(query, new byte[] {(byte) 0xC0, (byte) query.length});
    byte[] past = answer(query, new byte[] {(byte) 0xC0, (byte) 0xFF});
    byte[] shortRdata = answer(query, new byte[] {(byte) 0xC0, 12, 0, 16, 0, 1, 0, 0, 0, 60, 0, 50, 3, 'a'});

    assertThatThrownBy(() -> DnsMessage.parse(loop, loop.length, 7, "x.example.net", DnsMessage.TYPE_TXT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> DnsMessage.parse(past, past.length, 7, "x.example.net", DnsMessage.TYPE_TXT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> DnsMessage.parse(shortRdata, shortRdata.length, 7, "x.example.net",
        DnsMessage.TYPE_TXT)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> DnsMessage.parse(query, query.length, 7, "x.example.net", DnsMessage.TYPE_TXT))
        .isInstanceOf(DnsMessage.NotOurs.class);
    byte[] other = answer(query, new byte[0]);
    assertThatThrownBy(() -> DnsMessage.parse(other, other.length, 8, "x.example.net", DnsMessage.TYPE_TXT))
        .isInstanceOf(DnsMessage.NotOurs.class);
    assertThatThrownBy(() -> DnsMessage.parse(other, other.length, 7, "y.example.net", DnsMessage.TYPE_TXT))
        .isInstanceOf(DnsMessage.NotOurs.class);
  }

  /** {@code query} turned into an answer with one record built from {@code rr} (owner onwards). */
  private static byte[] answer(byte[] query, byte[] rr) {
    byte[] out = java.util.Arrays.copyOf(query, query.length + rr.length);
    out[2] = (byte) 0x81;
    out[3] = (byte) 0x80;
    out[7] = (byte) (rr.length > 0 ? 1 : 0);
    System.arraycopy(rr, 0, out, query.length, rr.length);
    return out;
  }

  // ── resolv.conf ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void resolvConfServersOptionsAndDefaults() {
    Nameservers ns = Nameservers.parse("""
        # generated by NetworkManager
        search corp.example
        nameserver 10.0.0.2
        nameserver fe80::1%1
        nameserver dns.example.net
        nameserver 999.1.1.1
        nameserver 10.0.0.3
        nameserver 10.0.0.4
        options timeout:9 attempts:5 rotate
        """, "test");

    assertThat(ns.servers()).extracting(InetSocketAddress::getPort).containsOnly(53);
    assertThat(ns.servers()).extracting(a -> a.getAddress().getHostAddress())
        .containsExactly("10.0.0.2", "fe80:0:0:0:0:0:0:1%1", "10.0.0.3");
    assertThat(ns.timeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(ns.attempts()).isEqualTo(2);
    assertThat(Nameservers.parse("search x\n", "empty").servers()).extracting(a -> a.getAddress().getHostAddress())
        .containsExactly("127.0.0.1");
    assertThat(Nameservers.literal("dns.example.net")).isNull();
    assertThat(Nameservers.literal("0x7f.1")).isNull();
  }
}
