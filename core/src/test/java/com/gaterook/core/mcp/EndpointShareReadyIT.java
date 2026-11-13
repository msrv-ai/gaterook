package com.gaterook.core.mcp;

import static com.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Env;
import com.gaterook.core.endpoints.EndpointConnections;
import com.gaterook.core.endpoints.EndpointService;
import com.gaterook.core.endpoints.EndpointShareService;
import com.gaterook.core.endpoints.TokenService;
import com.gaterook.core.platform.LocalListener;
import com.gaterook.testfixtures.TestCore;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** A reviewed file import must be callable immediately, without reopening or saving its connections. */
class EndpointShareReadyIT {
  @Test void completeImportedConnectionsAreUsableWhileIncompleteEntriesRemainUnavailable() {
    try (TestCore core = TestCore.start("endpoint-share-ready")) {
      core.connection("ready-database", "pgfake", Env.DEV, "database.test");
      core.connection("missing-database", "pgfake", Env.DEV, "other.test");
      EndpointService endpoints = core.bean(EndpointService.class);
      endpoints.create("ready-source", null, EndpointService.StartFrom.EMPTY, "test");
      core.bean(EndpointConnections.class).add("ready-source", "ready-database", "test");
      core.bean(EndpointConnections.class).add("ready-source", "missing-database", "test");
      EndpointShareService shares = core.bean(EndpointShareService.class);
      ObjectNode bundle = shares.export("ready-source", List.of("ready-database", "missing-database"), true);
      ObjectNode missing = (ObjectNode) bundle.path("connections").get(1);
      missing.putObject("secrets");
      missing.putArray("omittedSecretFields").add("/credentials/password");
      endpoints.disable("ready-source", "Keep existing endpoint paused");

      var result = shares.importBundle(bundle, List.of("ready-database", "missing-database"),
          true, true, "ready-import", "test");
      assertThat(result.path("connections").get(0).path("needsSetup").asBoolean()).isFalse();
      assertThat(result.path("connections").get(1).path("needsSetup").asBoolean()).isTrue();
      assertThat(endpoints.snapshot("ready-source").enabled()).isFalse();
      String token = core.bean(TokenService.class).issue("ready-import", "import-client", 7, "test").token();
      McpClient client = new McpClient(core.bean(LocalListener.class).port(), "ready-import", token);
      var listed = client.legacy("tools/list", args());
      assertThat(listed.status()).isEqualTo(200);
      assertThat(listed.result().path("tools")).anyMatch(tool -> tool.path("name").asString().equals("sql_query"));
      var queried = client.legacyCall("sql_query", args("connection", "ready-database-2", "sql", "SELECT 1"));
      assertThat(queried.status()).isEqualTo(200);
      assertThat(queried.result().path("isError").asBoolean()).isFalse();
      assertThat(client.legacyCall("sql_query", args("connection", "missing-database-2", "sql", "SELECT 1"))
          .result().path("isError").asBoolean()).isTrue();
    }
  }
}
