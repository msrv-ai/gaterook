package ai.msrv.gaterook.connectors.http;

import static ai.msrv.gaterook.connectors.http.Fixtures.cfg;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Help for the HTTP API form. Local, without network: a well-known API's sign-in by its host (left alone once a token
 * was typed), the usual key header and parameter names, Allow plain HTTP for http://. Service: the API's own OpenAPI
 * document, read without credentials from a usual place, for its servers, path globs and sign-in; an untrusted
 * certificate is a note and nothing is sent.
 */
class HttpSuggestionsTest {

  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("Acme internal CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("api.test")
      .addSubjectAlternativeName("api.test").signedBy(CA).build();

  private static final String DOCUMENT = """
      {"openapi":"3.0.3","info":{"title":"Billing","version":"2"},
       "servers":[{"url":"%s"},{"url":"https://sandbox.api.test/{version}","variables":{"version":{"default":"v1"}}}],
       "security":[{"key":[]}],
       "components":{"securitySchemes":{"key":{"type":"apiKey","in":"header","name":"X-Billing-Key"}}},
       "paths":{"/invoices":{"get":{"responses":{"200":{"description":"ok"}}}},
                "/invoices/{id}":{"get":{"responses":{"200":{"description":"ok"}}}},
                "/refunds":{"post":{"responses":{"201":{"description":"ok"}}}},
                "/{tenant}/reports":{"get":{"responses":{"200":{"description":"ok"}}}}}}""";

  private MockWebServer server;
  private EgressClients clients;
  private GuardedEgress guard;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final HttpConnector connector = new HttpConnector();

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF, CA.certificate()).build()
        .sslSocketFactory());
    server.start(Fixtures.ip("127.0.0.1"), 0);
    clients = Fixtures.clients(new Fixtures.Names().answer("api.test", "127.0.0.1"), Set.of(7465));
    guard = Fixtures.guard(clients, "conn-1", "127.0.0.1/32");
  }

  @AfterEach
  void stop() {
    clients.close();
    server.close();
  }

  private String base() {
    return "https://api.test:" + server.getPort() + "/v1";
  }

  private void serve(String path, String body) {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        return request.getUrl().encodedPath().equals(path)
            ? new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            : new MockResponse.Builder().code(404).build();
      }
    });
  }

  private ConnectionConfig trusted(String extra) {
    return cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"" + base() + "\"],\"auth\":{\"type\":\"bearer\"},\"tls\":{"
        + "\"caCertificates\":" + Fixtures.JSON.writeValueAsString(CA.certificatePem()) + "}" + extra + "}");
  }

  private Suggestions local(Env env, String json, Fixtures.Secrets secrets) {
    return connector.suggest(cfg("draft", env, json), secrets, Fixtures.Context.of(guard), Suggestions.Scope.LOCAL);
  }

  // ── Local ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aWellKnownApiFillsInItsSignInAndSaysWhatElseItNeeds() {
    Suggestions s = local(Env.DEV, "{\"baseUrls\":[\"https://api.anthropic.com/v1\"],\"auth\":{\"type\":\"bearer\"}}",
        new Fixtures.Secrets());

    assertThat(s.fields().get("/auth/type").value()).isEqualTo("apiKeyHeader");
    assertThat(s.fields().get("/auth/type").source()).isEqualTo("what the Anthropic API expects");
    assertThat(s.fields().get("/auth/headerName").value()).isEqualTo("x-api-key");
    assertThat(s.notes()).containsExactly("The Anthropic API also needs the header anthropic-version: 2023-06-01: "
        + "add it under Default headers.");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void aTypedTokenKeepsItsSignInAndANoteSaysWhatToChoose() {
    Suggestions s = local(Env.DEV, "{\"baseUrls\":[\"my-team.openai.azure.com/openai\"],\"auth\":{\"type\":"
        + "\"bearer\"}}", Fixtures.Secrets.of(HttpSettings.TOKEN, "typed"));

    assertThat(s.fields()).doesNotContainKey("/auth/type");
    assertThat(s.fields().get("/auth/headerName").value()).isEqualTo("api-key");
    assertThat(s.notes()).containsExactly("The Azure OpenAI API signs in with an API key in the api-key header: "
        + "choose API key in a header under Authentication.");
  }

  @Test
  void anApiThatTakesAUserAndPasswordGetsItsUser() {
    Suggestions s = local(Env.DEV, "{\"baseUrls\":[\"https://api.mailgun.net/v3\"],\"auth\":{\"type\":\"bearer\"}}",
        new Fixtures.Secrets());

    assertThat(s.fields().get("/auth/type").value()).isEqualTo("basic");
    assertThat(s.fields().get("/auth/username").value()).isEqualTo("api");
    assertThat(s.notes()).containsExactly("Mailgun signs in as the user api with an API key as the password.");
  }

  @Test
  void anyOtherApiIsOfferedTheUsualNamesAndNothingIsFilledIn() {
    Suggestions s = local(Env.DEV, "{\"baseUrls\":[\"https://billing.example.test/v1\"],\"auth\":{\"type\":"
        + "\"apiKeyHeader\"}}", new Fixtures.Secrets());

    assertThat(s.fields()).doesNotContainKeys("/auth/type", "/auth/username");
    Suggestions.Field header = s.fields().get("/auth/headerName");
    assertThat(header.value()).isNull();
    assertThat(header.source()).isEqualTo("common API key headers");
    assertThat(header.options()).extracting(Option::value).startsWith("X-API-Key", "api-key",
        "Ocp-Apim-Subscription-Key", "apikey").contains("Authorization", "PRIVATE-TOKEN");
    // Every offered name is one the connection accepts as its key header.
    assertThat(header.options()).allMatch(o -> Headers.validName(o.value()));
    assertThat(s.fields().get("/auth/queryName").options()).extracting(Option::value).contains("api_key", "key");
    assertThat(s.notes()).isEmpty();
  }

  @Test
  void anHttpBaseUrlTurnsOnPlainHttpButNeverOnProd() {
    Suggestions dev = local(Env.DEV, "{\"baseUrls\":[\"http://10.0.0.5:8080\"],\"auth\":{\"type\":\"none\"}}",
        new Fixtures.Secrets());
    assertThat(dev.fields().get("/advanced/allowPlainHttp").value()).isEqualTo("true");
    assertThat(dev.notes()).contains("An http:// base URL needs Allow plain HTTP under Advanced settings, and plain "
        + "HTTP goes only to a private address you allow in Policies.");

    Suggestions prod = local(Env.PROD, "{\"baseUrls\":[\"http://10.0.0.5:8080\"],\"auth\":{\"type\":\"none\"}}",
        new Fixtures.Secrets());
    assertThat(prod.fields()).doesNotContainKey("/advanced/allowPlainHttp");
    assertThat(prod.notes()).contains("Prod connections never send plain http://: use an https:// base URL.");
  }

  @Test
  void theHostOfABaseUrlAsItIsTyped() {
    assertThat(HttpSuggestions.host("https://API.Example.test:8443/v1?x#y")).isEqualTo("api.example.test");
    assertThat(HttpSuggestions.host("api.example.test/v1")).isEqualTo("api.example.test");
    assertThat(HttpSuggestions.host("https://user@api.example.test.")).isEqualTo("api.example.test");
    assertThat(HttpSuggestions.host("http://[::1]:8080/")).isEqualTo("::1");
    assertThat(HttpSuggestions.host("https://")).isNull();
  }

  @Test
  void testConnectionFailsAnHttpBaseUrlThatCallsWouldRefuse() {
    TestResult t = connector.test(cfg("legacy", Env.DEV, "{\"baseUrls\":[\"http://10.0.0.5:8080\"],\"auth\":"
        + "{\"type\":\"none\"}}"), new Fixtures.Secrets(), Fixtures.Context.of(guard));

    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("http.plain-http");
    assertThat(t.error().message()).startsWith("Calls over plain http:// are refused until you turn on Allow plain "
        + "HTTP");
  }

  // ── Service ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theApisOpenApiDocumentOffersItsServersPathsAndSignIn() throws Exception {
    serve("/v1/openapi.json", DOCUMENT.formatted(base()));
    Fixtures.Secrets secrets = Fixtures.Secrets.of(HttpSettings.TOKEN, "tok-cnry-77");

    Suggestions s = connector.suggest(trusted(""), secrets, Fixtures.Context.of(guard), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/baseUrls").options()).containsExactly(
        Option.of("https://sandbox.api.test/v1", "a server of the document"));
    assertThat(s.fields().get("/baseUrls").source()).isEqualTo("the OpenAPI document");
    assertThat(s.fields().get("/allowedPaths").options()).extracting(Option::value)
        .containsExactly("/invoices/**", "/refunds/**");
    assertThat(s.notes()).containsExactly(
        "The OpenAPI document signs in with an API key in the X-Billing-Key header: choose API key in a header "
            + "under Authentication.",
        "Found the OpenAPI document of Billing at " + base() + "/openapi.json: 4 paths. Import HTTP API turns its "
            + "operations into tools.");
    // Read without credentials: nothing was resolved or sent.
    assertThat(requests).singleElement().satisfies(r -> {
      assertThat(r.getMethod()).isEqualTo("GET");
      assertThat(r.getHeaders().get("Authorization")).isNull();
    });
    assertThat(secrets.resolved).isEmpty();
  }

  @Test
  void aMatchingSignInGetsTheDocumentsHeaderName() {
    serve("/v1/openapi.json", DOCUMENT.formatted(base()));

    Suggestions s = connector.suggest(cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"" + base() + "\"],\"auth\":{"
        + "\"type\":\"apiKeyHeader\"},\"tls\":{\"caCertificates\":" + Fixtures.JSON.writeValueAsString(
            CA.certificatePem()) + "}}"), Fixtures.Secrets.of(HttpSettings.API_KEY, "k"), Fixtures.Context.of(guard),
        Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/auth/headerName").value()).isEqualTo("X-Billing-Key");
    assertThat(s.fields().get("/auth/headerName").source()).isEqualTo("the OpenAPI document");
  }

  @Test
  void noDocumentIsNothingAndTheLookIsKeptAWhile() {
    serve("/nowhere", "{}");

    Suggestions first = connector.suggest(trusted(""), new Fixtures.Secrets(), Fixtures.Context.of(guard),
        Suggestions.Scope.SERVICE);
    int asked = requests.size();
    Suggestions again = connector.suggest(trusted(",\"allowedPaths\":[\"/invoices/**\"]"), new Fixtures.Secrets(),
        Fixtures.Context.of(guard), Suggestions.Scope.SERVICE);

    assertThat(first.isEmpty()).isTrue();
    assertThat(again.isEmpty()).isTrue();
    // Under the base URL, then at the origin: four places, once.
    assertThat(asked).isEqualTo(4);
    assertThat(requests).hasSize(4);
  }

  @Test
  void anUntrustedCertificateIsANoteAndNothingIsSent() {
    serve("/v1/openapi.json", DOCUMENT.formatted(base()));

    Suggestions s = connector.suggest(cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"" + base() + "\"],\"auth\":"
        + "{\"type\":\"none\"}}"), new Fixtures.Secrets(), Fixtures.Context.of(guard), Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("The certificate of api.test isn't trusted yet: Test connection shows it "
        + "for you to trust, then the API's OpenAPI document can be read.");
    assertThat(server.getRequestCount()).isZero();
  }
}
