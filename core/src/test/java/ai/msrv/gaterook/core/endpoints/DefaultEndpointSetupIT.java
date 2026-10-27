package ai.msrv.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The default endpoint is ready before startup returns and is initialized only once per installation. */
class DefaultEndpointSetupIT {

  @Test
  void firstStartCreatesAnEndpointThatAcceptsConnectionsAndSurvivesRestart() {
    Path home;
    String id;
    try (TestCore core = TestCore.start("default-endpoint")) {
      home = core.home();
      EndpointService endpoints = core.bean(EndpointService.class);
      assertThat(endpoints.list()).extracting(EndpointService.Summary::slug).containsExactly("default");
      EndpointService.Summary endpoint = endpoints.get("default");
      id = endpoint.id();
      assertThat(endpoint.access()).isEqualTo("standard");
      assertThat(endpoint.status()).isEqualTo("running");
      assertThat(endpoint.connections()).isZero();
      assertThat(endpoint.tools().read()).isEqualTo(CoreTools.WITH_MODE.size());
      assertThat(endpoint.tools().approval()).isZero();
      assertThat(endpoint.tools().off()).isZero();
      assertThat(endpoint.blockedReason()).isNull();
      assertThat(endpoint.published()).isFalse();
      assertThat(endpoint.tokensActive()).isZero();
      assertThat(core.bean(SettingStore.class).find(DefaultEndpointSetup.INITIALIZED)).isPresent();

      core.connection("first-db", "pgfake", Env.DEV, "db.test");
      EndpointSnapshot wired = core.bean(EndpointConnections.class).add("default", "first-db", null);
      assertThat(wired.pairs()).filteredOn(p -> p.connectionName().equals("first-db"))
          .anySatisfy(p -> {
            assertThat(p.tool().name()).isEqualTo("sql_query");
            assertThat(p.mode()).isEqualTo(Mode.READ_ONLY);
          }).anySatisfy(p -> {
            assertThat(p.tool().name()).isEqualTo("sql_execute");
            assertThat(p.mode()).isEqualTo(Mode.APPROVAL);
          });
    }
    try (TestCore core = TestCore.reopen(home)) {
      EndpointService endpoints = core.bean(EndpointService.class);
      assertThat(endpoints.list()).extracting(EndpointService.Summary::id).containsExactly(id);
      assertThat(endpoints.get("default").connections()).isEqualTo(1);
      assertThat(endpoints.get("default").blockedReason()).isNull();
      endpoints.delete("default", "default", "Remove the starter endpoint");
    }
    try (TestCore core = TestCore.reopen(home)) {
      assertThat(core.bean(EndpointService.class).list()).isEmpty();
    }
  }

  @Test
  void anExistingSetupIsNotChangedOrGivenAnotherEndpoint() {
    try (TestCore core = TestCore.start("default-existing")) {
      EndpointService endpoints = core.bean(EndpointService.class);
      endpoints.delete("default", "default", null);
      EndpointService.Summary existing = endpoints.create("personal", "Keep my setup",
          EndpointService.StartFrom.EMPTY, null);
      // A home from before default initialization has no marker.
      core.jdbc().update("delete from setting where key = ?", DefaultEndpointSetup.INITIALIZED);
      core.bean(DefaultEndpointSetup.class).initialize();
      assertThat(endpoints.list()).isEqualTo(List.of(existing));
      assertThat(core.bean(SettingStore.class).find(DefaultEndpointSetup.INITIALIZED)).isPresent();
      endpoints.delete("personal", "personal", null);
      core.bean(DefaultEndpointSetup.class).initialize();
      assertThat(endpoints.list()).isEmpty();
    }
  }

  @Test
  void anOlderEmptyHomeGetsItsDefaultOnceTheKeyIsAvailable() {
    try (TestCore core = TestCore.start("default-empty")) {
      EndpointService endpoints = core.bean(EndpointService.class);
      endpoints.delete("default", "default", null);
      core.jdbc().update("delete from setting where key = ?", DefaultEndpointSetup.INITIALIZED);
      MasterKeyLock lock = core.bean(MasterKeyLock.class);
      lock.lock(MasterKeyLock.Reason.MASTER_KEY_MISSING);
      core.bean(DefaultEndpointSetup.class).initialize();
      assertThat(endpoints.list()).isEmpty();
      assertThat(core.bean(SettingStore.class).find(DefaultEndpointSetup.INITIALIZED)).isEmpty();
      lock.unlock();
      new TransactionTemplate(core.bean(PlatformTransactionManager.class)).executeWithoutResult(status ->
          core.context().publishEvent(new MasterKeyChanged(MasterKeyChanged.Kind.RESTORED)));
      TestCore.await(() -> !endpoints.list().isEmpty(), "the default endpoint after key recovery");
      assertThat(endpoints.list()).extracting(EndpointService.Summary::slug).containsExactly("default");
    }
  }
}
