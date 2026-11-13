package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.e2e.modules.fixture.EchoPackages;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * A connector module's package that a person chooses as a file (docs/connector-delivery.md, "A module from a file"),
 * through the assembled core with a real module host. A package no list has is the person's own module: it installs
 * only with their word, and then the core takes none of its words, as for an unsigned plugin: its tools start in
 * Approval, Read only is a confirmation per tool, and what a person approves is the call itself. The list neither
 * updates nor restores it; {@code replace-own} returns to GateRook's build. A package the signed list pins is
 * GateRook's own module, wherever its bytes came from: it installs from the file without a question.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModuleFromFileCoreProcessIT {

  private static final String SECRET = "cnry-echo-file-secret-5d20";

  private static ModuleChannelServer channel;
  private static CoreProcess core;
  private static CoreClient api;
  /** The person's own build: no list has it. */
  private static byte[] own;
  /** GateRook's build, which the channel lists. */
  private static byte[] listed;
  private static String staging;

  @TempDir
  static Path home;

  @BeforeAll
  static void start() throws Exception {
    own = EchoPackages.module("1.0.0");
    listed = EchoPackages.module("1.1.0");
    channel = ModuleChannelServer.serving(BuiltModule.manifestOf(listed), listed, "echo-1.1.0.jar");
    core = CoreProcess.start(home, CoreProcess.freePort(), BuiltModule.flags(home, channel)).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
    api = new CoreClient(core);
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
  void aPackageNoListHasIsReviewedAsThePersonsOwnAndInstallsOnlyWithTheirWord() throws Exception {
    JsonNode check = api.json(api.upload("/api/plugins/check", "echo-1.0.0.jar", own), 200);
    staging = check.path("stagingId").asString();
    JsonNode report = check.path("report");
    assertThat(report.path("module").asBoolean()).as(report.toString()).isTrue();
    assertThat(report.path("signed").asBoolean()).as("GateRook's list doesn't pin it").isFalse();
    assertThat(report.path("id").asString()).isEqualTo("echo");
    assertThat(report.path("version").asString()).isEqualTo("1.0.0");
    assertThat(report.path("installedVersion").isNull()).isTrue();
    assertThat(report.path("types")).extracting(t -> t.path("id").asString()).containsExactly("echo-db", "echo-log");
    assertThat(report.at("/types/0/tools")).extracting(t -> t.path("name").asString())
        .containsExactly("echo_read", "echo_write");
    assertThat(report.path("tools")).hasSize(3);

    CoreClient.Answer refused = api.admin("POST", "/api/plugins/install", "{\"stagingId\":\"" + staging + "\"}");
    assertThat(refused.status()).as(refused.body()).isEqualTo(422);
    assertThat(refused.body()).contains("Confirm that you trust it");
    assertThat(api.module("echo").path("installed").asBoolean()).isFalse();
  }

  @Test
  @Order(2)
  void itsToolsStartInApprovalAndReadOnlyNeedsAConfirmationPerTool() throws Exception {
    api.json(api.admin("POST", "/api/plugins/install", "{\"stagingId\":\"" + staging
        + "\",\"acknowledgeUnsigned\":true,\"reason\":\"my own echo\"}"), 200);
    JsonNode echo = api.module("echo");
    assertThat(echo.path("installed").asBoolean()).as(echo.toString()).isTrue();
    assertThat(echo.path("state").asString()).isEqualTo("ready");
    assertThat(echo.path("version").asString()).isEqualTo("1.0.0");
    assertThat(echo.path("signed").asBoolean()).isFalse();
    assertThat(echo.path("listedVersion").asString()).as("what GateRook's list has of the module").isEqualTo("1.1.0");
    assertThat(channel.downloads()).as("nothing came from the channel").isZero();

    JsonNode installed = installed("echo-db");
    assertThat(installed.path("kind").asString()).isEqualTo("built-in");
    assertThat(installed.path("signed").asBoolean(true)).as(installed.toString()).isFalse();
    assertThat(installed.at("/module/id").asString()).isEqualTo("echo");
    assertThat(installed.at("/module/signed").asBoolean(true)).isFalse();

    api.json(api.admin("POST", "/api/connections", """
        {"name":"echo-dev","type":"echo-db","env":"dev","config":{"host":"echo.example.com"},
         "secrets":{"/password":{"source":"local","value":"%s"}}}""".formatted(SECRET)), 201);
    api.endpoint("echo", "echo-dev");
    assertThat(api.modes("echo", "echo-dev")).as("whatever defaults the module declares")
        .containsEntry("echo_read", "APPROVAL").containsEntry("echo_write", "APPROVAL").hasSize(2);
    JsonNode row = api.tool("echo", "echo-dev", "echo_read");
    assertThat(row.path("declaredBy").asString()).as(row.toString()).isEqualTo("Echo DB");

    CoreClient.Answer readOnly = api.admin("PUT", "/api/endpoints/echo/tools", CoreProcess.JSON.createObjectNode()
        .put("toolKey", row.path("toolKey").asString()).put("connection", "echo-dev").put("mode", "READ_ONLY")
        .put("version", row.path("version").asLong()).put("reason", "Module e2e").toString());
    assertThat(readOnly.status()).as(readOnly.body()).isNotEqualTo(200);
    assertThat(readOnly.body()).contains("Confirm that echo_read only reads");
  }

  @Test
  @Order(3)
  void whatAPersonApprovesIsTheCallItselfWithTheModulesWordsBesideIt() throws Exception {
    JsonNode pending = api.call(2, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"hello\"}");
    String handle = pending.at("/result/structuredContent/handle").asString();
    assertThat(handle).as(pending.toString()).startsWith("ap_");
    JsonNode detail = api.get("/api/approvals/" + handle);
    assertThat(detail.at("/action/text").asString()).as("the call's own arguments, not the module's words: " + detail)
        .isEqualTo("{\"text\":\"hello\"}");
    assertThat(detail.path("target").asString()).isEqualTo("echo-dev");
    assertThat(detail.at("/fields/0/label").asString()).isEqualTo("Described by echo 1.0.0 (unsigned), not verified");
    assertThat(detail.at("/fields/0/text").asString()).as("what the module said, beside it")
        .contains("WRITE 'hello' TO echo.example.com");

    JsonNode done = api.approve(3, handle);
    assertThat(done.at("/result/structuredContent/status").asString()).as(done.toString()).isEqualTo("completed");
    assertThat(done.at("/result/structuredContent/result/echo").asString()).isEqualTo("hello");
    assertThat(done.at("/result/structuredContent/result/build").asString()).isEqualTo("1.0.0");
    assertThat(done.toString()).doesNotContain(SECRET);
  }

  @Test
  @Order(4)
  void theListDoesNotReplaceAPersonsOwnBuildUntilThePersonAsksForGateRooks() throws Exception {
    api.json(api.admin("POST", "/api/modules/refresh", null), 200);
    JsonNode still = api.module("echo");
    assertThat(still.path("version").asString()).as(still.toString()).isEqualTo("1.0.0");
    assertThat(still.path("state").asString()).isEqualTo("ready");
    assertThat(channel.downloads()).isZero();

    api.json(api.admin("POST", "/api/modules/echo/replace-own", null), 200);
    JsonNode echo = awaitVersion("1.1.0");
    assertThat(echo.path("signed").asBoolean()).as(echo.toString()).isTrue();
    assertThat(echo.path("previousVersion").asString()).isEqualTo("1.0.0");
    assertThat(echo.path("listedVersion").isNull()).isTrue();
    assertThat(channel.downloads()).isEqualTo(1);

    // GateRook vouches for its own build: nothing is declared by somebody else, and Read only needs no confirmation.
    assertThat(api.tool("echo", "echo-dev", "echo_read").path("declaredBy").isNull()).isTrue();
    assertThat(api.modes("echo", "echo-dev")).as("a build change loosens no mode a tool had")
        .containsEntry("echo_read", "APPROVAL");
    api.mode("echo", "echo-dev", "echo_read", "READ_ONLY");
    JsonNode read = api.call(4, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"again\"}");
    assertThat(read.at("/result/isError").asBoolean(true)).as(read.toString()).isFalse();
    assertThat(read.at("/result/structuredContent/build").asString()).isEqualTo("1.1.0");
  }

  @Test
  @Order(5)
  void rollingBackToThePersonsOwnBuildTakesItsWordForNothingAgain() throws Exception {
    JsonNode rolled = api.json(api.admin("POST", "/api/modules/echo/rollback", "{\"reason\":\"back to my build\"}"),
        200);
    assertThat(rolled.path("version").asString()).isEqualTo("1.0.0");
    assertThat(rolled.path("signed").asBoolean()).as(rolled.toString()).isFalse();
    assertThat(api.tool("echo", "echo-dev", "echo_read").path("declaredBy").asString()).isEqualTo("Echo DB");
    assertThat(api.modes("echo", "echo-dev")).as("Read only was never confirmed for this build")
        .containsEntry("echo_read", "APPROVAL");
    JsonNode pending = api.call(5, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"once more\"}");
    assertThat(pending.at("/result/structuredContent/handle").asString()).as(pending.toString()).startsWith("ap_");
  }

  @Test
  @Order(6)
  void aPackageTheSignedListPinsInstallsFromAFileAsGateRooksOwn(@TempDir Path fresh) throws Exception {
    int downloads = channel.downloads();
    CoreProcess second = CoreProcess.start(fresh, CoreProcess.freePort(), BuiltModule.flags(fresh, channel))
        .bootstrap();
    try {
      second.untilReady(Duration.ofSeconds(90));
      CoreClient other = new CoreClient(second);
      JsonNode check = other.json(other.upload("/api/plugins/check", "echo-1.1.0.jar", listed), 200);
      assertThat(check.at("/report/module").asBoolean()).isTrue();
      assertThat(check.at("/report/signed").asBoolean()).as(check.toString()).isTrue();

      // GateRook's own module: nobody is asked whether they trust it.
      other.json(other.admin("POST", "/api/plugins/install", "{\"stagingId\":\"" + check.path("stagingId").asString()
          + "\"}"), 200);
      JsonNode echo = other.module("echo");
      assertThat(echo.path("installed").asBoolean()).as(echo.toString()).isTrue();
      assertThat(echo.path("signed").asBoolean()).isTrue();
      assertThat(echo.path("version").asString()).isEqualTo("1.1.0");
      assertThat(channel.downloads()).as("the file was the package").isEqualTo(downloads);

      other.json(other.admin("POST", "/api/connections", """
          {"name":"echo-dev","type":"echo-db","env":"dev","config":{"host":"echo.example.com"},
           "secrets":{"/password":{"source":"local","value":"%s"}}}""".formatted(SECRET)), 201);
      other.endpoint("echo", "echo-dev");
      assertThat(other.modes("echo", "echo-dev")).as("the modes the connector declares")
          .containsEntry("echo_read", "READ_ONLY").containsEntry("echo_write", "APPROVAL");
      assertThat(other.tool("echo", "echo-dev", "echo_read").path("declaredBy").isNull()).isTrue();

      CoreClient.Answer again = other.upload("/api/plugins/check", "echo-1.1.0.jar", listed);
      assertThat(again.status()).as(again.body()).isEqualTo(409);
      assertThat(again.body()).contains("already installed");
    } finally {
      second.close();
    }
  }

  private static JsonNode installed(String id) throws Exception {
    for (JsonNode t : api.get("/api/connector-types").path("installed")) {
      if (t.path("id").asString().equals(id)) {
        return t;
      }
    }
    throw new AssertionError("Not installed: " + id);
  }

  /** Waits until the module is at {@code version} and ready. */
  private static JsonNode awaitVersion(String version) throws Exception {
    JsonNode echo = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      echo = api.module("echo");
      if (echo.path("version").asString().equals(version) && echo.path("state").asString().equals("ready")) {
        return echo;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("echo didn't become " + version + ": " + echo + "\n" + core.coreLog());
  }
}
