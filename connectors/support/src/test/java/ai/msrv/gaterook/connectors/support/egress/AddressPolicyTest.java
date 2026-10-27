package ai.msrv.gaterook.connectors.support.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connectors.support.egress.AddressPolicy.Reason;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** The address table of report 10 §6.1, IPv4 and IPv6, with and without allowed private ranges. */
class AddressPolicyTest {

  private static final List<Integer> CORE_PORTS = List.of(7465, 7466);

  private final AddressPolicy strict = AddressPolicy.publicOnly(CORE_PORTS);
  private final AddressPolicy open = AddressPolicy.builder().corePorts(CORE_PORTS)
      .allowPrivate("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
          "198.18.0.0/15", "fc00::/7", "fec0::/10")
      .build();

  @ParameterizedTest(name = "{0} port {1}: {2} / allowed private {3}")
  @CsvSource({
      // ── public ──
      "8.8.8.8,                 443,  PUBLIC,          PUBLIC",
      "1.1.1.1,                 -1,   PUBLIC,          PUBLIC",
      "2606:4700:4700::1111,    443,  PUBLIC,          PUBLIC",
      "151.101.1.69,            80,   PUBLIC,          PUBLIC",
      "172.32.0.1,              443,  PUBLIC,          PUBLIC",
      "172.15.255.255,          443,  PUBLIC,          PUBLIC",
      "100.128.0.1,             443,  PUBLIC,          PUBLIC",
      "192.169.0.1,             443,  PUBLIC,          PUBLIC",
      "11.0.0.1,                443,  PUBLIC,          PUBLIC",
      "198.20.0.1,              443,  PUBLIC,          PUBLIC",
      "2a00:1450:4001:82a::200e, 443, PUBLIC,          PUBLIC",
      // ── always blocked: link-local and cloud metadata ──
      "169.254.169.254,         80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "169.254.170.2,           80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "169.254.170.23,          80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "169.254.0.1,             80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "fe80::1,                 80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "fe80::1%2,               80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "febf::1,                 80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "fd00:ec2::254,           80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "fd00:ec2::23,            80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "fd20:ce::254,            80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "100.100.100.200,         80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      // ── always blocked: this network, unspecified, multicast, reserved, broadcast, Teredo, documentation ──
      "0.0.0.0,                 80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "0.1.2.3,                 80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "::,                      80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "224.0.0.1,               80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "239.255.255.250,         80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "ff02::1,                 80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "240.0.0.1,               80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "255.255.255.255,         80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "2001:0:4136:e378::1,     80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "192.0.0.192,             80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "192.0.2.10,              80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "198.51.100.7,            80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "203.0.113.9,             80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "2001:db8::1,             80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "100::1,                  80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      // ── private: blocked unless allowed ──
      "127.0.0.1,               8080, PRIVATE,         ALLOWED_PRIVATE",
      "127.255.255.254,         8080, PRIVATE,         ALLOWED_PRIVATE",
      "::1,                     8080, PRIVATE,         ALLOWED_PRIVATE",
      "10.0.0.1,                5432, PRIVATE,         ALLOWED_PRIVATE",
      "10.30.4.12,              443,  PRIVATE,         ALLOWED_PRIVATE",
      "172.16.0.1,              443,  PRIVATE,         ALLOWED_PRIVATE",
      "172.31.255.255,          443,  PRIVATE,         ALLOWED_PRIVATE",
      "192.168.1.1,             443,  PRIVATE,         ALLOWED_PRIVATE",
      "100.64.0.1,              443,  PRIVATE,         ALLOWED_PRIVATE",
      "100.127.255.254,         443,  PRIVATE,         ALLOWED_PRIVATE",
      "198.18.0.1,              443,  PRIVATE,         ALLOWED_PRIVATE",
      "198.19.255.254,          443,  PRIVATE,         ALLOWED_PRIVATE",
      "fc00::1,                 443,  PRIVATE,         ALLOWED_PRIVATE",
      "fd12:3456:789a::1,       443,  PRIVATE,         ALLOWED_PRIVATE",
      "fec0::1,                 443,  PRIVATE,         ALLOWED_PRIVATE",
      // ── the core's own ports on loopback: never ──
      "127.0.0.1,               7465, CORE_PORT,       CORE_PORT",
      "127.0.0.1,               7466, CORE_PORT,       CORE_PORT",
      "127.1.2.3,               7465, CORE_PORT,       CORE_PORT",
      "::1,                     7465, CORE_PORT,       CORE_PORT",
      "10.0.0.1,                7465, PRIVATE,         ALLOWED_PRIVATE",
      // ── embedded IPv4: judged by what they carry ──
      "::ffff:127.0.0.2,        80,   PRIVATE,         ALLOWED_PRIVATE",
      "::ffff:7f00:2,           80,   PRIVATE,         ALLOWED_PRIVATE",
      "::ffff:169.254.169.254,  80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "::ffff:8.8.8.8,          80,   PUBLIC,          PUBLIC",
      "::127.0.0.1,             80,   PRIVATE,         ALLOWED_PRIVATE",
      "::169.254.169.254,       80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "64:ff9b::a9fe:a9fe,      80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "64:ff9b::7f00:1,         80,   PRIVATE,         ALLOWED_PRIVATE",
      "64:ff9b::7f00:1,         7465, CORE_PORT,       CORE_PORT",
      "64:ff9b::808:808,        80,   PUBLIC,          PUBLIC",
      "64:ff9b::a00:1,          80,   PRIVATE,         ALLOWED_PRIVATE",
      // The local-use NAT64 prefix (RFC 8215) may translate to private IPv4: private, opened only by its own range.
      "64:ff9b:1::a00:1,        80,   PRIVATE,         PRIVATE",
      "64:ff9b:1:ffff::1,       443,  PRIVATE,         PRIVATE",
      "2002:a9fe:a9fe::1,       80,   ALWAYS_BLOCKED,  ALWAYS_BLOCKED",
      "2002:7f00:1::1,          80,   PRIVATE,         ALLOWED_PRIVATE",
      "2002:c0a8:101::1,        80,   PRIVATE,         ALLOWED_PRIVATE",
      "2002:808:808::1,         80,   PUBLIC,          PUBLIC",
  })
  void judgesAddresses(String literal, int port, Reason strictReason, Reason openReason) {
    InetAddress address = AddressPolicy.canonicalIp(literal).orElseThrow();
    assertThat(strict.check(address, port).reason()).as("strict policy").isEqualTo(strictReason);
    assertThat(open.check(address, port).reason()).as("policy with private ranges").isEqualTo(openReason);
  }

