package com.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.cloudauth.GoogleApis;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A Google token goes to Google's own API hosts only (ARCHITECTURE.md §7.6): a server configured to sign in with
 * Google Cloud credentials at another address gets no token, when its session opens and on any request, whatever its
 * authorization server is.
 */
class GoogleRequestCredentialsTest {

  private static final String TOKEN = "ya29.a-test-token-of-google-credentials";

  @Test
  void aServerOutsideGooglesApiHostsGetsNoCredentialsAtAll() {
    try (CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC())) {
      for (String url : List.of("https://mcp.example.test/mcp", "http://bigquery.googleapis.com/mcp",
          "https://my-service-abc123.a.run.app/mcp")) {
        var cfg = Fixtures.cfg("cn_lookalike", "lookalike-mcp", Env.DEV, 1, Fixtures.object("""
            {"transport":"http","url":"%s","auth":{"type":"google","credentials":{"source":"adc"}}}""".formatted(url)));
        assertThatThrownBy(() -> GoogleRequestCredentials.of(cloud, cfg, new Fixtures.Secrets(), t -> { }))
            .as(url).isInstanceOf(ConnectorException.class)
            .hasMessageContaining("Google Cloud credentials go only to Google's own servers");
      }
    }
  }

  @Test
  void aRequestToAnotherHostThanGooglesCarriesNoToken() throws Exception {
    List<CharSequence> tainted = new ArrayList<>();
    GoogleCredentials google = GoogleCredentials.create(new AccessToken(TOKEN,
        Date.from(Instant.now().plusSeconds(3600))));
    GoogleRequestCredentials credentials = new GoogleRequestCredentials(google, tainted::add, GoogleApis::host,
        "bigquery-mcp");

    assertThat(credentials.headers("POST", URI.create("https://bigquery.googleapis.com/mcp"), new byte[0]))
        .containsEntry("Authorization", "Bearer " + TOKEN);
    assertThatThrownBy(() -> credentials.headers("POST", URI.create("https://messages.example.test/mcp"), new byte[0]))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("not to messages.example.test");
    assertThat(tainted).hasSize(1);
  }
}
