package ai.msrv.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorTypeChanged;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Catalog consumers observe committed rows after eviction, and never a rolled-back mutation. */
class EndpointCatalogInvalidationIT {

  @Test
  void endpointChangesNotifyAfterCommitAndEvictionWhileRollbackStaysSilent() {
    try (TestCore core = TestCore.start("catalog-invalidated")) {
      EndpointSnapshotService snapshots = core.bean(EndpointSnapshotService.class);
      EndpointConnections wiring = core.bean(EndpointConnections.class);
      ToolCatalog catalogs = core.bean(ToolCatalog.class);
      String endpointId = core.bean(EndpointService.class).get("default").id();
      core.connection("catalog-db", "pgfake", Env.DEV, "db.test");
      assertThat(catalogs.forEndpoint(snapshots.get(endpointId).orElseThrow()).tool("sql_query")).isEmpty();
      List<ToolCatalog.Catalog> observed = new CopyOnWriteArrayList<>();
      core.context().addApplicationListener(event -> {
        if (event instanceof PayloadApplicationEvent<?> payload
            && payload.getPayload() instanceof EndpointCatalogInvalidated invalidated
            && endpointId.equals(invalidated.endpointId())) {
          // Match notification consumers: rebuild on another thread, without the committing transaction's resources.
          observed.add(CompletableFuture.supplyAsync(() -> catalogs.forEndpoint(
              snapshots.get(endpointId).orElseThrow())).join());
        }
      });
      TransactionTemplate tx = new TransactionTemplate(core.bean(PlatformTransactionManager.class));

      tx.executeWithoutResult(status -> {
        wiring.add("default", "catalog-db", null);
        assertThat(observed).as("no notification before commit").isEmpty();
      });
      assertThat(observed).hasSize(1);
      assertThat(observed.getFirst().tool("sql_query")).isPresent();
      assertThat(observed.getFirst().tool("describe_connection").orElseThrow().inputSchema()
          .at("/properties/connection/enum")).extracting(n -> n.asString()).containsExactly("catalog-db");

      tx.executeWithoutResult(status -> {
        wiring.remove("default", "catalog-db", null);
        status.setRollbackOnly();
      });
      assertThat(observed).as("rollback does not invalidate clients").hasSize(1);
      assertThat(snapshots.get(endpointId).orElseThrow().connection("catalog-db")).isPresent();

      EndpointSnapshot.Pair query = snapshots.get(endpointId).orElseThrow().pairs().stream()
          .filter(p -> p.toolKey().equals("sql_query")).findFirst().orElseThrow();
      core.bean(EndpointTools.class).setMode("default", "sql_query", "catalog-db", Mode.OFF, null,
          query.rowVersion(), null);
      assertThat(observed).hasSize(2);
      assertThat(observed.getLast().tool("sql_query")).isEmpty();

      wiring.remove("default", "catalog-db", null);
      assertThat(observed).hasSize(3);
      assertThat(observed.getLast().tool("describe_connection").orElseThrow().inputSchema()
          .at("/properties/connection/enum")).isEmpty();
    }
  }

  @Test
  void sharedDependenciesInvalidateAllCatalogsOnlyAfterCommit() {
    try (TestCore core = TestCore.start("catalog-shared-invalidated")) {
      List<EndpointCatalogInvalidated> observed = new CopyOnWriteArrayList<>();
      core.context().addApplicationListener(event -> {
        if (event instanceof PayloadApplicationEvent<?> payload
            && payload.getPayload() instanceof EndpointCatalogInvalidated invalidated) {
          observed.add(invalidated);
        }
      });
      TransactionTemplate tx = new TransactionTemplate(core.bean(PlatformTransactionManager.class));
      tx.executeWithoutResult(status -> {
        core.context().publishEvent(new ConnectorTypeChanged("pgfake"));
        assertThat(observed).isEmpty();
      });
      assertThat(observed).containsExactly(new EndpointCatalogInvalidated(null));

      observed.clear();
      tx.executeWithoutResult(status -> {
        core.bean(EndpointSnapshotService.class).invalidateAll();
        assertThat(observed).isEmpty();
        status.setRollbackOnly();
      });
      assertThat(observed).isEmpty();

      tx.executeWithoutResult(status -> {
        core.bean(EndpointSnapshotService.class).invalidateAll();
        assertThat(observed).isEmpty();
      });
      assertThat(observed).containsExactly(new EndpointCatalogInvalidated(null));
    }
  }

  @Test
  void committedConnectorSwitchesAreVisibleBeforeCatalogReadersWake() {
    try (TestCore core = TestCore.start("catalog-connector-switch")) {
      core.connection("switch-db", "pgfake", Env.DEV, "db.test");
      core.bean(EndpointConnections.class).add("default", "switch-db", null);
      EndpointSnapshotService snapshots = core.bean(EndpointSnapshotService.class);
      ToolCatalog catalogs = core.bean(ToolCatalog.class);
      ConnectorRegistry registry = core.bean(ConnectorRegistry.class);
      String endpointId = core.bean(EndpointService.class).get("default").id();
      assertThat(catalogs.forEndpoint(snapshots.get(endpointId).orElseThrow()).tool("sql_query")).isPresent();
      List<ToolCatalog.Catalog> observed = new CopyOnWriteArrayList<>();
      core.context().addApplicationListener(event -> {
        if (event instanceof PayloadApplicationEvent<?> payload
            && payload.getPayload() instanceof EndpointCatalogInvalidated invalidated
            && invalidated.endpointId() == null) {
          observed.add(CompletableFuture.supplyAsync(() -> catalogs.forEndpoint(
              snapshots.get(endpointId).orElseThrow())).join());
        }
      });

      registry.setEnabled("pgfake", false, null);
      assertThat(observed).hasSize(1);
      assertThat(observed.getFirst().tool("sql_query")).isEmpty();
      assertThat(catalogs.forEndpoint(snapshots.get(endpointId).orElseThrow()).tool("sql_query")).isEmpty();
      observed.clear();
      TransactionTemplate tx = new TransactionTemplate(core.bean(PlatformTransactionManager.class));
      tx.executeWithoutResult(status -> {
        registry.setEnabled("pgfake", true, null);
        assertThat(registry.usable("pgfake")).as("switch is not visible before commit").isFalse();
        status.setRollbackOnly();
      });
      assertThat(registry.usable("pgfake")).isFalse();
      assertThat(observed).isEmpty();

      registry.setEnabled("pgfake", true, null);
      assertThat(observed).hasSize(1);
      assertThat(observed.getFirst().tool("sql_query")).isPresent();

      core.jdbc().update("update connector_type set impl_version = 'edited' where id = 'pgfake'");
      registry.synchronize();
      snapshots.invalidateAll();
      assertThat(observed.getLast().tool("sql_query")).isEmpty();
      observed.clear();
      registry.confirmIntegrity("pgfake", "Reviewed the connector type");
      assertThat(observed).isNotEmpty();
      assertThat(observed.getFirst().tool("sql_query")).isPresent();
    }
  }
}
