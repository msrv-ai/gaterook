package com.gaterook.pluginhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The host's address rules judge every address as the core's {@code AddressPolicy} does (its table, the same rows). */
class AddressRulesTest {

  private static final List<AddressRules.Cidr> OPEN = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8",
      "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "198.18.0.0/15", "fc00::/7", "fec0::/10").stream()
      .map(AddressRules.Cidr::parse).toList();

  @ParameterizedTest(name = "{0}: {1} / allowed private {2}")
  @CsvSource({
      // ── public ──
      "8.8.8.8,                 PUBLIC,          PUBLIC",
      "2606:4700:4700::1111,    PUBLIC,          PUBLIC",
      "172.32.0.1,              PUBLIC,          PUBLIC",
      "100.128.0.1,             PUBLIC,          PUBLIC",
      "2a00:1450:4001:82a::200e, PUBLIC,         PUBLIC",
      // ── never: link-local and cloud metadata ──
      "169.254.169.254,         BLOCKED,         BLOCKED",
      "fe80::1,                 BLOCKED,         BLOCKED",
      "fd00:ec2::254,           BLOCKED,         BLOCKED",
      "fd20:ce::254,            BLOCKED,         BLOCKED",
      "100.100.100.200,         BLOCKED,         BLOCKED",
      "192.0.0.192,             BLOCKED,         BLOCKED",
      // ── never: this network, unspecified, multicast, reserved, Teredo, documentation, discard ──
      "0.0.0.0,                 BLOCKED,         BLOCKED",
      "::,                      BLOCKED,         BLOCKED",
      "224.0.0.1,               BLOCKED,         BLOCKED",
      "ff02::1,                 BLOCKED,         BLOCKED",
      "255.255.255.255,         BLOCKED,         BLOCKED",
      "2001:0:4136:e378::1,     BLOCKED,         BLOCKED",
      "192.0.2.10,              BLOCKED,         BLOCKED",
      "198.51.100.7,            BLOCKED,         BLOCKED",
      "203.0.113.9,             BLOCKED,         BLOCKED",
      "2001:db8::1,             BLOCKED,         BLOCKED",
      "100::1,                  BLOCKED,         BLOCKED",
      // ── private: only where the policy allows it ──
      "127.0.0.1,               PRIVATE,         ALLOWED_PRIVATE",
      "::1,                     PRIVATE,         ALLOWED_PRIVATE",
      "10.30.4.12,              PRIVATE,         ALLOWED_PRIVATE",
      "172.31.255.255,          PRIVATE,         ALLOWED_PRIVATE",
      "192.168.1.1,             PRIVATE,         ALLOWED_PRIVATE",
      "100.64.0.1,              PRIVATE,         ALLOWED_PRIVATE",
      "198.19.255.254,          PRIVATE,         ALLOWED_PRIVATE",
      "fd12:3456:789a::1,       PRIVATE,         ALLOWED_PRIVATE",
      "fec0::1,                 PRIVATE,         ALLOWED_PRIVATE",
      // ── embedded IPv4: judged by the address inside ──
      "::ffff:127.0.0.2,        PRIVATE,         ALLOWED_PRIVATE",
      "::ffff:169.254.169.254,  BLOCKED,         BLOCKED",
      "::ffff:8.8.8.8,          PUBLIC,          PUBLIC",
      "::127.0.0.1,             PRIVATE,         ALLOWED_PRIVATE",
      "::169.254.169.254,       BLOCKED,         BLOCKED",
      "64:ff9b::a9fe:a9fe,      BLOCKED,         BLOCKED",
      "64:ff9b::7f00:1,         PRIVATE,         ALLOWED_PRIVATE",
      "64:ff9b::a00:1,          PRIVATE,         ALLOWED_PRIVATE",
      "64:ff9b::808:808,        PUBLIC,          PUBLIC",
      // The local-use NAT64 prefix may translate to private IPv4: private, opened only by its own range.
      "64:ff9b:1::a00:1,        PRIVATE,         PRIVATE",
      "2002:a9fe:a9fe::1,       BLOCKED,         BLOCKED",
      "2002:7f00:1::1,          PRIVATE,         ALLOWED_PRIVATE",
      "2002:c0a8:101::1,        PRIVATE,         ALLOWED_PRIVATE",
      "2002:808:808::1,         PUBLIC,          PUBLIC",
  })
  void judgesAddressesAsTheCoreDoes(String literal, String strict, String open) {
    InetAddress address = InetAddress.ofLiteral(literal);
    assertThat(judge(address, List.of())).as("no private range allowed").isEqualTo(strict);
    assertThat(judge(address, OPEN)).as("private ranges allowed").isEqualTo(open);
  }

  @ParameterizedTest(name = "{0} is loopback: {1}")
  @CsvSource({
      "127.0.0.1, true", "127.1.2.3, true", "::1, true", "::127.0.0.1, true", "64:ff9b::7f00:1, true",
      "2002:7f00:1::1, true", "10.0.0.1, false", "::, false", "fe80::1, false", "64:ff9b:1::7f00:1, false",
  })
  void knowsLoopbackInEveryForm(String literal, boolean loopback) {
    assertThat(AddressRules.loopback(InetAddress.ofLiteral(literal))).isEqualTo(loopback);
  }

  @Test
  void anIpv4MappedAddressFromAResolverIsJudgedByItsIpv4Address() throws Exception {
    // Java turns a mapped literal into an Inet4Address; a resolver's answer may still be an Inet6Address.
    InetAddress metadata = Inet6Address.getByAddress(null, mapped(169, 254, 169, 254), -1);
    InetAddress loopback = Inet6Address.getByAddress(null, mapped(127, 0, 0, 1), -1);
    assertThat(metadata).isInstanceOf(Inet6Address.class);
    assertThat(judge(metadata, OPEN)).isEqualTo("BLOCKED");
    assertThat(judge(loopback, List.of())).isEqualTo("PRIVATE");
    assertThat(judge(loopback, OPEN)).isEqualTo("ALLOWED_PRIVATE");
    assertThat(AddressRules.loopback(loopback)).isTrue();
  }

  @Test
  void noRangeOpensWhatIsNeverReachable() {
    List<AddressRules.Cidr> everything = List.of(AddressRules.Cidr.parse("0.0.0.0/0"), AddressRules.Cidr.parse("::/0"));
    for (String literal : List.of("169.254.169.254", "100.100.100.200", "fd20:ce::254", "64:ff9b::a9fe:a9fe",
        "::169.254.169.254", "2002:a9fe:a9fe::1", "2001:0:4136:e378::1")) {
      assertThat(AddressRules.allowed(InetAddress.ofLiteral(literal), everything)).as(literal).isFalse();
    }
  }

  private static String judge(InetAddress address, List<AddressRules.Cidr> allowedPrivate) {
    if (AddressRules.forbidden(address)) {
      assertThat(AddressRules.allowed(address, OPEN)).isFalse();
      return "BLOCKED";
    }
    if (!AddressRules.privateAddress(address)) {
      assertThat(AddressRules.allowed(address, allowedPrivate)).isTrue();
      return "PUBLIC";
    }
    return AddressRules.allowed(address, allowedPrivate) ? "ALLOWED_PRIVATE" : "PRIVATE";
  }

  private static byte[] mapped(int a, int b, int c, int d) {
    byte[] bytes = new byte[16];
    bytes[10] = (byte) 0xff;
    bytes[11] = (byte) 0xff;
    bytes[12] = (byte) a;
    bytes[13] = (byte) b;
    bytes[14] = (byte) c;
    bytes[15] = (byte) d;
    return bytes;
  }
}
