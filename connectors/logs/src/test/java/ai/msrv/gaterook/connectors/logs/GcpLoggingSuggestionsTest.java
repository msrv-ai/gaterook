package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help for the GCP Cloud Logging form: gcloud's project or a pasted key's as {@code projects/<id>} on this computer,
 * and once the draft signs in, the log views of the projects named (or, while none is, of the one this computer knows).
 * Listing every project stays with the form's project discovery.
 */
class GcpLoggingSuggestionsTest {

  private static final String DEFAULT = "projects/acme-dev/locations/global/buckets/_Default";
  private static final String REQUIRED = "projects/acme-dev/locations/global/buckets/_Required";

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private CloudCredentials cloud;
  private CloudEndpoints endpoints;

  @TempDir
  Path home;

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        return switch (request.getUrl().encodedPath()) {
          case "/token" -> json("{\"access_token\":\"ya29.user-sign-in\",\"expires_in\":3600,\"token_type\":"
              + "\"Bearer\"}");
          case "/v2/projects/acme-dev/locations/-/buckets" -> json("{\"buckets\":[{\"name\":\"" + DEFAULT
              + "\",\"lifecycleState\":\"ACTIVE\"},{\"name\":\"" + REQUIRED + "\",\"lifecycleState\":\"ACTIVE\"},"
              + "{\"name\":\"projects/acme-dev/locations/europe-west1/buckets/old\",\"lifecycleState\":"
              + "\"DELETE_REQUESTED\"}]}");
          case "/v2/" + DEFAULT + "/views" -> json("{\"views\":[{\"name\":\"" + DEFAULT + "/views/_AllLogs\"},"
              + "{\"name\":\"" + DEFAULT + "/views/_Default\"}]}");
          case "/v2/" + REQUIRED + "/views" -> json("{\"views\":[{\"name\":\"" + REQUIRED + "/views/_AllLogs\"}]}");
          default -> new MockResponse.Builder().code(404).body("{\"error\":{\"message\":\"not found\"}}").build();
        };
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Path adc = Files.writeString(home.resolve("adc.json"), "{\"type\":\"authorized_user\",\"client_id\":\"c.apps"
        + ".googleusercontent.com\",\"client_secret\":\"d-cnry\",\"refresh_token\":\"1//cnry-refresh\"}");
    Path gcloud = Files.createDirectories(home.resolve("gcloud/configurations"));
    Files.writeString(gcloud.getParent().resolve("active_config"), "default\n");
    Files.writeString(gcloud.resolve("config_default"), "[core]\naccount = dev@acme.test\nproject = acme-dev\n");
    URI base = server.url("/").uri();
    endpoints = new CloudEndpoints(server.url("/token").uri(), base + "v1/projects/-/serviceAccounts/%s"
        + ":generateAccessToken", adc, null, null, null, Map.of("gcp.logging", base));
    cloud = new CloudCredentials(endpoints, Clock.systemUTC());
  }

  @AfterEach
  void stop() throws IOException {
    cloud.close();
    server.close();
  }

  private Suggestions suggest(CloudLocal local, String json, TestSecrets secrets, Suggestions.Scope scope)
      throws Exception {
    try (BackendConnector connector = new BackendConnector("gcp-logging", cloud, Clock.systemUTC(), local)) {
      return connector.suggest(new ConnectionConfig("draft-1", "draft", "gcp-logging", Env.DEV, 0,
          TestCtx.JSON.readTree(json), Set.of("/credentials/serviceAccountKey"), null), secrets, TestCtx.of(), scope);
    }
  }

  private CloudLocal gcloud() {
    Path dir = home.resolve("gcloud");
    return new CloudLocal(endpoints, k -> k.equals("CLOUDSDK_CONFIG") ? dir.toString() : null, home);
  }

  @Test
  void gcloudsProjectIsTheScopeOfTheGoogleSignIn() throws Exception {
    Suggestions s = suggest(gcloud(), """
        {"backend":"gcp-logging","credentials":{"source":"adc",
         "impersonate":"gw-reader@acme-ops.iam.gserviceaccount.com"}}""", new TestSecrets(), Suggestions.Scope.LOCAL);

    Suggestions.Field names = s.fields().get("/resourceNames");
    assertThat(names.value()).isEqualTo("projects/acme-dev");
    assertThat(names.source()).isEqualTo("your gcloud configuration");
    assertThat(names.options()).extracting(Option::value).containsExactly("projects/acme-dev", "projects/acme-ops");
    assertThat(names.options().get(1).detail()).isEqualTo("the impersonated service account's project");
    assertThat(s.notes()).isEmpty();
    assertThat(requests).isEmpty();
  }

  @Test
  void withoutAGoogleSignInItSaysHowToSignIn() throws Exception {
    CloudEndpoints none = new CloudEndpoints(null, null, home.resolve("missing.json"), null, null, null);
    Suggestions s = suggest(new CloudLocal(none, k -> null, home), """
        {"backend":"gcp-logging","credentials":{"source":"adc"}}""", new TestSecrets(), Suggestions.Scope.LOCAL);

    assertThat(s.notes()).containsExactly("Nobody is signed in to Google on this computer: run gcloud auth "
        + "application-default login, or choose a service account key.");
    assertThat(s.fields()).isEmpty();
  }

  @Test
  void aPastedKeyNamesItsOwnProject() throws Exception {
    String key = "{\"type\":\"service_account\",\"project_id\":\"acme-prod\",\"private_key_id\":\"k1\","
        + "\"client_email\":\"gw@acme-prod.iam.gserviceaccount.com\"}";
    String config = "{\"backend\":\"gcp-logging\",\"credentials\":{\"source\":\"serviceAccountKey\"}}";

    Suggestions s = suggest(gcloud(), config, new TestSecrets().with("/credentials/serviceAccountKey", key),
        Suggestions.Scope.LOCAL);
    Suggestions wrong = suggest(gcloud(), config, new TestSecrets().with("/credentials/serviceAccountKey",
        "not a key"), Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/resourceNames").value()).as("not gcloud's project").isEqualTo("projects/acme-prod");
    assertThat(s.fields().get("/resourceNames").source()).isEqualTo("the service account key");
    assertThat(wrong.notes()).containsExactly("This isn't a service account key: paste the whole JSON key file, with "
        + "its project_id.");
    assertThat(wrong.fields()).isEmpty();
  }

  @Test
  void signedInItOffersTheLogViewsOfTheProjectsNamed() throws Exception {
    Suggestions s = suggest(gcloud(), """
        {"backend":"gcp-logging","resourceNames":["projects/acme-dev"],"credentials":{"source":"adc"}}""",
        new TestSecrets(), Suggestions.Scope.SERVICE);

    Suggestions.Field names = s.fields().get("/resourceNames");
    assertThat(names.source()).isEqualTo("Cloud Logging");
    assertThat(names.options()).extracting(Option::value).containsExactly(DEFAULT + "/views/_AllLogs",
        DEFAULT + "/views/_Default", REQUIRED + "/views/_AllLogs");
    assertThat(names.options().getFirst().detail()).isEqualTo("bucket _Default in global");
    assertThat(s.notes()).containsExactly("Signed in to Cloud Logging with the Google sign-in on this computer: 3 log "
        + "views in projects/acme-dev. A log view is the tightest scope.");
    assertThat(requests.stream().filter(r -> r.getUrl().encodedPath().startsWith("/v2/")).toList()).hasSize(3)
        .allSatisfy(r -> {
          assertThat(r.getMethod()).isEqualTo("GET");
          assertThat(r.getHeaders().get("Authorization")).isEqualTo("Bearer ya29.user-sign-in");
        });
  }

  @Test
  void whileNoScopeIsNamedItReadsTheProjectThisComputerKnows() throws Exception {
    Suggestions s = suggest(gcloud(), """
        {"backend":"gcp-logging","resourceNames":[],"credentials":{"source":"adc"}}""", new TestSecrets(),
        Suggestions.Scope.SERVICE);
    Suggestions unknown = suggest(new CloudLocal(endpoints, k -> null, home.resolve("elsewhere")), """
        {"backend":"gcp-logging","credentials":{"source":"adc"}}""", new TestSecrets(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/resourceNames").options()).extracting(Option::value).startsWith("projects/acme-dev",
        DEFAULT + "/views/_AllLogs");
    assertThat(unknown.notes()).containsExactly("Choose Load projects to list the projects this sign-in may read, or "
        + "type one.");
    assertThat(unknown.fields()).isEmpty();
  }

  private static MockResponse json(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
  }
}
