package com.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.gaterook.e2e.modules.fixture.EchoPackages;
import com.gaterook.e2e.platform.CoreProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * An update that turns a compiled-in connector into a connector module (docs/connector-delivery.md). The build
 * before has the connector in its own process: a person made a connection with it, put it on an endpoint and chose
 * the modes of its tools. The build after doesn't have it. It finds the connection, keeps the type's tools on the
 * endpoint as the database describes them, asks for the module at once and serves the same tools in the same modes
 * from the module's host. Nobody is asked anything, and a call that comes while the module is on its way waits for
 * it and is answered.
 *
 * <p>The two builds are the same assembled core: the one before is started with the fixture's connectors among its
 * own classes ({@link CoreProcess#start(Path, int, List, List)}), which a native core can't be.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CompiledInToModuleCoreProcessIT {

  private static final String SECRET = "cnry-echo-e2e-secret-4be1";

  private static ModuleChannelServer channel;
  private static CoreProcess core;
  private static CoreClient api;
  private static byte[] jar;
  private static String token;

  @TempDir
  static Path home;

  @BeforeAll
  static void start() throws Exception {
    assumeFalse(CoreProcess.nativeBinary(), "A native core can't be given a compiled-in connector at run time");
    assumeFalse(Boolean.getBoolean("gateway.core.aot"), "An AOT context doesn't take another auto-configuration");
    jar = EchoPackages.module("1.0.0");
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
  void theBuildBeforeServesTheConnectorInItsOwnProcess() throws Exception {
    Path compiledIn = Files.write(home.resolve("echo-compiled-in.jar"), EchoPackages.compiledIn());
    core = CoreProcess.start(home, CoreProcess.freePort(), List.of(), List.of(compiledIn)).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
    api = new CoreClient(core);

    JsonNode card = api.card("echo-db");
    assertThat(card.path("kind").asString()).isEqualTo("built-in");
    assertThat(card.path("module").isNull()).as("part of this build: " + card).isTrue();

    api.json(api.admin("POST", "/api/connections", """
        {"name":"echo-dev","type":"echo-db","env":"dev","config":{"host":"echo.example.com"},
         "secrets":{"/password":{"source":"local","value":"%s"}}}""".formatted(SECRET)), 201);
    token = api.endpoint("echo", "echo-dev");
    assertThat(api.modes("echo", "echo-dev")).containsEntry("echo_read", "READ_ONLY")
        .containsEntry("echo_write", "APPROVAL").hasSize(2);
    // What a person chose: the change is off on this endpoint.
    api.mode("echo", "echo-dev", "echo_write", "OFF");

    JsonNode read = api.call(2, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"before\"}");
    assertThat(read.at("/result/structuredContent/echo").asString()).as(read.toString()).isEqualTo("before");
    assertThat(read.at("/result/structuredContent/pid").asLong()).as("in the core's own process")
        .isEqualTo(core.process.pid());
    assertThat(read.at("/result/structuredContent/build").asString()).isEqualTo("unpackaged");

    core.close();
    assertThat(core.exited(Duration.ofSeconds(30))).isTrue();
  }

  @Test
  @Order(2)
  void theBuildAfterKeepsTheTypesToolsAndAsksForItsModuleAtOnce() throws Exception {
    channel.hold();
    core = CoreProcess.start(home, CoreProcess.freePort(), BuiltModule.flags(home, channel)).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
    api = new CoreClient(core);

    // The connector isn't part of this build and its module isn't here yet: the tools are, in the modes they had.
    assertThat(api.modes("echo", "echo-dev")).as("as the build before left them").containsEntry("echo_read",
        "READ_ONLY").containsEntry("echo_write", "OFF").hasSize(2);
    api.session("echo", token);
    assertThat(api.tools(2)).as("the agent's list is the same as before the update").contains("echo_read")
        .doesNotContain("echo_write");

    // Nobody opened a form or made a call: the start asked for the module because a connection uses its type.
    JsonNode module = api.awaitModule("echo", "installing", Duration.ofSeconds(30));
    assertThat(module.path("installed").asBoolean()).isFalse();
    JsonNode card = api.card("echo-db");
    assertThat(card.path("kind").asString()).isEqualTo("built-in");
    assertThat(card.at("/module/installed").asBoolean()).as("not here yet: " + card).isFalse();
    assertThat(channel.downloads()).as("its package is being fetched").isEqualTo(1);
  }

  @Test
  @Order(3)
  void aCallThatComesMeanwhileWaitsForTheModuleAndIsAnsweredByItsHost() throws Exception {
    CompletableFuture<JsonNode> early = CompletableFuture.supplyAsync(() -> {
      try {
        return api.call(3, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"during\"}");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    Thread.sleep(1_500);
    assertThat(early).as("the call waits: its connector is on its way").isNotDone();
    channel.release();

    JsonNode read = early.get(60, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(read.at("/result/isError").asBoolean(true)).as(read.toString()).isFalse();
    assertThat(read.at("/result/structuredContent/echo").asString()).isEqualTo("during");
    assertThat(read.at("/result/structuredContent/pid").asLong()).as("in the module's host, not in the core")
        .isNotEqualTo(core.process.pid());
    assertThat(read.at("/result/structuredContent/build").asString()).isEqualTo("1.0.0");
    assertThat(read.at("/result/structuredContent/passwordLength").asInt()).as("the connection's secret, as before")
        .isEqualTo(SECRET.length());
  }

  @Test
  @Order(4)
  void theModuleIsInstalledAndWhatAPersonChoseIsStillInPlace() throws Exception {
    JsonNode module = api.awaitModule("echo", "ready", Duration.ofSeconds(30));
    assertThat(module.path("installed").asBoolean()).isTrue();
    assertThat(channel.downloads()).as("downloaded once").isEqualTo(1);
    assertThat(api.card("echo-db").at("/module/installed").asBoolean()).isTrue();
    assertThat(api.card("echo-log").at("/module/installed").asBoolean())
        .as("the module's other type, which no connection used, came with it").isTrue();

    assertThat(api.modes("echo", "echo-dev")).as("the modes a person chose survive the change of delivery")
        .containsEntry("echo_read", "READ_ONLY").containsEntry("echo_write", "OFF").hasSize(2);
    assertThat(api.tools(4)).contains("echo_read").doesNotContain("echo_write");

    JsonNode again = api.call(5, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"after\"}");
    assertThat(again.at("/result/structuredContent/echo").asString()).as(again.toString()).isEqualTo("after");
    JsonNode denied = api.call(6, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"forbidden words\"}");
    assertThat(denied.toString()).as("the connector's own classification").contains("This text isn't echoed");
  }
}
