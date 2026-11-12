package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help for the OpenSearch and Elasticsearch forms: AWS request signing from an Amazon OpenSearch address, a server on
 * this computer asked once without credentials (and a self-signed one told to paste its CA, never asked), and once the
 * draft signs in, who it is and what it may see; for the legacy Logs form also its index patterns and log fields.
 */
class SearchSuggestionsTest {

  private static final String PASSWORD = "cnry-search-suggest-" + Long.toHexString(System.nanoTime());
  private static final String OPENSEARCH = "{\"name\":\"node-1\",\"version\":{\"distribution\":\"opensearch\","
      + "\"number\":\"2.19.1\"},\"tagline\":\"The OpenSearch Project: https://opensearch.org/\"}";
  private static final String ELASTICSEARCH = "{\"name\":\"es01\",\"version\":{\"number\":\"8.15.0\","
      + "\"build_flavor\":\"default\"},\"tagline\":\"You Know, for Search\"}";

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private volatile Function<RecordedRequest, MockResponse> answer = r -> json(404, "{}");
  private CloudCredentials cloud;
  private CloudLocal local;

  @TempDir
  Path home;

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        return answer.apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Path config = Files.writeString(home.resolve("aws-config"), """
        [default]
        region = eu-west-1
        [profile search-dev]
        region = eu-central-1
        """);
    CloudEndpoints endpoints = new CloudEndpoints(null, null, null, null, config, null);
    cloud = new CloudCredentials(endpoints, Clock.systemUTC());
    local = new CloudLocal(endpoints, k -> null, home);
  }

  @AfterEach
  void stop() throws IOException {
    cloud.close();
    server.close();
  }

  private Suggestions suggest(String type, String json, Suggestions.Scope scope) throws Exception {
    try (Connector connector = type.equals(LogsConnector.TYPE) ? new LogsConnector(cloud, Clock.systemUTC(), local)
        : new BackendConnector(type, cloud, Clock.systemUTC(), local)) {
      return connector.suggest(draft(type, json), new TestSecrets().with("/auth/secret", PASSWORD), TestCtx.of(),
          scope);
    }
  }

  private static ConnectionConfig draft(String type, String json) {
    return new ConnectionConfig("draft-1", "draft", type, Env.DEV, 0, TestCtx.JSON.readTree(json),
        Set.of("/auth/secret"), null);
  }

  private String local() {
    return "http://127.0.0.1:" + server.getPort();
  }

  @Test
  void anAmazonOpenSearchAddressSignsWithAwsInItsRegion() throws Exception {
    Suggestions domain = suggest("opensearch", """
        {"backend":"opensearch","url":"https://search-logs-a1b2c3.eu-central-1.es.amazonaws.com",
         "auth":{"mode":"basic"}}""", Suggestions.Scope.LOCAL);
    Suggestions serverless = suggest("opensearch", """
        {"backend":"opensearch","url":"https://x7k2p9q1.us-east-1.aoss.amazonaws.com","auth":{"mode":"basic"}}""",
        Suggestions.Scope.LOCAL);
    Suggestions elastic = suggest("elasticsearch", """
        {"backend":"elasticsearch","url":"https://search-logs-a1b2c3.eu-central-1.es.amazonaws.com",
         "auth":{"mode":"basic"}}""", Suggestions.Scope.LOCAL);

    assertThat(domain.fields().get("/auth/mode").value()).isEqualTo("awsSigV4");
    assertThat(domain.fields().get("/auth/awsRegion").value()).isEqualTo("eu-central-1");
    assertThat(domain.fields().get("/auth/awsRegion").source()).isEqualTo("the URL");
    assertThat(domain.fields().get("/auth/awsService").value()).isEqualTo("es");
    assertThat(domain.fields().get("/auth/awsCredentials/profile").options()).extracting(Option::value)
        .containsExactly("default", "search-dev");
    assertThat(domain.notes()).singleElement().asString().startsWith("An Amazon OpenSearch Service domain in "
        + "eu-central-1");
    assertThat(serverless.fields().get("/auth/awsService").value()).isEqualTo("aoss");
    assertThat(serverless.fields().get("/auth/awsRegion").value()).isEqualTo("us-east-1");
    assertThat(elastic.fields()).as("Elasticsearch signs no AWS requests").doesNotContainKeys("/auth/mode",
        "/auth/awsRegion");
    assertThat(requests).isEmpty();
  }

  @Test
  void requestSigningWithoutAnAwsAddressTakesTheProfilesRegion() throws Exception {
    Suggestions s = suggest("opensearch", """
        {"backend":"opensearch","url":"https://search.example.test",
         "auth":{"mode":"awsSigV4","awsCredentials":{"source":"profile","profile":"search-dev"}}}""",
        Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/auth/awsRegion").value()).isEqualTo("eu-central-1");
    assertThat(s.fields().get("/auth/awsRegion").source()).isEqualTo("~/.aws/config");
    assertThat(s.fields().get("/auth/awsCredentials/profile").value()).as("the person named one").isNull();
  }

  @Test
  void aServerOnThisComputerWithoutSecurityNeedsNoCredentialsAndNeverSeesThePassword() throws Exception {
    answer = r -> r.getUrl().encodedPath().equals("/") ? json(200, OPENSEARCH) : json(404, "{}");

    Suggestions s = suggest("opensearch", "{\"backend\":\"opensearch\",\"url\":\"" + local() + "\",\"auth\":{"
        + "\"mode\":\"basic\",\"username\":\"admin\"}}", Suggestions.Scope.LOCAL);
    Suggestions other = suggest("elasticsearch", "{\"backend\":\"elasticsearch\",\"url\":\"" + local() + "\"}",
        Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/auth/mode").value()).isEqualTo("none");
    assertThat(s.notes()).containsExactly("OpenSearch 2.19.1 on 127.0.0.1:" + server.getPort() + " answers without "
        + "credentials.");
    assertThat(other.notes()).contains("This server is OpenSearch: use the OpenSearch connector, whose tools speak "
        + "its API.");
    assertThat(requests).hasSize(2).allSatisfy(r -> assertThat(r.getHeaders().get("Authorization")).isNull());
  }

  @Test
  void aServerThatAsksForCredentialsIsOfferedItsUsualUser() throws Exception {
    answer = r -> json(401, "{\"error\":{\"root_cause\":[{\"type\":\"security_exception\",\"reason\":\"missing "
        + "authentication credentials for REST request [/]\"}]},\"status\":401}");

    Suggestions s = suggest("elasticsearch", "{\"backend\":\"elasticsearch\",\"url\":\"" + local() + "\"}",
        Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/auth/username").value()).as("offered, not filled in").isNull();
    assertThat(s.fields().get("/auth/username").options()).extracting(Option::value).containsExactly("elastic");
    assertThat(s.notes()).containsExactly("127.0.0.1:" + server.getPort() + " asks for credentials.");
  }

  @Test
  void aSelfSignedElasticsearchIsToldToPasteItsCaAndAskedOnlyOnceItIsThere() throws Exception {
    HeldCertificate ca = new HeldCertificate.Builder().commonName("Elasticsearch security auto-configuration HTTP CA")
        .certificateAuthority(0).build();
    HeldCertificate leaf = new HeldCertificate.Builder().commonName("es01").addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1").signedBy(ca).build();
    try (MockWebServer tls = https(leaf, ca)) {
      tls.setDispatcher(new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
          return json(200, ELASTICSEARCH);
        }
      });
      tls.start(InetAddress.getByName("127.0.0.1"), 0);
      String url = "https://127.0.0.1:" + tls.getPort();

      Suggestions untrusted = suggest("elasticsearch", "{\"backend\":\"elasticsearch\",\"url\":\"" + url + "\"}",
          Suggestions.Scope.LOCAL);
      Suggestions signedIn = suggest("elasticsearch", "{\"backend\":\"elasticsearch\",\"url\":\"" + url + "\","
          + "\"auth\":{\"mode\":\"basic\",\"username\":\"elastic\"}}", Suggestions.Scope.SERVICE);

      String note = "127.0.0.1:" + tls.getPort() + " uses a certificate this computer doesn't trust: paste the CA "
          + "certificate (PEM) that signed it under Advanced settings. Elasticsearch writes its own to "
          + "config/certs/http_ca.crt.";
      assertThat(untrusted.notes()).containsExactly(note);
      assertThat(signedIn.notes()).containsExactly(note);
      assertThat(tls.getRequestCount()).as("never asked without a verified certificate").isZero();

      Suggestions trusted = suggest("elasticsearch", "{\"backend\":\"elasticsearch\",\"url\":\"" + url + "\","
          + "\"caCertificate\":" + TestCtx.JSON.writeValueAsString(ca.certificatePem()) + "}", Suggestions.Scope.LOCAL);
      assertThat(trusted.notes()).containsExactly("Elasticsearch 8.15.0 on 127.0.0.1:" + tls.getPort() + " answers "
          + "without credentials.");
      assertThat(tls.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  void aCertificateForAnotherNameIsANoteAndTestConnectionPointsAtTheCaField() throws Exception {
    HeldCertificate ca = new HeldCertificate.Builder().certificateAuthority(0).build();
    HeldCertificate leaf = new HeldCertificate.Builder().addSubjectAlternativeName("node-0.example.com").signedBy(ca)
        .build();
    try (MockWebServer tls = https(leaf, ca)) {
      tls.start(InetAddress.getByName("127.0.0.1"), 0);
      String url = "https://127.0.0.1:" + tls.getPort();

      Suggestions wrongName = suggest("opensearch", "{\"backend\":\"opensearch\",\"url\":\"" + url + "\","
          + "\"auth\":{\"mode\":\"none\"},\"caCertificate\":" + TestCtx.JSON.writeValueAsString(ca.certificatePem())
          + "}", Suggestions.Scope.LOCAL);
      TestResult test;
      try (BackendConnector connector = new BackendConnector("opensearch", cloud, Clock.systemUTC(), local)) {
        test = connector.test(draft("opensearch", "{\"backend\":\"opensearch\",\"url\":\"" + url + "\","
            + "\"auth\":{\"mode\":\"none\"}}"), new TestSecrets(), TestCtx.of());
      }

      assertThat(wrongName.notes()).containsExactly("The certificate of 127.0.0.1:" + tls.getPort() + " isn't "
          + "issued for 127.0.0.1: use the host name it was issued for in the URL.");
      assertThat(test.ok()).isFalse();
      assertThat(test.error().message()).contains("isn't trusted on this computer");
      assertThat(test.hints()).anySatisfy(h -> assertThat(h.field()).isEqualTo("/caCertificate"));
      assertThat(tls.getRequestCount()).isZero();
    }
  }

  @Test
  void signedInItNamesTheUserTheVersionAndIndexPatterns() throws Exception {
    answer = r -> switch (r.getUrl().encodedPath()) {
      case "/" -> json(200, OPENSEARCH);
      case "/_plugins/_security/api/account" -> json(200, "{\"user_name\":\"reader\",\"roles\":[\"logs_read\"]}");
      case "/_resolve/index/*" -> json(200, """
          {"indices":[{"name":"logs-app-2026.11.10","attributes":["open"]},
                      {"name":"logs-app-2026.11.11","attributes":["open"]},
                      {"name":"products","attributes":["open"]},
                      {"name":".ds-metrics-app-default-2026.11.11-000001","attributes":["open","hidden"],
                       "data_stream":"metrics-app-default"}],
           "aliases":[{"name":"logs","indices":["logs-app-2026.11.11"]}],
           "data_streams":[{"name":"metrics-app-default","timestamp_field":"@timestamp"}]}""");
      default -> json(404, "{}");
    };

    Suggestions s = suggest("opensearch", "{\"backend\":\"opensearch\",\"url\":\"" + local() + "\",\"auth\":{"
        + "\"mode\":\"basic\",\"username\":\"reader\"}}", Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("Signed in to OpenSearch 2.19.1 as reader: 3 indices, 1 alias and 1 data "
        + "stream (logs-app-*, logs, metrics-app-default, products).");
    assertThat(s.fields()).as("the full REST form has no index settings").isEmpty();
    assertThat(requests).allSatisfy(r -> {
      assertThat(r.getMethod()).isEqualTo("GET");
      assertThat(r.getHeaders().get("Authorization")).startsWith("Basic ");
    });
  }

  @Test
  void theLegacyLogFormGetsIndexPatternsAndTheFieldsOfItsMappings() throws Exception {
    answer = r -> switch (r.getUrl().encodedPath()) {
      case "/" -> json(200, OPENSEARCH);
      case "/_resolve/index/*" -> json(200, """
          {"indices":[{"name":"logs-app-2026.11.10"},{"name":"logs-app-2026.11.11"},{"name":"orders"}]}""");
      case "/logs-app-*/_field_caps" -> json(200, """
          {"fields":{"time":{"date":{"type":"date"}},"created_at":{"date":{"type":"date"}},
                     "msg":{"text":{"type":"text"}},"level":{"keyword":{"type":"keyword"}},
                     "service.name":{"keyword":{"type":"keyword"}},"_id":{"_id":{"type":"_id"}}}}""");
      default -> json(404, "{}");
    };
    String scoped = "{\"backend\":\"opensearch\",\"apiAccess\":\"logs\",\"url\":\"" + local() + "\","
        + "\"auth\":{\"mode\":\"basic\",\"username\":\"reader\"}";

    Suggestions s = suggest(LogsConnector.TYPE, scoped + ",\"indices\":[\"logs-app-*\"]}", Suggestions.Scope.SERVICE);
    Suggestions empty = suggest(LogsConnector.TYPE, scoped + "}", Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/indices").options()).extracting(Option::value).containsExactly("logs-app-*",
        "orders");
    assertThat(s.fields().get("/indices").options().getFirst().detail()).isEqualTo("2 indices");
    assertThat(s.fields().get("/timestampField").value()).isEqualTo("time");
    assertThat(s.fields().get("/timestampField").options()).extracting(Option::value)
        .containsExactly("time", "created_at");
    assertThat(s.fields().get("/messageField").value()).isEqualTo("msg");
    assertThat(s.fields().get("/severityField").value()).isEqualTo("level");
    assertThat(s.fields().get("/labelFields").options()).extracting(Option::value).containsExactly("service.name");
    assertThat(s.notes()).contains("logs-app-* has no date field named @timestamp: set Timestamp field to time.");
    // While Log index patterns is still empty (x-suggest), the patterns are listed and the log-like one is read.
    assertThat(empty.fields().get("/indices").options()).extracting(Option::value).contains("logs-app-*");
    assertThat(empty.fields().get("/timestampField").value()).isEqualTo("time");
  }

  @Test
  void collapsesDatedAndRolledOverIndicesIntoPatterns() {
    assertThat(IndexPatterns.pattern("logstash-2026.11.12")).isEqualTo("logstash-*");
    assertThat(IndexPatterns.pattern("filebeat-8.15.0-2026.11.12-000001")).isEqualTo("filebeat-8.15.0-*");
    assertThat(IndexPatterns.pattern("app-logs-202611")).isEqualTo("app-logs-*");
    assertThat(IndexPatterns.pattern("metrics_20261112")).isEqualTo("metrics_*");
    assertThat(IndexPatterns.pattern("events-2026-11-12t10")).isEqualTo("events-*");
    assertThat(IndexPatterns.pattern("my-index-000001")).isEqualTo("my-index-*");
    assertThat(IndexPatterns.pattern("products")).isEqualTo("products");
    assertThat(IndexPatterns.pattern("orders-2")).isEqualTo("orders-2");
    assertThat(IndexPatterns.pattern("logs-app-default")).isEqualTo("logs-app-default");
  }

  private static MockWebServer https(HeldCertificate leaf, HeldCertificate ca) {
    MockWebServer tls = new MockWebServer();
    tls.useHttps(new HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate()).build()
        .sslSocketFactory());
    return tls;
  }

  private static MockResponse json(int status, String body) {
    return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build();
  }
}
