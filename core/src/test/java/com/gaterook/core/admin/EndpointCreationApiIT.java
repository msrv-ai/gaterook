package com.gaterook.core.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.core.endpoints.EndpointConnections;
import com.gaterook.core.endpoints.EndpointService;
import com.gaterook.core.endpoints.EndpointSnapshotService;
import com.gaterook.core.endpoints.EndpointTools;
import com.gaterook.core.endpoints.ToolCatalog;
import com.gaterook.core.platform.LocalListener;
import com.gaterook.core.platform.ProblemException;
import com.gaterook.testfixtures.TestCore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Presets configure only explicitly selected connections, even when other endpoints already use them. */
class EndpointCreationApiIT {

  @Test
  void readOnlyStageOffersOnlyStageConnectionsAndKeepsSelectedToolsReadOnly() {
    try (TestCore core = TestCore.start("endpoint-stage-selection")) {
      AdminMvc api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
      EndpointService endpoints = core.bean(EndpointService.class);
      EndpointSnapshotService snapshots = core.bean(EndpointSnapshotService.class);
      core.connection("stage-db", "pgfake", Env.STAGE, "stage.test");
      core.connection("stage-mcp", "mcpfake", Env.STAGE, "mcp.test");
      core.connection("dev-db", "pgfake", Env.DEV, "dev.test");
      core.connection("prod-db", "pgfake", Env.PROD, "prod.test");

      JsonNode preview = null;
      for (JsonNode start : api.ok("/api/endpoint-starts")) {
        if (start.path("startFrom").asString().equals("read-only-stage")) preview = start;
      }
      assertThat(preview).as("stage preset is available in the creation dialog").isNotNull();
      assertThat(preview.path("env").asString()).isEqualTo("stage");
      assertThat(preview.path("access").asString()).isEqualTo("read-only");
      assertThat(preview.path("connections")).extracting(JsonNode::asString).containsExactly("stage-db");
      assertThat(preview.at("/tools/approval").asInt()).isZero();
      assertThat(preview.at("/tools/bypass").asInt()).isZero();

      core.connection("later-stage-db", "pgfake", Env.STAGE, "later.test");
      JsonNode created = api.expect(201, HttpMethod.POST, "/api/endpoints", """
          {"slug":"selected-stage","startFrom":"read-only-stage","connections":["stage-db"]}
          """);
      assertThat(created.path("env").asString()).isEqualTo("stage");
      assertThat(created.path("access").asString()).isEqualTo("read-only");
      assertThat(created.path("tools")).as("creation uses the previewed stage tool modes")
          .isEqualTo(preview.path("tools"));
      var selected = endpoints.snapshot("selected-stage");
      assertThat(selected.connections()).extracting(w -> w.config().name()).containsExactly("stage-db");
      assertThat(selected.pairs()).filteredOn(p -> p.tool().name().equals("sql_query"))
          .extracting(p -> p.mode()).containsExactly(Mode.READ_ONLY);
      assertThat(selected.pairs()).filteredOn(p -> p.tool().name().equals("sql_execute"))
          .extracting(p -> p.mode()).containsExactly(Mode.OFF);
      assertThatThrownBy(() -> core.bean(EndpointTools.class).setMode("selected-stage", "sql_execute", "stage-db",
          Mode.APPROVAL, null, null, 0, null)).isInstanceOf(ProblemException.class)
          .hasMessageContaining("is read only");

      for (String connection : List.of("dev-db", "prod-db", "stage-mcp")) {
        JsonNode refused = api.expect(422, HttpMethod.POST, "/api/endpoints", """
            {"slug":"invalid-stage","startFrom":"read-only-stage","connections":["%s"]}
            """.formatted(connection));
        assertThat(refused.path("field").asString()).isEqualTo("/connections");
        assertThat(snapshots.bySlug("invalid-stage")).isEmpty();
      }
    }
  }

