package ai.msrv.gaterook.core.platform.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class StartupProtocolTest {

  private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

  private String[] lines() {
    return stdout.toString(StandardCharsets.UTF_8).split("\n");
  }

  @Test
  void progressThenOneReadyLine() {
    StartupProtocol protocol = StartupProtocol.writingTo(new PrintStream(stdout, true, StandardCharsets.UTF_8));

    protocol.running(StartupStep.DB_MIGRATIONS);
    protocol.done(StartupStep.DB_MIGRATIONS);
    protocol.ready(JsonNodeFactory.instance.objectNode().put("version", "0.4.0"));
    protocol.failed(StartupReason.STARTUP_FAILED, "too late", null);
    protocol.running(StartupStep.LOCAL_API);

    assertThat(lines()).containsExactly(
        "{\"v\":1,\"step\":\"db-migrations\",\"state\":\"running\"}",
        "{\"v\":1,\"step\":\"db-migrations\",\"state\":\"done\"}",
        "{\"v\":1,\"ready\":true,\"version\":\"0.4.0\"}");
  }

  @Test
  void aFailureMarksTheRunningStepFailed() {
    StartupProtocol protocol = StartupProtocol.writingTo(new PrintStream(stdout, true, StandardCharsets.UTF_8));

    protocol.running(StartupStep.LOCAL_API);
    protocol.failed(StartupReason.PORT_IN_USE, "Address already in use: 127.0.0.1:7465",
        JsonNodeFactory.instance.objectNode().put("log", "/home/u/.mcp-gateway/logs/core.log"));

    assertThat(lines()).containsExactly(
        "{\"v\":1,\"step\":\"local-api\",\"state\":\"running\"}",
        "{\"v\":1,\"step\":\"local-api\",\"state\":\"failed\"}",
        "{\"v\":1,\"ready\":false,\"reason\":\"PORT_IN_USE\",\"detail\":\"Address already in use: 127.0.0.1:7465\","
            + "\"log\":\"/home/u/.mcp-gateway/logs/core.log\"}");
    assertThat(protocol.finished()).isTrue();
  }
}
