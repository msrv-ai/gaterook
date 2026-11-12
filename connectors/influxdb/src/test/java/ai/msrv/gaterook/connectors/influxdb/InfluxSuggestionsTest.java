package ai.msrv.gaterook.connectors.influxdb;

import static ai.msrv.gaterook.connectors.influxdb.FakeInflux.ping;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the InfluxDB form: the {@code influx} CLI's profiles (never their tokens) and local servers, offered while
 * the URL is empty; the server a URL of this computer names in {@code /ping}, asked without credentials; and, signed in
 * to a stand-in server, the API family for Server, InfluxDB 2.x's organization and the buckets or databases.
 */
class InfluxSuggestionsTest {

  private static final String TOKEN = "cnry-influx-suggest-token-1";

  @TempDir
  Path dir;

  private FakeInflux influx;
  private InfluxDbConnector connector;

  @BeforeEach
  void start() throws Exception {
    influx = new FakeInflux();
    connector = new InfluxDbConnector();
  }

  @AfterEach
  void stop() {
    connector.close();
    influx.close();
  }

  private static ConnectionConfig cfg(Consumer<ObjectNode> tweak) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("version", "3");
    c.putObject("auth").put("method", "token");
    if (tweak != null) {
      tweak.accept(c);
    }
    return new ConnectionConfig("draft-1", "draft", "influxdb", Env.DEV, 0, c, Set.of(InfluxSettings.TOKEN), null);
  }

  private Path profiles() throws Exception {
    Path file = dir.resolve("configs");
    Files.writeString(file, """
        [default]
          url = "http://localhost:8086"
          token = "%s"
          org = "acme"
          active = true
        [cloud]
          url = "https://eu-central-1-1.aws.cloud2.influxdata.com/"
          token = "%s"
          org = "acme-cloud"
        """.formatted(TOKEN, TOKEN));
    return file;
  }

  @Test
  void anEmptyUrlIsOfferedTheCliProfilesAndLocalServers() throws Exception {
    Suggestions.Builder out = Suggestions.builder();
    InfluxSuggestions.local(cfg(null), port -> port == 8181, profiles(), uri -> null, out);
    Suggestions s = out.build();

    Suggestions.Field url = s.fields().get("/url");
    assertThat(url.value()).as("never filled in").isNull();
    assertThat(url.options()).extracting(Option::value).containsExactly("http://localhost:8086",
        "https://eu-central-1-1.aws.cloud2.influxdata.com", "http://localhost:8181");
    assertThat(url.options().getFirst().detail()).isEqualTo("influx CLI profile default (active)");
    assertThat(url.options().get(2).detail()).isEqualTo("A server answers on port 8181, InfluxDB 3's");
    // Organizations are InfluxDB 2.x's: not offered while the form says InfluxDB 3.
    assertThat(s.fields()).doesNotContainKey("/organization");
    assertThat(s.toString()).doesNotContain(TOKEN);
  }

  @Test
  void theProfileOfTheTypedServerFillsInItsOrganization() throws Exception {
    Suggestions.Builder out = Suggestions.builder();
    InfluxSuggestions.local(cfg(c -> c.put("version", "2").put("url", "https://eu-central-1-1.aws.cloud2"
        + ".influxdata.com")), port -> false, profiles(), uri -> null, out);
    Suggestions.Field org = out.build().fields().get("/organization");

    assertThat(org.value()).isEqualTo("acme-cloud");
    assertThat(org.source()).isEqualTo("~/.influxdbv2/configs");
    assertThat(org.options()).extracting(Option::value).containsExactly("acme", "acme-cloud");
  }

  @Test
  void aServerOfThisComputerNamesItsFamilyWithoutCredentials() {
    influx.on("GET /ping", r -> ping("v2.7.10", "OSS"));
    Suggestions.Builder out = Suggestions.builder();
    InfluxSuggestions.local(cfg(c -> c.put("url", influx.url())), port -> false, dir.resolve("none"),
        uri -> InfluxSuggestions.ping(uri, Duration.ofSeconds(2)), out);
    Suggestions s = out.build();

    Suggestions.Field version = s.fields().get("/version");
    assertThat(version.value()).isEqualTo("2");
    assertThat(version.options()).containsExactly(new Option("2", "InfluxDB 2.7.10 OSS", null));
    assertThat(influx.to("GET /ping")).singleElement()
        .satisfies(r -> assertThat(r.getHeaders().get("Authorization")).isNull());
    assertThat(InfluxSuggestions.ping(URI.create("https://influx.example.com"), Duration.ofSeconds(1)))
        .as("never another computer").isNull();
  }

  @Test
  void signedInToInfluxDb2ItFillsTheOrganizationAndListsTheBuckets() {
    influx.on("GET /ping", r -> ping("v2.7.10", "OSS"))
        .on("GET /api/v2/orgs", 200, "{\"orgs\":[{\"id\":\"1\",\"name\":\"acme\"}]}")
        .on("POST /query", 200, "{\"results\":[{\"statement_id\":0,\"series\":[{\"name\":\"databases\",\"columns\":"
            + "[\"name\"],\"values\":[[\"telemetry\"],[\"iot\"]]}]}]}");

    Suggestions s = connector.suggest(cfg(c -> c.put("version", "2").put("url", influx.url())),
        new TestSecrets().with(InfluxSettings.TOKEN, TOKEN), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/version").value()).isEqualTo("2");
    assertThat(s.fields().get("/organization").value()).isEqualTo("acme");
    assertThat(s.fields().get("/databases").options()).extracting(Option::value).containsExactly("iot", "telemetry");
    assertThat(s.notes()).containsExactly("Signed in to InfluxDB 2.7.10 OSS: 2 buckets. Choose the ones agents may "
        + "use.");
    assertThat(influx.to("GET /api/v2/orgs").getFirst().getHeaders().get("Authorization")).isEqualTo("Token " + TOKEN);
  }

  @Test
  void anotherFamilyThanTheFormsSetsServerAndWaitsForIt() {
    influx.on("GET /ping", r -> ping("3.12.1", "Core"));

    Suggestions s = connector.suggest(cfg(c -> c.put("version", "2").put("url", influx.url())),
        new TestSecrets().with(InfluxSettings.TOKEN, TOKEN), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/version").value()).isEqualTo("3");
    assertThat(s.fields()).doesNotContainKey("/databases");
    assertThat(s.notes()).contains("This server is InfluxDB 3.12.1 Core: Server changes to InfluxDB 3 Core or "
        + "Enterprise.");
  }

  @Test
  void refusedCredentialsAndUnusableSettingsAreNotes() {
    influx.on("GET /ping", 401, "{\"error\": \"the request was not authenticated\"}");
    Suggestions refused = connector.suggest(cfg(c -> c.put("url", influx.url())),
        new TestSecrets().with(InfluxSettings.TOKEN, TOKEN), TestCtx.of(), Suggestions.Scope.SERVICE);
    assertThat(refused.notes()).anyMatch(n -> n.startsWith("InfluxDB refused the credentials"));

    Suggestions remote = connector.suggest(cfg(c -> c.put("url", "http://influx.example.com:8086")),
        new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);
    assertThat(remote.notes()).anyMatch(n -> n.startsWith("Plain http:// goes only to this computer"));
  }

  @Test
  void theServerFieldIsAskedForAndFilledByTheService() {
    assertThat(connector.manifest().configSchema().at("/properties/version/x-suggest").asBoolean()).isTrue();
    assertThat(connector.manifest().configSchema().path("required").toString()).contains("\"version\"");
  }
}