  @ParameterizedTest(name = "host {0}")
  @CsvSource({
      "2130706433", "0177.0.0.1", "127.1", "0x7f.1", "0x7f000001", "127.0.0.01", "1.2.3", "017700000001",
      "256.1.1.1", "localhost.2130706433", "[::ffff:zz]",
  })
  void refusesNonCanonicalNumericHosts(String host) {
    assertThat(AddressPolicy.checkHostSyntax(host)).get().extracting(AddressPolicy.Verdict::reason)
        .isEqualTo(Reason.NON_CANONICAL);
  }

  @ParameterizedTest(name = "host {0}")
  @CsvSource({"metadata.google.internal", "METADATA.GOOGLE.INTERNAL.", "metadata.goog", "instance-data.ec2.internal"})
  void refusesMetadataNames(String host) {
    assertThat(AddressPolicy.checkHostSyntax(host)).get().extracting(AddressPolicy.Verdict::reason)
        .isEqualTo(Reason.BLOCKED_NAME);
  }

  @ParameterizedTest(name = "host {0}")
  @CsvSource({"billing.stage.acme.dev", "10.30.4.12", "[2606:4700:4700::1111]", "beef.dead", "api-2.example.com",
      "0.example.com", "localhost"})
  void letsNamesAndCanonicalLiteralsThrough(String host) {
    assertThat(AddressPolicy.checkHostSyntax(host)).isEmpty();
  }

