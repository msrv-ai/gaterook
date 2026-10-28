package ai.msrv.gaterook.e2e.secretstores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.secrets.SecretStores;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The secret references of the demo world (design/BLUEPRINT.md §9, ARCHITECTURE.md §10 S1) on the assembled core with
 * the real built-in connectors: Vault and GCP Secret Manager connected, AWS Secrets Manager not, so
 * {@code billing-api/webhook-secret} says so; {@code prod-replica} reads a Vault KV reference; the SSH
 * hosts sign in with Vault's CA. Nothing contacts a store while seeding or here.
 */
class DemoReferencesIT {

  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("demo-references-it-launch-secret".getBytes(StandardCharsets.US_ASCII));

  @Test
  void theDemoWorldReferencesItsStoresAndNamesTheOneThatIsntConnected() throws Exception {
    try (ConfigurableApplicationContext core = start(newHome())) {
      Map<String, SecretStores.StoreInfo> stores = core.getBean(SecretStores.class).list().stream()
          .collect(Collectors.toMap(SecretStores.StoreInfo::name, s -> s));
      assertThat(stores).containsOnlyKeys("vault", "gcp-acme-stage");
      assertThat(stores.get("vault").kind()).isEqualTo("vault");
      assertThat(stores.get("vault").verified()).isTrue();
      assertThat(stores.get("vault").signIn()).singleElement().satisfies(s -> {
        assertThat(s.name()).isEqualTo("vault/approle-secret-id");
        assertThat(s.state()).isEqualTo("stored");
      });
      assertThat(stores.get("vault").references()).extracting(SecretStores.Reference::name)
          .containsExactlyInAnyOrder("stage-db/password", "prod-replica/password");
      assertThat(stores.get("gcp-acme-stage").references()).extracting(SecretStores.Reference::name)
          .containsExactly("billing-api/token");

      Map<String, SecretInfo> secrets = core.getBean(SecretService.class).list().stream()
          .collect(Collectors.toMap(SecretInfo::name, s -> s));
      assertThat(secrets.get("stage-db/password")).satisfies(s -> {
        assertThat(s.source()).isEqualTo("vault");
        assertThat(s.state()).isEqualTo("reference");
        assertThat(s.ref()).isEqualTo("vault://kv/stage/db#password");
      });
      assertThat(secrets.get("billing-api/token")).satisfies(s -> {
        assertThat(s.source()).isEqualTo("gcp-sm");
        assertThat(s.ref()).isEqualTo("gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest");
      });
      assertThat(secrets.get("billing-api/webhook-secret")).satisfies(s -> {
        assertThat(s.source()).isEqualTo("aws-sm");
        assertThat(s.state()).isEqualTo("not-connected");
        assertThat(s.store()).isNull();
      });
      assertThat(secrets.get("vault/approle-secret-id").source()).isEqualTo("local");
      assertThat(secrets.get("prod-replica/password")).satisfies(s -> {
        assertThat(s.source()).isEqualTo("vault");
        assertThat(s.state()).isEqualTo("reference");
        assertThat(s.ref()).isEqualTo("vault://kv/prod/prod-replica#password");
      });

      ConnectionConfigs configs = core.getBean(ConnectionConfigs.class);
      assertThat(configs.byName("prod-replica").orElseThrow().config().config()
          .at("/credentials/user").asString()).isEqualTo("read_only");
      ConnectionConfig billing = configs.byName("billing-api").orElseThrow().config();
      try (ScopedSecretResolver r = core.getBean(SecretService.class).resolver(billing, new Taint())) {
        assertThatThrownBy(() -> r.secret("/secretHeaders/0/value")).isInstanceOf(SecretUnavailableException.class)
            .hasMessage("billing-api/webhook-secret can't be resolved: AWS Secrets Manager isn't connected.");
        assertThat(r.meta("/auth/token").source()).isEqualTo("reference");
      }
      ConnectionConfig devVm = configs.byName("dev-vm").orElseThrow().config();
      assertThat(devVm.config().at("/credentials/method").asString()).isEqualTo("vaultSshCa");
      assertThat(devVm.config().at("/credentials/vault/store").asString()).isEqualTo("vault");

      assertThat(core.getBean(SecretStores.class).sources()).filteredOn(s -> s.id().equals("aws-sm")).singleElement()
          .satisfies(s -> {
            assertThat(s.available()).isFalse();
            assertThat(s.reason()).isEqualTo("Connect AWS Secrets Manager in Secrets first.");
          });
      assertThat(core.getBean(IntegrityAlerts.class).current()).isEmpty();
    }
  }

  private static ConfigurableApplicationContext start(String home) {
    return new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s", "--gateway.demo=true");
  }

  private static String newHome() {
    try {
      Path parent = Path.of("target", "gateway-homes").toAbsolutePath();
      Files.createDirectories(parent);
      return Files.createTempDirectory(parent, "demo-references-").toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
