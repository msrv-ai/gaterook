package ai.msrv.gaterook.pluginhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Name resolution while egress calls run: each call's private ranges bound what its names may resolve to. */
class GuardedResolverProviderTest {

  private static final InetAddress PRIVATE = InetAddress.ofLiteral("10.0.0.5");
  private static final InetAddress PUBLIC = InetAddress.ofLiteral("93.184.216.34");
  private static final List<AddressRules.Cidr> TEN = List.of(AddressRules.Cidr.parse("10.0.0.0/8"));

  @Test
  void aStricterConcurrentCallOnTheSameNameNarrowsItsResolution() throws Exception {
    // A dev connection that may reach 10/8 and a prod connection that may not, on the same name at the same time.
    AutoCloseable dev = GuardedResolverProvider.grant("shared-a.example", TEN);
    AutoCloseable prod = GuardedResolverProvider.grant("Shared-A.example", List.of());
    assertThat(GuardedResolverProvider.passes("shared-a.example", PRIVATE)).isFalse();
    assertThat(GuardedResolverProvider.passes("shared-a.example", PUBLIC)).isTrue();
    prod.close();
    assertThat(GuardedResolverProvider.passes("shared-a.example", PRIVATE)).isTrue();
    dev.close();
  }

  @Test
  void aCallGetsItsOwnRangesOnceAnEarlierCallOnTheNameEnds() throws Exception {
    AutoCloseable prod = GuardedResolverProvider.grant("shared-b.example", List.of());
    AutoCloseable dev = GuardedResolverProvider.grant("shared-b.example", TEN);
    assertThat(GuardedResolverProvider.passes("shared-b.example", PRIVATE)).isFalse();
    prod.close();
    // Only the dev call is left: its own ranges, not the prod call's, judge the name now.
    assertThat(GuardedResolverProvider.passes("shared-b.example", PRIVATE)).isTrue();
    dev.close();
  }

  @Test
  void aHandleClosesOnlyItsOwnGrant() throws Exception {
    AutoCloseable first = GuardedResolverProvider.grant("shared-c.example", TEN);
    AutoCloseable second = GuardedResolverProvider.grant("shared-c.example", TEN);
    first.close();
    first.close();
    // The second request still holds the name: 10/8 only, not whatever a lookup outside egress may get.
    assertThat(GuardedResolverProvider.passes("shared-c.example", PRIVATE)).isTrue();
    assertThat(GuardedResolverProvider.passes("shared-c.example", InetAddress.ofLiteral("192.168.1.1"))).isFalse();
    second.close();
    assertThat(GuardedResolverProvider.passes("shared-c.example", InetAddress.ofLiteral("192.168.1.1"))).isTrue();
  }

  @Test
  void aRequestToACorePortNeverResolvesToLoopback() throws Exception {
    List<AddressRules.Cidr> local = List.of(AddressRules.Cidr.parse("127.0.0.0/8"), AddressRules.Cidr.parse("::1/128"),
        AddressRules.Cidr.parse("10.0.0.0/8"));
    try (AutoCloseable other = GuardedResolverProvider.grant("core.example", local);
        AutoCloseable toCorePort = GuardedResolverProvider.grant("core.example", local, true)) {
      assertThat(GuardedResolverProvider.passes("core.example", InetAddress.ofLiteral("127.0.0.1"))).isFalse();
      assertThat(GuardedResolverProvider.passes("core.example", InetAddress.ofLiteral("::1"))).isFalse();
      assertThat(GuardedResolverProvider.passes("core.example", InetAddress.ofLiteral("::ffff:7f00:1"))).isFalse();
      assertThat(GuardedResolverProvider.passes("core.example", InetAddress.ofLiteral("64:ff9b::7f00:1"))).isFalse();
      assertThat(GuardedResolverProvider.passes("core.example", PRIVATE)).isTrue();
    }
  }

  @Test
  void aLookupKeepsOnlyTheAddressesItsRequestsAllow() throws Exception {
    Map<String, List<InetAddress>> dns = Map.of(
        "mixed.example", List.of(PUBLIC, PRIVATE),
        "nat64.example", List.of(InetAddress.ofLiteral("64:ff9b::a9fe:a9fe")));
    InetAddressResolver resolver = GuardedResolverProvider.guard(new InetAddressResolver() {
      @Override
      public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
        List<InetAddress> found = dns.get(host);
        if (found == null) {
          throw new UnknownHostException(host);
        }
        return found.stream();
      }

      @Override
      public String lookupByAddress(byte[] addr) throws UnknownHostException {
        throw new UnknownHostException();
      }
    });
    InetAddressResolver.LookupPolicy any = InetAddressResolver.LookupPolicy.of(
        InetAddressResolver.LookupPolicy.IPV4 | InetAddressResolver.LookupPolicy.IPV6);

    try (AutoCloseable g = GuardedResolverProvider.grant("mixed.example", List.of())) {
      assertThat(resolver.lookupByName("mixed.example", any)).containsExactly(PUBLIC);
    }
    assertThat(resolver.lookupByName("mixed.example", any)).containsExactly(PUBLIC, PRIVATE);
    assertThatThrownBy(() -> resolver.lookupByName("nat64.example", any)).isInstanceOf(UnknownHostException.class);
    assertThatThrownBy(() -> resolver.lookupByName("Metadata.Google.Internal.", any))
        .isInstanceOf(UnknownHostException.class).hasMessageContaining("cloud metadata");
  }

  @Test
  void neverReachableAddressesStayClosedWhateverTheGrant() throws Exception {
    List<AddressRules.Cidr> everything = List.of(AddressRules.Cidr.parse("0.0.0.0/0"),
        AddressRules.Cidr.parse("::/0"));
    try (AutoCloseable g = GuardedResolverProvider.grant("wide.example", everything)) {
      assertThat(GuardedResolverProvider.passes("wide.example", InetAddress.ofLiteral("169.254.169.254"))).isFalse();
      assertThat(GuardedResolverProvider.passes("wide.example", InetAddress.ofLiteral("64:ff9b::a9fe:a9fe")))
          .isFalse();
      assertThat(GuardedResolverProvider.passes("wide.example", PRIVATE)).isTrue();
    }
    // A lookup no egress call made (a plugin's own client): metadata never, the rest as before.
    assertThat(GuardedResolverProvider.passes("other.example", InetAddress.ofLiteral("::169.254.169.254")))
        .isFalse();
    assertThat(GuardedResolverProvider.passes("other.example", PUBLIC)).isTrue();
  }
}
