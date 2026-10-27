package ai.msrv.gaterook.connectors.http;

import static ai.msrv.gaterook.connectors.http.Fixtures.call;
import static ai.msrv.gaterook.connectors.http.Fixtures.cfg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import okio.GzipSink;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Requests on the wire against MockWebServer over TLS with a private CA (report 10 §8.2): what reaches the server
 * (credentials added last, the approved bytes and nothing else), what the agent gets back, and the SSRF guard of the
 * core in front of every connection — the test policy allows only {@code 127.0.0.1/32} and names resolve through a
 * fake resolver.
 */
class HttpExecutionTest {

  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("GateRook test CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("api.test")
      .addSubjectAlternativeName("api.test").addSubjectAlternativeName("api2.test")
      .addSubjectAlternativeName("rebind.test").addSubjectAlternativeName("mixed.test")
      .addSubjectAlternativeName("127.0.0.1").signedBy(CA).build();

  private MockWebServer server;
  private Fixtures.Names names;
  private EgressClients clients;
  private GuardedEgress guard;
  private final HttpConnector connector = new HttpConnector();
  private final Fixtures.Secrets secrets = Fixtures.Secrets.of(HttpSettings.TOKEN, "tok-cnry-4f2a9c");

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF, CA.certificate()).build()
        .sslSocketFactory());
    server.start(Fixtures.ip("127.0.0.1"), 0);
    names = new Fixtures.Names().answer("api.test", "127.0.0.1");
    clients = Fixtures.clients(names, Set.of(7465, 7466));
    guard = Fixtures.guard(clients, "conn-1", "127.0.0.1/32");
  }

  @AfterEach
  void stop() {
    clients.close();
    server.close();
  }

  private ConnectionConfig billing(String extra) {
    return cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"https://api.test:" + server.getPort() + "/v1\"],"
        + "\"auth\":{\"type\":\"bearer\"},\"tls\":{\"caCertificates\":" + Fixtures.JSON.writeValueAsString(
        CA.certificatePem()) + "}" + (extra.isEmpty() ? "" : "," + extra) + "}");
  }

  private ToolResult get(ConnectionConfig cfg, String args) throws ConnectorException {
    return connector.execute(call("http_get", args), cfg, secrets, Fixtures.Context.of(guard));
  }

  @Test
  void aReadGetsJsonAndOnlyTheHeadersAnAgentMaySee() throws Exception {
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .addHeader("Set-Cookie", "session=abc").addHeader("X-Request-Id", "7f3k").addHeader("Server", "nginx")
        .body("{\"items\":[{\"id\":\"in_1\",\"total\":129.00}]}").build());

    ToolResult r = get(billing(""), "{\"connection\":\"billing-api\",\"path\":\"/invoices\",\"query\":{\"limit\":10},"
        + "\"headers\":{\"Accept\":\"application/json\"}}");

    JsonNode s = r.structuredContent();
    assertThat(r.isError()).isFalse();
    assertThat(s.path("status").asInt()).isEqualTo(200);
    assertThat(s.path("ok").asBoolean()).isTrue();
    assertThat(s.path("json").toString()).isEqualTo("{\"items\":[{\"id\":\"in_1\",\"total\":129.00}]}");
    assertThat(s.path("text").isNull()).isTrue();
    assertThat(s.path("headers").has("x-request-id")).isTrue();
    assertThat(s.path("headers").has("set-cookie")).isFalse();
    assertThat(s.path("headers").has("server")).isFalse();
    assertThat(s.path("headersRemoved").asInt()).isEqualTo(1);
    assertThat(s.path("url").asString()).isEqualTo("https://api.test:" + server.getPort() + "/v1/invoices?limit=10");
    assertThat(r.content().getFirst().text()).startsWith("GET https://api.test:" + server.getPort()
        + "/v1/invoices?limit=10 → 200 OK · application/json · 40 B · ").contains(" · 1 header removed")
        .contains("\n\n{\"items\"");

    RecordedRequest seen = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(seen.getMethod()).isEqualTo("GET");
    assertThat(seen.getTarget()).isEqualTo("/v1/invoices?limit=10");
    assertThat(seen.getHeaders().get("Authorization")).isEqualTo("Bearer tok-cnry-4f2a9c");
    assertThat(seen.getHeaders().get("User-Agent")).isEqualTo("GateRook/test");
    assertThat(seen.getHeaders().get("Accept")).isEqualTo("application/json");
    assertThat(seen.getHeaders().get("Cookie")).isNull();
  }

  @Test
  void anyStatusIsDataAndTextIsSanitized() throws Exception {
    server.enqueue(new MockResponse.Builder().code(404).addHeader("Content-Type", "text/plain; charset=utf-8")
        .body("not found\u001b[31m here‮").build());

    ToolResult r = get(billing(""), "{\"path\":\"/invoices/in_9\"}");

    assertThat(r.isError()).isFalse();
    assertThat(r.structuredContent().path("status").asInt()).isEqualTo(404);
    assertThat(r.structuredContent().path("ok").asBoolean()).isFalse();
    assertThat(r.structuredContent().path("text").asString()).doesNotContain("\u001b").doesNotContain("‮");
  }

  @Test
  void binaryIsNamedNotShown() throws Exception {
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "image/png")
        .body(new Buffer().write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3})).build());

    ToolResult r = get(billing(""), "{\"path\":\"/logo.png\"}");

    assertThat(r.structuredContent().path("binary").path("bytes").asInt()).isEqualTo(8);
    assertThat(r.structuredContent().path("binary").path("sha256").asString()).hasSize(64);
    assertThat(r.content().getFirst().text()).contains("Binary response (image/png, 8 B) is not shown.");
  }

  @Test
  void aLargeBodyAndAGzipBombStopAtTheLimit() throws Exception {
    Buffer big = new Buffer();
    byte[] chunk = new byte[64 * 1024];
    java.util.Arrays.fill(chunk, (byte) 'a');
    for (int i = 0; i < 80; i++) {
      big.write(chunk);
    }
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "text/plain").body(big).build());
    ToolResult large = get(billing(""), "{\"path\":\"/big\"}");
    assertThat(large.structuredContent().path("truncated").asBoolean()).isTrue();
    assertThat(large.structuredContent().path("truncatedReason").asString()).isEqualTo("size_limit");
    assertThat(large.structuredContent().path("bytes").asLong()).isEqualTo(512 * 1024);

    Buffer zipped = new Buffer();
    try (var gzip = Okio.buffer(new GzipSink(zipped))) {
      byte[] zeros = new byte[1024 * 1024];
      for (int i = 0; i < 20; i++) {
        gzip.write(zeros);
      }
    }
    long onTheWire = zipped.size();
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/octet-stream")
        .addHeader("Content-Encoding", "gzip").body(zipped).build());
    ToolResult bomb = get(billing(""), "{\"path\":\"/bomb\"}");
    assertThat(onTheWire).isLessThan(64 * 1024);
    assertThat(bomb.structuredContent().path("truncated").asBoolean()).isTrue();
    assertThat(bomb.structuredContent().path("bytes").asLong()).isEqualTo(512 * 1024);
  }

  @Test
  void anApprovedRequestSendsTheApprovedBytesNotTheArguments() throws Exception {
    ConnectionConfig cfg = billing("");
    String approvedArgs = "{\"method\":\"POST\",\"path\":\"/refunds\",\"headers\":{\"Idempotency-Key\":\"refund-48213\"},"
        + "\"json\":{\"order_id\":48213,\"amount\":1999,\"currency\":\"EUR\"}}";
    ActionPreview approved = connector.describeAction(call("http_request", approvedArgs), cfg);
    server.enqueue(new MockResponse.Builder().code(201).addHeader("Content-Type", "application/json")
        .body("{\"id\":\"re_1\",\"status\":\"pending\"}").build());

    // The arguments the agent sends again differ; the person approved the payload, and that is what runs.
    ToolResult r = connector.execute(call("http_request", approvedArgs.replace("1999", "999999")), cfg, secrets,
        Fixtures.Context.approved(guard, approved.payload()));

    assertThat(r.structuredContent().path("status").asInt()).isEqualTo(201);
    RecordedRequest seen = server.takeRequest(5, TimeUnit.SECONDS);
    String shown = approved.text().substring(approved.text().indexOf("\n\n") + 2);
    assertThat(seen.getBody().utf8()).isEqualTo(shown).contains("\"amount\": 1999");
    assertThat(seen.getHeaders().get("Content-Type")).isEqualTo("application/json");
    assertThat(seen.getHeaders().get("Idempotency-Key")).isEqualTo("refund-48213");
    assertThat(seen.getHeaders().get("Authorization")).isEqualTo("Bearer tok-cnry-4f2a9c");
  }

  @Test
  void anApprovalRunsOnlyOnTheConnectionItWasApprovedFor() {
    ActionPreview approved = connector.describeAction(call("http_request", "{\"method\":\"POST\",\"path\":"
        + "\"/refunds\",\"json\":{\"amount\":100}}"), billing(""));

    // Other credentials than the person saw: the run is refused, nothing is sent.
    ConnectionConfig rekeyed = cfg("billing-api", Env.DEV, billing("").config().toString()
        .replace("{\"type\":\"bearer\"}", "{\"type\":\"apiKeyHeader\",\"headerName\":\"X-Api-Key\"}"));
    assertThatThrownBy(() -> connector.execute(call("http_request", "{}"), rekeyed, secrets,
        Fixtures.Context.approved(guard, approved.payload())))
        .hasMessage("The credentials of the connection changed after approval. Not executed.");

    // A base URL taken away after approval takes the request with it.
    ConnectionConfig moved = cfg("billing-api", Env.DEV, billing("").config().toString()
        .replace("/v1\"]", "/v2\"]"));
    assertThatThrownBy(() -> connector.execute(call("http_request", "{}"), moved, secrets,
        Fixtures.Context.approved(guard, approved.payload())))
        .hasMessageContaining("is no longer under");

    // A payload edited in the database is no request the person approved.
    ObjectNode tampered = (ObjectNode) approved.payload().deepCopy();
    tampered.put("url", "https://api.test:" + server.getPort() + "/v1/../admin");
    assertThatThrownBy(() -> connector.execute(call("http_request", "{}"), billing(""), secrets,
        Fixtures.Context.approved(guard, tampered))).isInstanceOf(ConnectorException.class);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void aChangeWithoutApprovalNeverLeaves() {
    assertThatThrownBy(() -> connector.execute(call("http_request", "{\"method\":\"DELETE\",\"path\":\"/refunds/re_1\"}"),
        billing(""), secrets, Fixtures.Context.of(guard)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("runs only after a person approves it");
        });
    ConnectionConfig mutating = billing("\"mutatingGetPaths\":[\"/export/run\"]");
    assertThatThrownBy(() -> get(mutating, "{\"path\":\"/export/run\"}")).isInstanceOf(ConnectorException.class);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void anApprovedPostIsSentExactlyOnceWhenTheConnectionDrops() throws Exception {
    ConnectionConfig cfg = billing("");
    server.enqueue(new MockResponse.Builder().code(200).body("warm").build());
    // The server reads the whole request, then the socket closes before a byte of the answer.
    server.enqueue(new MockResponse.Builder().onResponseStart(new SocketEffect.CloseSocket()).build());
    server.enqueue(new MockResponse.Builder().code(201).body("a second refund").build());
    get(cfg, "{\"path\":\"/warm\"}"); // a pooled keep-alive connection, the one that drops

    ActionPreview approved = connector.describeAction(call("http_request", "{\"method\":\"POST\",\"path\":"
        + "\"/refunds\",\"json\":{\"amount\":100}}"), cfg);
    assertThatThrownBy(() -> connector.execute(call("http_request", "{}"), cfg, secrets,
        Fixtures.Context.approved(guard, approved.payload())))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
          assertThat(e.getMessage()).contains("dropped after the POST was sent. Check before retrying.");
        });

    assertThat(server.getRequestCount()).isEqualTo(2);
    server.takeRequest(5, TimeUnit.SECONDS);
    RecordedRequest post = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(post.getMethod()).isEqualTo("POST");
    Thread.sleep(300);
    assertThat(server.getRequestCount()).as("never sent again").isEqualTo(2);
  }

  /**
   * A 503 with {@code Retry-After: 0} makes OkHttp send the same request again by itself, whatever
   * {@code retryOnConnectionFailure} says: the approved request goes out once and the 503 is the answer.
   */
  @Test
  void anApprovedRequestIsSentOnceWhenTheServerAsksForItAgainAtOnce() throws Exception {
    ConnectionConfig cfg = billing("");
    for (String args : new String[] {"{\"method\":\"POST\",\"path\":\"/refunds\",\"json\":{\"amount\":100}}",
        "{\"method\":\"DELETE\",\"path\":\"/refunds/re_1\"}"}) {
      server.enqueue(new MockResponse.Builder().code(503).addHeader("Retry-After", "0")
          .addHeader("Content-Type", "text/plain").body("busy").build());
      server.enqueue(new MockResponse.Builder().code(201).body("a second refund").build());
      int before = server.getRequestCount();
      ActionPreview approved = connector.describeAction(call("http_request", args), cfg);

      ToolResult r = connector.execute(call("http_request", "{}"), cfg, secrets,
          Fixtures.Context.approved(guard, approved.payload()));

      assertThat(r.structuredContent().path("status").asInt()).as(args).isEqualTo(503);
      assertThat(r.structuredContent().path("headers").path("retry-after").asString()).as(args).isEqualTo("0");
      Thread.sleep(200);
      assertThat(server.getRequestCount() - before).as(args + ": sent once").isEqualTo(1);
      server.takeRequest(5, TimeUnit.SECONDS);
      get(cfg, "{\"path\":\"/drain\"}"); // the round's unused second answer
      server.takeRequest(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void aPostWithoutAnAnswerInTimeIsAnUnknownOutcomeAndAGetATimeout() throws Exception {
    ConnectionConfig cfg = billing("");
    server.enqueue(new MockResponse.Builder().onResponseStart(SocketEffect.Stall.INSTANCE).build());
    server.enqueue(new MockResponse.Builder().onResponseStart(SocketEffect.Stall.INSTANCE).build());
    ActionPreview approved = connector.describeAction(call("http_request", "{\"method\":\"POST\",\"path\":"
        + "\"/refunds\",\"json\":{\"amount\":100}}"), cfg);
    Fixtures.Context oneSecond = Fixtures.Context.approved(guard, approved.payload())
        .withLimits(Fixtures.limits(512 * 1024, 256 * 1024, java.time.Duration.ofSeconds(1)));

    assertThatThrownBy(() -> connector.execute(call("http_request", "{}"), cfg, secrets, oneSecond))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
          assertThat(e.getMessage()).isEqualTo("Outcome unknown: api.test:" + server.getPort() + " didn't answer in "
              + "time after the POST was sent. Check before retrying.");
        });
    Fixtures.Context read = Fixtures.Context.of(guard)
        .withLimits(Fixtures.limits(512 * 1024, 256 * 1024, java.time.Duration.ofSeconds(1)));
    assertThatThrownBy(() -> connector.execute(call("http_get", "{\"path\":\"/slow\"}"), cfg, secrets, read))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  // ── SSRF ────────────────────────────────────────────────────────────────────────────────────────────────────

  private void assertDenied(ConnectionConfig cfg, String path, String text) {
    assertThatThrownBy(() -> get(cfg, "{\"path\":\"" + path + "\"}"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains(text);
        });
  }

  private ConnectionConfig at(String baseUrl) {
    return cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"" + baseUrl + "\"],\"auth\":{\"type\":\"bearer\"},"
        + "\"tls\":{\"caCertificates\":" + Fixtures.JSON.writeValueAsString(CA.certificatePem()) + "}}");
  }

  @Test
  void theGuardStopsEveryAddressThePolicyDoesntAllow() {
    int port = server.getPort();
    names.answer("api2.test", "127.0.0.2");
    names.answer("mixed.test", "127.0.0.1", "10.1.2.3");
    assertDenied(at("https://api2.test:" + port), "/", "api2.test resolves to 127.0.0.2, a private address");
    assertDenied(at("https://mixed.test:" + port), "/", "10.1.2.3");
    // Literals never ask the resolver: the socket guard stops them at connect.
    assertDenied(at("https://127.0.0.2:" + port), "/", "127.0.0.2 is a private address");
    assertDenied(at("https://[::ffff:7f00:2]:" + port), "/", "127.0.0.2");
    // Numeric and octal spellings of 127.0.0.1 are no host a connection may name.
    for (String host : new String[] {"2130706433", "0177.0.0.1", "127.1"}) {
      assertThatThrownBy(() -> get(at("https://" + host + ":" + port), "{\"path\":\"/\"}")).as(host)
          .isInstanceOf(ConnectorException.class);
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void metadataAndTheCoreItselfAreNeverReachableWhateverThePolicySays() {
    GuardedEgress everything = Fixtures.guard(clients, "conn-2", "127.0.0.0/8", "169.254.0.0/16");
    assertThatThrownBy(() -> connector.execute(call("http_get", "{\"path\":\"/latest/meta-data/\"}"),
        at("https://169.254.169.254"), secrets, Fixtures.Context.of(everything)))
        .hasMessageContaining("never connects there");

    EgressClients asCore = Fixtures.clients(names, Set.of(server.getPort()));
    try {
      GuardedEgress core = Fixtures.guard(asCore, "conn-3", "127.0.0.0/8");
      assertThatThrownBy(() -> connector.execute(call("http_get", "{\"path\":\"/\"}"), billing(""), secrets,
          Fixtures.Context.of(core))).hasMessageContaining("is GateRook itself");
    } finally {
      asCore.close();
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void dnsRebindingIsCaughtOnTheNextConnection() throws Exception {
    names.answer("rebind.test", "127.0.0.1").answer("rebind.test", "10.1.2.3");
    ConnectionConfig cfg = at("https://rebind.test:" + server.getPort());
    server.enqueue(new MockResponse.Builder().code(200).body("first").build());

    assertThat(get(cfg, "{\"path\":\"/\"}").structuredContent().path("status").asInt()).isEqualTo(200);
    clients.evict("conn-1");
    assertDenied(cfg, "/", "10.1.2.3");
  }

  @Test
  void aRedirectIsDataUnlessTheConnectionFollowsItOnItsOwnOrigin() throws Exception {
    server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "http://169.254.169.254/latest/")
        .build());
    ToolResult notFollowed = get(billing(""), "{\"path\":\"/go\"}");
    assertThat(notFollowed.structuredContent().path("status").asInt()).isEqualTo(302);
    assertThat(notFollowed.structuredContent().path("redirect").asString()).isEqualTo("http://169.254.169.254/latest/");
    assertThat(notFollowed.content().getFirst().text()).contains("not followed");
    assertThat(server.getRequestCount()).isEqualTo(1);

    ConnectionConfig follows = billing("\"allowedPaths\":[\"/go\",\"/invoices/**\"],\"advanced\":{\"followRedirects\":"
        + "\"same-origin\"}");
    server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "/v1/invoices/in_1").build());
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body("{\"id\":\"in_1\"}").build());
    ToolResult followed = get(follows, "{\"path\":\"/go\"}");
    assertThat(followed.structuredContent().path("status").asInt()).isEqualTo(200);
    assertThat(followed.structuredContent().path("redirects").asInt()).isEqualTo(1);
    assertThat(followed.structuredContent().path("url").asString()).endsWith("/v1/invoices/in_1");

    for (String location : new String[] {"/v1/admin", "https://api2.test:" + server.getPort() + "/v1/invoices/x",
        "http://169.254.169.254/latest/"}) {
      server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", location).build());
      ToolResult stopped = get(follows, "{\"path\":\"/go\"}");
      assertThat(stopped.structuredContent().path("status").asInt()).as(location).isEqualTo(302);
      assertThat(stopped.content().getFirst().text()).as(location).contains(": not followed:");
    }
    assertThat(server.getRequestCount()).isEqualTo(6);
  }

  // ── Credentials ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void eachKindOfCredentialIsAddedByTheGateway() throws Exception {
    int port = server.getPort();
    String tls = ",\"tls\":{\"caCertificates\":" + Fixtures.JSON.writeValueAsString(CA.certificatePem()) + "}";
    ConnectionConfig basic = cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"https://api.test:" + port + "\"],"
        + "\"auth\":{\"type\":\"basic\",\"username\":\"gw\"}" + tls + "}");
    ConnectionConfig header = cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"https://api.test:" + port + "\"],"
        + "\"auth\":{\"type\":\"apiKeyHeader\",\"headerName\":\"X-Api-Key\"}" + tls + "}");
    ConnectionConfig query = cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"https://api.test:" + port + "\"],"
        + "\"auth\":{\"type\":\"apiKeyQuery\",\"queryName\":\"key\"}" + tls + "}");
    Fixtures.Secrets all = new Fixtures.Secrets().with(HttpSettings.PASSWORD, "pw-cnry").with(HttpSettings.API_KEY,
        "key-cnry");
    for (int i = 0; i < 3; i++) {
      server.enqueue(new MockResponse.Builder().code(200).body("ok").build());
    }
    Fixtures.Context ctx = Fixtures.Context.of(guard);
    connector.execute(call("http_get", "{\"path\":\"/a\"}"), basic, all, ctx);
    connector.execute(call("http_get", "{\"path\":\"/b\"}"), header, all, Fixtures.Context.of(guard));
    ToolResult q = connector.execute(call("http_get", "{\"path\":\"/c\"}"), query, all, Fixtures.Context.of(guard));

    String encoded = java.util.Base64.getEncoder().encodeToString("gw:pw-cnry".getBytes(StandardCharsets.UTF_8));
    assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeaders().get("Authorization")).isEqualTo("Basic "
        + encoded);
    assertThat(ctx.taints).contains(encoded);
    assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeaders().get("X-Api-Key")).isEqualTo("key-cnry");
    assertThat(server.takeRequest(5, TimeUnit.SECONDS).getTarget()).isEqualTo("/c?key=key-cnry");
    assertThat(q.structuredContent().path("url").asString()).endsWith("/c?key=‹masked›");
    assertThat(q.content().getFirst().text()).doesNotContain("key-cnry");
  }

  /**
   * A stored value with a line break (a pasted token) can't go in a header: OkHttp refuses it with a message that
   * quotes the value of every header but Authorization and Cookie. The call names the field, never the value.
   */
  @Test
  void aCredentialHttpCantCarryIsNamedNeverQuoted() {
    int port = server.getPort();
    String tls = ",\"tls\":{\"caCertificates\":" + Fixtures.JSON.writeValueAsString(CA.certificatePem()) + "}";
    ConnectionConfig header = cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"https://api.test:" + port + "\"],"
        + "\"auth\":{\"type\":\"apiKeyHeader\",\"headerName\":\"X-Api-Key\"}" + tls + "}");
    ConnectionConfig hooks = cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"https://api.test:" + port + "\"],"
        + "\"auth\":{\"type\":\"none\"},\"secretHeaders\":[{\"name\":\"X-Webhook-Secret\",\"value\":{\"$secret\":"
        + "\"s\"}}]" + tls + "}");
    Fixtures.Secrets pasted = new Fixtures.Secrets().with(HttpSettings.API_KEY, "key-cnry-77\n")
        .with("/secretHeaders/0/value", "hook-cnry-88\r\nX-Evil: 1");

    for (ConnectionConfig cfg : List.of(header, hooks)) {
      assertThatThrownBy(() -> connector.execute(call("http_get", "{\"path\":\"/a\"}"), cfg, pasted,
          Fixtures.Context.of(guard)))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
            assertThat(e.getMessage()).contains("line break").doesNotContain("cnry");
          });
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void aSecretHeaderThatDoesntResolveStopsOnlyTheMethodsThatNeedIt() throws Exception {
    ConnectionConfig hooks = billing("\"secretHeaders\":[{\"name\":\"X-Webhook-Secret\",\"value\":{\"$secret\":"
        + "\"s\"},\"methods\":[\"POST\"]}]");
    secrets.unresolvable.add("/secretHeaders/0/value");
    server.enqueue(new MockResponse.Builder().code(200).body("ok").build());
    assertThat(get(hooks, "{\"path\":\"/x\"}").structuredContent().path("status").asInt()).isEqualTo(200);

    ActionPreview post = connector.describeAction(call("http_request", "{\"method\":\"POST\",\"path\":\"/x\","
        + "\"json\":{}}"), hooks);
    assertThatThrownBy(() -> connector.execute(call("http_request", "{}"), hooks, secrets,
        Fixtures.Context.approved(guard, post.payload())))
        .hasMessageContaining("AWS Secrets Manager isn't connected");
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  // ── Plain HTTP ──────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void plainHttpGoesOnlyToAllowedPrivateAddresses() throws Exception {
    MockWebServer plain = new MockWebServer();
    plain.start(Fixtures.ip("127.0.0.1"), 0);
    try {
      names.answer("public.test", "93.184.216.34");
      ConnectionConfig off = cfg("internal", Env.DEV, "{\"baseUrls\":[\"http://api.test:" + plain.getPort() + "\"],"
          + "\"auth\":{\"type\":\"none\"}}");
      assertThatThrownBy(() -> get(off, "{\"path\":\"/x\"}")).hasMessageContaining("plain http://");

      ConnectionConfig on = cfg("internal", Env.DEV, "{\"baseUrls\":[\"http://api.test:" + plain.getPort() + "\"],"
          + "\"auth\":{\"type\":\"none\"},\"advanced\":{\"allowPlainHttp\":true}}");
      plain.enqueue(new MockResponse.Builder().code(200).body("ok").build());
      assertThat(get(on, "{\"path\":\"/x\"}").structuredContent().path("status").asInt()).isEqualTo(200);

      ConnectionConfig toPublic = cfg("internal", Env.DEV, "{\"baseUrls\":[\"http://public.test:" + plain.getPort()
          + "\"],\"auth\":{\"type\":\"none\"},\"advanced\":{\"allowPlainHttp\":true}}");
      assertThatThrownBy(() -> get(toPublic, "{\"path\":\"/x\"}"))
          .hasMessageContaining("Plain http:// goes only to a private address allowed in Policies");
      assertThat(plain.getRequestCount()).isEqualTo(1);
    } finally {
      plain.close();
    }
  }
}