  @Test
  void verdictsExplainWhatToDo() {
    InetAddress address = AddressPolicy.canonicalIp("10.30.4.12").orElseThrow();
    AddressPolicy.Verdict verdict = strict.check("billing.stage.acme.dev", address, 443);
    assertThat(verdict.text()).isEqualTo("billing.stage.acme.dev resolves to 10.30.4.12, a private address. "
        + "Allow 10.30.4.12/32 in this environment's policy to reach it.");
    assertThat(verdict.suggestedRange()).contains(Cidr.parse("10.30.4.12/32"));
    assertThat(strict.check(AddressPolicy.canonicalIp("169.254.169.254").orElseThrow(), 80).text())
        .isEqualTo("169.254.169.254 is a link-local, cloud metadata or reserved address. "
            + "MCP Gateway never connects there.");
    assertThat(strict.check(AddressPolicy.canonicalIp("127.0.0.1").orElseThrow(), 7465).text())
        .isEqualTo("127.0.0.1:7465 is MCP Gateway itself. Connections to it are never allowed.");
  }

  @Test
  void allowedRangesNeverOpenTheMetadataInsideThem() {
    AddressPolicy wide = AddressPolicy.builder().allowPrivate("fc00::/7", "169.254.0.0/16").build();
    assertThat(wide.check(AddressPolicy.canonicalIp("fd20:ce::254").orElseThrow(), 80).reason())
        .isEqualTo(Reason.ALWAYS_BLOCKED);
    assertThat(wide.check(AddressPolicy.canonicalIp("169.254.169.254").orElseThrow(), 80).reason())
        .isEqualTo(Reason.ALWAYS_BLOCKED);
  }

  @Test
  void downloadsNeverReachLoopback() {
    AddressPolicy downloads = AddressPolicy.builder().allowPrivate("127.0.0.0/8", "10.0.0.0/8").neverLoopback().build();
    assertThat(downloads.check(AddressPolicy.canonicalIp("127.0.0.1").orElseThrow(), 8080).reason())
        .isEqualTo(Reason.LOOPBACK);
    assertThat(downloads.check(AddressPolicy.canonicalIp("::1").orElseThrow(), 8080).reason())
        .isEqualTo(Reason.LOOPBACK);
    assertThat(downloads.check(AddressPolicy.canonicalIp("10.1.2.3").orElseThrow(), 8080).reason())
        .isEqualTo(Reason.ALLOWED_PRIVATE);
  }

  @Test
  void cidrParsingIsCanonical() {
    assertThat(Cidr.parse("10.30.0.7/16")).hasToString("10.30.0.0/16");
    assertThat(Cidr.parse("fd00:EC2::254/32")).hasToString("fd00:ec2::/32");
    assertThat(Cidr.parse("2001:db8:0:0:1:0:0:1")).hasToString("2001:db8::1:0:0:1/128");
    assertThat(Cidr.parse("::1")).hasToString("::1/128");
    assertThatThrownBy(() -> Cidr.parse("0177.0.0.1/32")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Cidr.parse("example.com/24")).isInstanceOf(IllegalArgumentException.class);
    assertThat(Cidr.parse("10.0.0.0/8").contains(AddressPolicy.canonicalIp("10.255.0.1").orElseThrow())).isTrue();
    assertThat(Cidr.parse("10.0.0.0/8").contains(AddressPolicy.canonicalIp("::ffff:10.0.0.1").orElseThrow())).isTrue();
    assertThat(Cidr.parse("10.0.0.0/8").contains(AddressPolicy.canonicalIp("fc00::1").orElseThrow())).isFalse();
  }
}
