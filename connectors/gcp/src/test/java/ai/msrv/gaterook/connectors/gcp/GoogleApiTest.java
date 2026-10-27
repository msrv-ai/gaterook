package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The last check before a request leaves: a Google API path is the gateway's own, so it never walks with dot segments
 * or hides them behind percent-encoding — whatever a caller put together.
 */
class GoogleApiTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "https://monitoring.googleapis.com/v3/projects/acme-stage/metricDescriptors/run.googleapis.com/request_count",
      "https://cloudasset.googleapis.com/v1/projects/acme-stage:searchAllResources?query=name%3A%22..%22",
      "https://run.googleapis.com/v2/projects/acme-stage/locations/europe-west1/services/api",
      "http://127.0.0.1:4711/v3/projects/acme-stage/timeSeries?filter=a"})
  void theGatewaysOwnPathsPass(String uri) {
    assertThatCode(() -> GoogleApi.requirePlainPath(URI.create(uri))).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "https://monitoring.googleapis.com/v3/projects/acme-stage/metricDescriptors/run.googleapis.com/../../../x",
      "https://monitoring.googleapis.com/v3/projects/acme-stage/./metricDescriptors/x",
      "https://monitoring.googleapis.com/v3/projects/acme-stage/metricDescriptors/x/..",
      "https://monitoring.googleapis.com/v3/projects//metricDescriptors/x",
      "https://monitoring.googleapis.com/v3/projects/acme-stage/%2e%2e/x",
      "https://monitoring.googleapis.com/v3/projects/acme-stage/a%2Fb"})
  void aPathThatWalksIsRefused(String uri) {
    assertThatThrownBy(() -> GoogleApi.requirePlainPath(URI.create(uri)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