  @Test
  void emptyEndpointsKeepTheChosenEnvironmentWithoutChangingPresetDefaultsOrProductionIsolation() {
    try (TestCore core = TestCore.start("endpoint-environment")) {
      AdminMvc api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
      EndpointService endpoints = core.bean(EndpointService.class);
      EndpointConnections wiring = core.bean(EndpointConnections.class);
      EndpointSnapshotService snapshots = core.bean(EndpointSnapshotService.class);
      for (Env env : Env.values()) {
        String slug = "chosen-" + env.id();
        JsonNode created = api.expect(201, HttpMethod.POST, "/api/endpoints", """
            {"slug":"%s","startFrom":"empty","connections":[],"env":"%s"}
            """.formatted(slug, env.id()));
        assertThat(created.path("env").asString()).isEqualTo(env.id());
        assertThat(created.at("/connections/total").asInt()).isZero();
        assertThat(endpoints.snapshot(slug).coreTools()).as("GateRook's own reads are on")
            .allSatisfy(tool -> assertThat(tool.mode()).isEqualTo(Mode.READ_ONLY));
        assertThat(api.ok("/api/endpoints/" + slug).at("/endpoint/env").asString()).isEqualTo(env.id());
        assertThat(core.jdbc().queryForObject("select env from endpoint where slug = ?", String.class, slug)).isEqualTo(env.id());
      }
      api.expect(201, HttpMethod.POST, "/api/endpoints", """
          {"slug":"default-environment"}
          """);
      assertThat(endpoints.get("default-environment").env()).isEqualTo(Env.DEV);
      for (String invalid : List.of("", "testing", "development")) {
        JsonNode refused = api.expect(422, HttpMethod.POST, "/api/endpoints", """
            {"slug":"invalid-environment","env":"%s"}
            """.formatted(invalid));
        assertThat(refused.path("field").asString()).isEqualTo("/env");
        assertThat(snapshots.bySlug("invalid-environment")).isEmpty();
      }
      for (String preset : List.of("read-only-dev", "read-only-stage", "incident-response")) {
        String matchingEnv = switch (preset) {
          case "read-only-dev" -> "dev";
          case "read-only-stage" -> "stage";
          default -> "prod";
        };
        JsonNode created = api.expect(201, HttpMethod.POST, "/api/endpoints", """
            {"slug":"matching-%s","startFrom":"%s","env":"%s"}
            """.formatted(preset, preset, matchingEnv));
        assertThat(created.path("env").asString()).isEqualTo(matchingEnv);
        for (Env env : Env.values()) {
          if (env.id().equals(matchingEnv)) continue;
          JsonNode refused = api.expect(422, HttpMethod.POST, "/api/endpoints", """
              {"slug":"conflicting-preset","startFrom":"%s","env":"%s"}
              """.formatted(preset, env.id()));
          assertThat(refused.path("field").asString()).isEqualTo("/env");
          assertThat(snapshots.bySlug("conflicting-preset")).isEmpty();
        }
      }

      core.connection("environment-prod-db", "pgfake", Env.PROD, "production.test");
      core.connection("environment-dev-db", "pgfake", Env.DEV, "development.test");
      wiring.add("chosen-prod", "environment-prod-db", null);
      assertThat(endpoints.snapshot("chosen-prod").env()).isEqualTo(Env.PROD);
      assertThatThrownBy(() -> wiring.add("chosen-prod", "environment-dev-db", null))
          .isInstanceOf(ProblemException.class).hasMessageContaining("prod connections only");
      wiring.add("chosen-stage", "environment-dev-db", null);
      assertThat(endpoints.snapshot("chosen-stage").env()).as("connections still determine the endpoint environment").isEqualTo(Env.DEV);
    }
  }

