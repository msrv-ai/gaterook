package ai.msrv.gaterook.connectors.cloudauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/**
 * Where a Google token may go (ARCHITECTURE.md §7.6): Google's own API hosts over HTTPS, and with a test token endpoint
 * (whose tokens are good for a test API only) any address.
 */
class GoogleApisTest {

  @Test
  void googlesApiHostsOverHttpsAreGoogles() {
    for (String url : new String[] {"https://bigquery.googleapis.com/mcp", "https://bigtableadmin.googleapis.com/mcp",
        "https://BIGQUERY.googleapis.com:443/mcp", "https://us-central1-aiplatform.googleapis.com/v1"}) {
      assertThat(GoogleApis.host(URI.create(url))).as(url).isTrue();
    }
  }

  @Test
  void everyOtherAddressIsNot() {
    for (String url : new String[] {"http://bigquery.googleapis.com/mcp", "https://bigquery.googleapis.com:8443/mcp",
        "https://user@bigquery.googleapis.com/mcp", "https://googleapis.com/mcp", "https://googleapis.com.example/mcp",
        "https://bigquery.googleapis.com.example/mcp", "https://xgoogleapis.com/mcp",
        "https://-bad.googleapis.com/mcp", "https://my-service-abc123.a.run.app/mcp",
        "https://region-project.cloudfunctions.net/mcp", "https://mcp.example.test/mcp", "http://127.0.0.1:8080/mcp",
        "file:///etc/hosts"}) {
      assertThat(GoogleApis.host(URI.create(url))).as(url).isFalse();
    }
    assertThat(GoogleApis.host(null)).isFalse();
  }

  @Test
  void aTestTokenEndpointsTokensMayGoToATestApiAnywhere() {
    URI local = URI.create("http://127.0.0.1:8080/mcp");
    try (CloudCredentials production = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
        CloudCredentials test = new CloudCredentials(new CloudEndpoints(URI.create("http://127.0.0.1:9/token"), null,
            null, null, null, null), Clock.systemUTC())) {
      assertThat(production.sendsGoogleTokensTo(local)).isFalse();
      assertThat(production.sendsGoogleTokensTo(URI.create("https://bigquery.googleapis.com/mcp"))).isTrue();
      assertThat(test.sendsGoogleTokensTo(local)).isTrue();
    }
  }
}
