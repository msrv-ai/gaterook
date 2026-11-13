package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.msrv.gaterook.e2e.modules.fixture.EchoPackages;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The bridge between two releases (docs/connector-delivery.md). A release that still compiles a connector in, while
 * its module is already published, installs the module ahead for the connections that use the type and goes on
 * serving the type itself. The release after doesn't compile the connector in: it finds the module in place and
 * serves the same connection from the module's host at once, with nothing to download and the modes a person chose.
 *
 * <p>The two releases are the same assembled core: the first is started with the fixture's connectors among its own
 * classes ({@link CoreProcess#start(Path, int, List, List)}), which a native core can't be.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BridgeToModuleCoreProcessIT {

  private static final String SECRET = "cnry-echo-e2e-secret-7d20";

  private static ModuleChannelServer channel;
  private static CoreProcess core;
  private static CoreClient api;
  private static String token;

  @TempDir
  static Path home;

  @BeforeAll
  static void start() throws Exception {
    assumeFalse(CoreProcess.nativeBinary(), "A native core can't be given a compiled-in connector at run time");
    assumeFalse(Boolean.getBoolean("gateway.core.aot"), "An AOT context doesn't take another auto-configuration");
    byte[] jar = EchoPackages.module("1.0.0");
    channel = ModuleChannelServer.serving(BuiltModule.manifestOf(jar), jar, "echo-1.0.0.jar");
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
    if (channel != null) {
      channel.close();
    }
  }

  @Test
  @Order(1)
  void theReleaseThatCompilesTheConnectorInInstallsItsModuleAheadAndServesTheTypeItself() throws Exception {
    Path compiledIn = Files.write(home.resolve("echo-compiled-in.jar"), EchoPackages.compiledIn());
    core = CoreProcess.start(home, CoreProcess.freePort(), BuiltModule.flags(home, channel), List.of(compiledIn))
        .bootstrap();
    core.untilReady(Duration.ofSeconds(90));
    api = new CoreClient(core);

    api.json(api.admin("POST", "/api/connections", """
        {"name":"echo-dev","type":"echo-db","env":"dev","config":{"host":"echo.example.com"},
         "secrets":{"/password":{"source":"local","value":"%s"}}}""".formatted(SECRET)), 201);
    token = api.endpoint("echo", "echo-dev");
    api.mode("echo", "echo-dev", "echo_write", "OFF");
    assertThat(api.card("echo-db").path("module").isNull())
        .as("a type the build compiles in is the build's, whatever is listed").isTrue();
    assertThat(channel.downloads()).as("nothing is fetched because a list names a module").isZero();

    // The check for updates, which runs by itself soon after the start and every few hours.
    api.json(api.admin("POST", "/api/modules/refresh", null), 200);
    JsonNode module = api.awaitModule("echo", "ready", Duration.ofSeconds(60));
    assertThat(module.path("installed").asBoolean()).isTrue();
    assertThat(channel.downloads()).isEqualTo(1);

    JsonNode read = api.call(2, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"bridge\"}");
    assertThat(read.at("/result/structuredContent/echo").asString()).as(read.toString()).isEqualTo("bridge");
    assertThat(read.at("/result/structuredContent/pid").asLong()).as("still in the core's own process")
        .isEqualTo(core.process.pid());
    assertThat(read.at("/result/structuredContent/build").asString()).isEqualTo("unpackaged");
    assertThat(api.card("echo-db").path("module").isNull()).as("and its card says nothing of a module").isTrue();
    assertThat(api.modes("echo", "echo-dev")).containsEntry("echo_read", "READ_ONLY")
        .containsEntry("echo_write", "OFF").hasSize(2);

    core.close();
    assertThat(core.exited(Duration.ofSeconds(30))).isTrue();
  }

  @Test
  @Order(2)
  void theReleaseAfterFindsTheModuleInPlaceAndServesTheConnectionFromItsHostAtOnce() throws Exception {
    // Nothing may be fetched now: a download would wait forever.
    channel.hold();
    core = CoreProcess.start(home, CoreProcess.freePort(), BuiltModule.flags(home, channel)).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
    api = new CoreClient(core);

    assertThat(api.module("echo").path("state").asString()).isEqualTo("ready");
    assertThat(api.modes("echo", "echo-dev")).as("the modes a person chose").containsEntry("echo_read", "READ_ONLY")
        .containsEntry("echo_write", "OFF").hasSize(2);
    api.session("echo", token);
    assertThat(api.tools(2)).contains("echo_read").doesNotContain("echo_write");

    JsonNode read = api.call(3, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"after\"}");
    assertThat(read.at("/result/isError").asBoolean(true)).as(read.toString()).isFalse();
    assertThat(read.at("/result/structuredContent/echo").asString()).isEqualTo("after");
    assertThat(read.at("/result/structuredContent/pid").asLong()).as("in the module's host now")
        .isNotEqualTo(core.process.pid());
    assertThat(read.at("/result/structuredContent/build").asString()).isEqualTo("1.0.0");
    assertThat(read.at("/result/structuredContent/passwordLength").asInt()).isEqualTo(SECRET.length());
    assertThat(channel.downloads()).as("the package was here already").isEqualTo(1);
    assertThat(api.card("echo-db").at("/module/installed").asBoolean()).isTrue();
  }
}
