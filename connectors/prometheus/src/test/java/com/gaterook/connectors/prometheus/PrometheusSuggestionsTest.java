package com.gaterook.connectors.prometheus;

import static com.gaterook.connectors.prometheus.FakePrometheus.json;
import static com.gaterook.connectors.prometheus.FakePrometheus.success;
import static com.gaterook.connectors.prometheus.FakePrometheus.vector;
import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.time.Clock;
import java.util.Set;
import mockwebserver3.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Prometheus form against a stand-in server: a local server offered for an empty URL, the server's kind,
 * the sign-in a server asks for, a Mimir that needs a tenant, and the path its API answers under.
 */
class PrometheusSuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private FakePrometheus prom;
  private PrometheusConnector connector;

  @BeforeEach
  void start() throws Exception {
    prom = new FakePrometheus();
    connector = new PrometheusConnector(Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    connector.close();
    prom.close();
  }

  private static ConnectionConfig cfg(String json) {
    return new ConnectionConfig("draft-1", "draft", "prometheus", Env.DEV, 0, (ObjectNode) JSON.readTree(json),
        Set.of("/auth/secret"), null);
  }

  private Suggestions service(String extra) {
    return connector.suggest(cfg("{\"url\": \"" + prom.url() + "\"" + (extra == null ? "" : ", " + extra) + "}"),
        new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);
  }

  @Test
  void anEmptyUrlIsOfferedTheLocalServerNeverFilledIn() {
    Suggestions s = PrometheusSuggestions.suggest(cfg("{}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL,
        null, port -> port == 9090);

    Suggestions.Field url = s.fields().get("/url");
    assertThat(url.value()).isNull();
    assertThat(url.options()).containsExactly(new Option("http://localhost:9090", null,
        "A Prometheus server answers on port 9090"));
    assertThat(PrometheusSuggestions.suggest(cfg("{\"url\": \"https://prom.example.com\"}"), new TestSecrets(),
        TestCtx.of(), Suggestions.Scope.LOCAL, null, port -> true).isEmpty()).isTrue();
  }

  @Test
  void aServerThatAnswersSaysWhatItIs() {
    prom.on("/api/v1/status/buildinfo", success("{\"version\":\"3.1.0\",\"revision\":\"abc\"}"))
        .on("/api/v1/query", vector("1700000000", "{}", "1"));

    assertThat(service(null).notes()).containsExactly("Connected to Prometheus 3.1.0 at "
        + prom.url().substring("http://".length()) + ": PromQL answers.");
  }

  @Test
  void theSignInTheServerAsksForIsFilledIn() {
    prom.on("/api/v1/status/buildinfo", r -> new MockResponse.Builder().code(401)
        .addHeader("WWW-Authenticate", "Basic realm=\"prometheus\"").body("Unauthorized\n").build());

    Suggestions s = service(null);

    assertThat(s.fields().get("/auth/mode").value()).isEqualTo("basic");
    assertThat(s.notes()).containsExactly("The server asks for a username and password: enter them under "
        + "Credentials.");
  }

  @Test
  void aMimirWithTenantsSaysToSetOne() {
    prom.on("/prometheus/api/v1/status/buildinfo", success("{\"application\":\"Grafana Mimir\",\"version\":"
        + "\"2.14.0\"}"))
        .on("/prometheus/api/v1/query", r -> json(401, "no org id\n"));

    Suggestions s = service(null);

    // The URL without a path finds nothing: Mimir answers under /prometheus.
    assertThat(s.fields().get("/url").options()).extracting(Option::value).containsExactly(prom.url()
        + "/prometheus");
    assertThat(s.notes()).containsExactly("Grafana Mimir 2.14.0 answers under /prometheus: use " + prom.url()
        + "/prometheus.");

    Suggestions tenant = connector.suggest(cfg("{\"url\": \"" + prom.url() + "/prometheus\"}"), new TestSecrets(),
        TestCtx.of(), Suggestions.Scope.SERVICE);
    assertThat(tenant.notes()).containsExactly("Grafana Mimir 2.14.0 needs a tenant: set Tenant (X-Scope-OrgID) "
        + "under Advanced settings.");
  }

  @Test
  void unusableSettingsAreANote() {
    Suggestions s = connector.suggest(cfg("{\"url\": \"http://prom.example.com\"}"), new TestSecrets(), TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.notes()).singleElement().asString().startsWith("Plain http:// reaches only this computer");
    assertThat(prom.requests).isEmpty();
  }
}
