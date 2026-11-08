package ai.msrv.gaterook.e2e.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in native download smoke: contacts the pinned publisher once, then restarts with an unreachable proxy. */
@EnabledIfSystemProperty(named = "gateway.test.optionalComponents", matches = "true")
class OptionalComponentsProcessIT {
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test void nativeFirstUseDownloadsJavaAndAnOfflineRestartUsesTheVerifiedCache() throws Exception {
    assumeTrue(CoreProcess.nativeBinary());
    Path home = CoreProcess.newHome("optional-components");
    Path resources = Files.createDirectories(home.resolve("cache/resources"));
    Path host = Files.createDirectories(resources.resolve("plugin-host"));
    Files.copy(Path.of(System.getProperty("gateway.pluginHostJar")), host.resolve("plugin-host.jar"));
    List<String> flags = new ArrayList<>(List.of("-Dgateway.resources=" + resources, "-Dgateway.plugins.aot-cache=false"));
    try (CoreProcess core = CoreProcess.start(home, CoreProcess.freePort(), flags).bootstrap()) {
      core.untilReady(Duration.ofSeconds(90));
      assertThat(request(core, "GET", "/api/plugins/runtime").path("state").asString()).isEqualTo("missing");
      assertThat(Files.exists(home.resolve("runtimes"))).isFalse();
      assertThat(request(core, "GET", "/api/core/info").path("pluginRuntime").path("state").asString()).isEqualTo("missing");
      JsonNode status = request(core, "POST", "/api/plugins/runtime/prepare");
      assertThat(status.path("state").asString()).isEqualTo("downloading");
      long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
      while (status.path("state").asString().equals("downloading") && System.nanoTime() < deadline) {
        Thread.sleep(250);
        status = request(core, "GET", "/api/plugins/runtime");
      }
      assertThat(status.path("state").asString()).as(status.toString()).isEqualTo("ready");
    }
    flags.addAll(List.of("-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
        "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9"));
    try (CoreProcess core = CoreProcess.start(home, CoreProcess.freePort(), flags).bootstrap()) {
      core.untilReady(Duration.ofSeconds(90));
      assertThat(request(core, "GET", "/api/plugins/runtime").path("state").asString()).isEqualTo("ready");
      assertThat(request(core, "POST", "/api/plugins/runtime/prepare").path("state").asString()).isEqualTo("ready");
      JsonNode details = request(core, "GET", "/api/core/info").path("pluginRuntime");
      assertThat(details.path("present").asBoolean()).isTrue();
      assertThat(details.path("source").asString()).isEqualTo("downloaded");
      assertThat(core.coreLog()).doesNotContain("MissingReflectionRegistration", "MissingResourceRegistration",
          "UnsupportedFeatureError");
    }
  }

  private static JsonNode request(CoreProcess core, String method, String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + core.port + path))
        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
        .header("X-Gateway-Auth", core.secret().sign(method, path, System.currentTimeMillis(), UUID.randomUUID().toString()))
        .method(method, "POST".equals(method) ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody())
        .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }
}