  @Test
  void creationNeverInheritsConnectionsAndOnlyWiresTheExplicitSelection() {
    try (TestCore core = TestCore.start("endpoint-selection")) {
      AdminMvc api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
      EndpointService endpoints = core.bean(EndpointService.class);
      EndpointSnapshotService snapshots = core.bean(EndpointSnapshotService.class);
      ToolCatalog catalog = core.bean(ToolCatalog.class);
      core.connection("existing-prod-db", "pgfake", Env.PROD, "db.test");
      core.connection("selected-prod-vm", "sshfake", Env.PROD, "vm.test");
      core.connection("existing-dev-db", "pgfake", Env.DEV, "dev.test");
      core.connection("existing-stage-db", "pgfake", Env.STAGE, "stage.test");
      endpoints.create("existing-prod", null, EndpointService.StartFrom.INCIDENT_RESPONSE,
          List.of("existing-prod-db", "selected-prod-vm"), null);

      for (String preset : List.of("empty", "read-only-dev", "read-only-stage", "incident-response")) {
        String slug = "new-" + preset;
        JsonNode created = api.expect(201, HttpMethod.POST, "/api/endpoints",
            """
            {"slug":"%s","startFrom":"%s"}
            """.formatted(slug, preset));
        assertThat(created.path("connections").path("total").asInt()).isZero();
        assertThat(api.ok("/api/endpoints/" + slug).path("connections").isEmpty()).isTrue();
        var snapshot = snapshots.bySlug(slug).orElseThrow();
        assertThat(snapshot.connections()).isEmpty();
        assertThat(catalog.forEndpoint(snapshot).tool("sql_query")).isEmpty();
        assertThat(catalog.forEndpoint(snapshot).tool("ssh_run")).isEmpty();
      }
      assertThat(endpoints.get("new-read-only-dev").access()).isEqualTo("read-only");
      assertThat(endpoints.get("new-read-only-stage").access()).isEqualTo("read-only");
      assertThat(endpoints.get("new-read-only-stage").env()).isEqualTo(Env.STAGE);
      assertThat(endpoints.get("new-incident-response").env()).isEqualTo(Env.PROD);
      assertThat(endpoints.create("service-empty", null, EndpointService.StartFrom.INCIDENT_RESPONSE, null)
          .connections()).isZero();

      // A connection created after the preview must not expand the submitted selection.
      assertThat(api.ok("/api/endpoint-starts").toString()).contains("selected-prod-vm");
      core.connection("later-prod-db", "pgfake", Env.PROD, "later.test");
      api.expect(201, HttpMethod.POST, "/api/endpoints", """
          {"slug":"selected-prod","startFrom":"incident-response","connections":["selected-prod-vm"]}
          """);
      var selected = snapshots.bySlug("selected-prod").orElseThrow();
      assertThat(selected.connections()).extracting(w -> w.config().name()).containsExactly("selected-prod-vm");
      assertThat(selected.pairs()).filteredOn(p -> "selected-prod-vm".equals(p.connectionName()))
          .filteredOn(p -> p.tool().name().equals("ssh_exec")).extracting(p -> p.mode())
          .containsExactly(Mode.APPROVAL);
      assertThat(snapshots.bySlug("existing-prod").orElseThrow().connections())
          .extracting(w -> w.config().name()).containsExactly("existing-prod-db", "selected-prod-vm");
      assertThat(snapshots.bySlug("new-incident-response").orElseThrow().connections()).isEmpty();

      api.expect(201, HttpMethod.POST, "/api/endpoints", """
          {"slug":"explicit-empty","startFrom":"incident-response","connections":[]}
          """);
      assertThat(endpoints.get("explicit-empty").connections()).isZero();

      for (String names : List.of("[\"existing-dev-db\"]", "[\"missing\"]", "[null]",
          "[\"selected-prod-vm\",\"selected-prod-vm\"]", "[\"selected-prod-vm\",\"missing\"]")) {
        JsonNode error = api.expect(422, HttpMethod.POST, "/api/endpoints", """
            {"slug":"invalid-selection","startFrom":"incident-response","connections":%s}
            """.formatted(names));
        assertThat(error.path("field").asString()).isEqualTo("/connections");
        assertThat(snapshots.bySlug("invalid-selection")).as("invalid selection creates no partial endpoint").isEmpty();
      }
    }
  }
}
