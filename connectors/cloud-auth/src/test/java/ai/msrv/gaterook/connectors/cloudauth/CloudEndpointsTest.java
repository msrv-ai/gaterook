package ai.msrv.gaterook.connectors.cloudauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Test endpoints of the cloud APIs connectors call ({@code gateway.cloud.endpoints}). */
class CloudEndpointsTest {

  @Test
  void parsesKeyUrlPairs() {
    Map<String, URI> services = CloudEndpoints.parseServices(
        " gcp.logging=http://127.0.0.1:4567 , aws.logs=http://127.0.0.1:4568/ ,");

    assertThat(services).containsExactly(Map.entry("gcp.logging", URI.create("http://127.0.0.1:4567")),
        Map.entry("aws.logs", URI.create("http://127.0.0.1:4568/")));
    assertThat(CloudEndpoints.parseServices(null)).isEmpty();
    assertThat(CloudEndpoints.parseServices("")).isEmpty();
    assertThatThrownBy(() -> CloudEndpoints.parseServices("gcp.logging")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aServiceOverrideIsATestEndpointLikeTheCredentialOnes() {
    CloudEndpoints e = new CloudEndpoints(null, null, null, null, null, null,
        Map.of("gcp.logging", URI.create("http://127.0.0.1:4567")));

    assertThat(e.overridden()).isTrue();
    assertThat(e.service("gcp.logging")).isEqualTo(URI.create("http://127.0.0.1:4567"));
    assertThat(e.service("aws.logs")).isNull();
    assertThat(CloudEndpoints.PRODUCTION.overridden()).isFalse();
    assertThat(CloudEndpoints.PRODUCTION.service("gcp.logging")).isNull();
    try (CloudCredentials cloud = new CloudCredentials(e, Clock.systemUTC())) {
      assertThat(cloud.endpoints()).isSameAs(e);
    }
  }

  @Test
  void refusesKeysAndUrlsItCantUse() {
    assertThatThrownBy(() -> new CloudEndpoints(null, null, null, null, null, null,
        Map.of("logging", URI.create("http://127.0.0.1:1")))).hasMessageContaining("Not a cloud service key");
    assertThatThrownBy(() -> new CloudEndpoints(null, null, null, null, null, null,
        Map.of("gcp.logging", URI.create("file:///etc/passwd")))).hasMessageContaining("http(s)");
  }
}
