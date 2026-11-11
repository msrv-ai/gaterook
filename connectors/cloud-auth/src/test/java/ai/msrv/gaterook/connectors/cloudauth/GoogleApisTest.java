package ai.msrv.gaterook.connectors.cloudauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/**
 * Where a Google token may go (ARCHITECTURE.md §7.6): Google's own API hosts over HTTPS, and with a test token endpoint
 * (whose tokens are good for a test API only) any address. A Firebase Realtime Database has a predicate of its own,
 * for its two domains only.
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
        "file:///etc/hosts", "https://acme-default-rtdb.firebaseio.com/.json",
        "https://acme.europe-west1.firebasedatabase.app/.json"}) {
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

  // ── Firebase Realtime Database ──────────────────────────────────────────────────────────────────────────────

  @Test
  void aRealtimeDatabaseIsOneLabelUnderFirebaseioOrALabelAndARegionUnderFirebasedatabase() {
    for (String url : new String[] {"https://acme-default-rtdb.firebaseio.com", "https://acme.firebaseio.com/",
        "https://ACME-Prod.FirebaseIO.com:443/users/alice.json?print=silent", "https://a.firebaseio.com",
        "https://0acme.firebaseio.com", "https://acme-eu.europe-west1.firebasedatabase.app",
        "https://x1.asia-southeast1.firebasedatabase.app/.json?shallow=true",
        "https://acme.northamerica-northeast1.firebasedatabase.app", "https://acme.us-east4.firebasedatabase.app",
        "HTTPS://acme.firebaseio.com", "https://" + "a".repeat(63) + ".firebaseio.com"}) {
      assertThat(GoogleApis.realtimeDatabase(URI.create(url))).as(url).isTrue();
    }
  }

  @Test
  void nothingElseIsARealtimeDatabase() {
    for (String url : new String[] {
        // Not HTTPS, another port, user info.
        "http://acme.firebaseio.com", "https://acme.firebaseio.com:8443", "https://acme.firebaseio.com:80",
        "https://user@acme.firebaseio.com", "https://user:pw@acme.firebaseio.com",
        "https://acme.firebaseio.com@attacker.example", "wss://acme.firebaseio.com",
        // Shallower, deeper or look-alike names.
        "https://firebaseio.com", "https://a.b.firebaseio.com", "https://acme.firebaseio.com.attacker.example",
        "https://acmefirebaseio.com", "https://acme-firebaseio.com", "https://acme.firebaseio.co",
        "https://acme.firebaseio.com.", "https://-acme.firebaseio.com", "https://acme-.firebaseio.com",
        "https://ac_me.firebaseio.com", "https://" + "a".repeat(64) + ".firebaseio.com",
        "https://acme.firebasedatabase.app", "https://firebasedatabase.app",
        "https://a.b.europe-west1.firebasedatabase.app", "https://acme.europe-west.firebasedatabase.app",
        "https://acme.europe_west1.firebasedatabase.app", "https://acme.europe-west1.firebasedatabase.app.example",
        "https://acme.europe-west1.firebasedatabase.application", "https://acme.1europe-west1.firebasedatabase.app",
        // Where people deploy code, and Google's API hosts.
        "https://acme.web.app", "https://acme.firebaseapp.com", "https://acme-default-rtdb.web.app",
        "https://acme.firebasestorage.app", "https://firestore.googleapis.com/v1",
        "https://firebasedatabase.googleapis.com/v1beta", "https://acme.a.run.app",
        // Addresses.
        "https://127.0.0.1/.json", "https://[::1]/.json", "http://127.0.0.1:9000/.json?ns=acme", "file:///etc/hosts"}) {
      assertThat(GoogleApis.realtimeDatabase(URI.create(url))).as(url).isFalse();
    }
    assertThat(GoogleApis.realtimeDatabase(null)).isFalse();
    // A host Java can't parse as a server name has no host.
    assertThat(GoogleApis.realtimeDatabase(URI.create("https://ac%6de.firebaseio.com"))).isFalse();
  }

  @Test
  void databaseTokensGoToDatabaseHostsOnlyAndApiTokensToApiHostsOnly() {
    URI database = URI.create("https://acme-default-rtdb.firebaseio.com/users.json");
    URI api = URI.create("https://firestore.googleapis.com/v1/projects/acme/databases/(default)/documents");
    URI local = URI.create("http://127.0.0.1:9000/users.json");
    try (CloudCredentials production = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
        CloudCredentials test = new CloudCredentials(new CloudEndpoints(URI.create("http://127.0.0.1:9/token"), null,
            null, null, null, null), Clock.systemUTC())) {
      assertThat(production.sendsGoogleTokensToRealtimeDatabase(database)).isTrue();
      assertThat(production.sendsGoogleTokensToRealtimeDatabase(api)).isFalse();
      assertThat(production.sendsGoogleTokensToRealtimeDatabase(local)).isFalse();
      assertThat(production.sendsGoogleTokensToRealtimeDatabase(URI.create("https://acme.web.app/users.json")))
          .isFalse();
      assertThat(production.sendsGoogleTokensTo(database)).as("not one of Google's API hosts").isFalse();
      assertThat(test.sendsGoogleTokensToRealtimeDatabase(local)).isTrue();
    }
  }
}
