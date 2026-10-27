package ai.msrv.gaterook.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.platform.HmacKeys;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.platform.TestHttp;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import ai.msrv.gaterook.core.secrets.internal.crypto.Envelope;
import ai.msrv.gaterook.core.secrets.internal.crypto.KeysetFile;
import ai.msrv.gaterook.core.secrets.internal.crypto.Keysets;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretOwnerKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionRepository;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretVersionState;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local secrets and the master key on a real core (ARCHITECTURE.md M1.3, report 12 §8.1–8.2): first start, Replace,
 * rotation that resumes after a crash, backups, the locked mode with Restore and Create, OS protection on the fly.
 * Each test starts the core one or more times on its own home, as Electron would restart it.
 */
class SecretsIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
  private final List<MasterKeyChanged.Kind> keyEvents = new java.util.concurrent.CopyOnWriteArrayList<>();

  @AfterEach
  void stopAll() {
    contexts.forEach(ConfigurableApplicationContext::close);
  }

  @Test
  void firstStartCreatesTheKeyAndSecretsAreWriteOnlyAndVersioned() throws Exception {
    Path home = TestGateway.newHome("secrets-first");
    ConfigurableApplicationContext core = start(home);
    MasterKeyService masterKey = core.getBean(MasterKeyService.class);
    SecretService secrets = core.getBean(SecretService.class);

    assertThat(Files.getPosixFilePermissions(home.resolve("master.keyset")))
        .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
    MasterKeyStatus status = masterKey.status();
    assertThat(status.keys()).singleElement().satisfies(k -> assertThat(k.primary()).isTrue());
    assertThat(status.mode()).isEqualTo("0400");
    assertThat(status.problems()).contains("backup-needed", "no-os-protection").doesNotContain("perms");
    assertThat(status.locked()).isNull();

    SecretInfo devDb = secrets.createLocal(SecretOwner.connection("dev-db"), "/credentials/password", "dev-db/password",
        "pw-v1-0123".toCharArray());
    secrets.createLocal(SecretOwner.connection("dev-db"), "/credentials/login", "dev-db/login",
        "{\"username\":\"app\",\"password\":\"pw-login-77\"}".toCharArray());
    Taint taint = new Taint();
    try (ScopedSecretResolver resolver = secrets.resolver(SecretOwner.connection("dev-db"),
        Set.of("/credentials/password", "/credentials/login"), taint)) {
      SecretResolver.Secret secret = resolver.secret("/credentials/password");
      assertThat(new String(secret.chars())).isEqualTo("pw-v1-0123");
      assertThat(secret.toString()).isEqualTo("‹secret›");
      assertThat(secret.version()).isEqualTo("v1");
      SecretResolver.Login login = resolver.login("/credentials/login");
      assertThat(login.username()).isEqualTo("app");
      assertThat(new String(login.password().chars())).isEqualTo("pw-login-77");
      assertThat(resolver.isSet("/credentials/password")).isTrue();
      assertThat(resolver.meta("/credentials/password")).isEqualTo(new SecretResolver.Meta("local", "v1", null));
      assertThatThrownBy(() -> resolver.secret("/credentials/other")).isInstanceOf(SecretUnavailableException.class)
          .hasMessageContaining("not a secret field of this connection");
      assertThatThrownBy(() -> JSON.writeValueAsString(secret)).hasMessageContaining("Secrets are never serialized");
      resolver.close();
      assertThatThrownBy(secret::bytes).as("zeroed and unusable once the call ended")
          .hasMessageContaining("closed when its call ended");
    }
    assertThat(taint.isEmpty()).isFalse();
    try (ScopedSecretResolver other = secrets.resolver(SecretOwner.connection("prod-db"),
        Set.of("/credentials/password"), new Taint())) {
      assertThatThrownBy(() -> other.secret("/credentials/password")).hasMessageContaining("not set");
    }

    SecretService.ReplaceResult replaced = secrets.replace(devDb.id(), "pw-v2-4567".toCharArray(), true,
        "rotated by hand");
    assertThat(replaced.version()).isEqualTo(2);
    assertThat(replaced.tested()).isTrue();
    assertThat(secrets.get(devDb.id())).satisfies(info -> {
      assertThat(info.version()).isEqualTo(2);
      assertThat(info.state()).isEqualTo("stored");
      assertThat(info.ownerLabel()).isEqualTo("dev-db");
      assertThat(info.usedBy()).containsExactly("dev");
      assertThat(info.canRotate()).isFalse();
    });
    assertThat(resolve(secrets, "dev-db", "/credentials/password")).isEqualTo("pw-v2-4567");
    SecretVersionRepository versions = core.getBean(SecretVersionRepository.class);
    assertThat(versions.findBySecretIdAndSecretVersion(devDb.id(), 1).orElseThrow().getState())
        .isEqualTo(SecretVersionState.PREVIOUS);
    secrets.replace(devDb.id(), "pw-v3-8901".toCharArray(), false, null);
    assertThat(versions.findBySecretIdAndSecretVersion(devDb.id(), 1).orElseThrow().getWrappedDek())
        .as("only one previous version survives").isNull();

    JdbcTemplate jdbc = jdbc(core);
    assertThat(jdbc.queryForList("select subject, change_from, change_to, reason from setup_change order by id"))
        .extracting(r -> r.get("subject") + " " + r.get("change_from") + "→" + r.get("change_to") + " " + r.get("reason"))
        .containsExactly("dev-db/password v1→v2 (replaced) rotated by hand", "dev-db/password v2→v3 (replaced) null");
    List<String> chain = jdbc.queryForList("select prev_sha256 || ':' || row_sha256 from setup_change order by id",
        String.class);
    assertThat(chain.get(0)).startsWith("0".repeat(64) + ":");
    assertThat(chain.get(1)).startsWith(chain.get(0).substring(65) + ":");

    SecretInfo prodDb = secrets.createLocal(SecretOwner.connection("prod-db"), "/credentials/password",
        "prod-db/password", "pw-prod-1".toCharArray());
    assertThatThrownBy(() -> secrets.replace(prodDb.id(), "pw-prod-2".toCharArray(), false, " "))
        .isInstanceOf(SecretService.SecretRuleException.class).hasMessageContaining("required");
    SecretInfo flaky = secrets.createLocal(SecretOwner.connection("flaky-db"), "/credentials/password",
        "flaky-db/password", "pw-flaky-1".toCharArray());
    SecretService.ReplaceResult failed = secrets.replace(flaky.id(), "pw-flaky-2".toCharArray(), true, null);
    assertThat(failed.tested()).isFalse();
    assertThat(failed.state()).isEqualTo("testing");
    assertThat(resolve(secrets, "flaky-db", "/credentials/password")).as("version 1 stays in use").isEqualTo("pw-flaky-1");

    HmacKeys macs = core.getBean(HmacKeys.class);
    byte[] mac = macs.mac(HmacKeys.Purpose.ROW_MAC, "row".getBytes(StandardCharsets.UTF_8));
    assertThat(mac).hasSize(32);
    assertThat(macs.verify(HmacKeys.Purpose.ROW_MAC, "row".getBytes(StandardCharsets.UTF_8), mac)).isTrue();
    assertThat(macs.verify(HmacKeys.Purpose.HANDLES, "row".getBytes(StandardCharsets.UTF_8), mac)).isFalse();

    HttpResponse<String> list = http(core).signedGet("/api/secrets");
    assertThat(list.statusCode()).isEqualTo(200);
    JsonNode rows = JSON.readTree(list.body());
    assertThat(rows).hasSize(4);
    assertNoValueFields(rows);
    assertThat(list.body()).doesNotContain("pw-v3-8901").doesNotContain("pw-login-77");
    HttpResponse<String> rotate = http(core).signed("POST", "/api/secrets/" + devDb.id() + "/rotate", null);
    assertThat(rotate.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(rotate.body()).path("detail").asString()).contains("Replace it with a new value");
    HttpResponse<String> put = http(core).signed("PUT", "/api/secrets/" + devDb.id() + "/value",
        "{\"value\":\"pw-v4-api\",\"testBefore\":false,\"reason\":\"from the UI\"}");
    assertThat(put.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(put.body()).path("version").asInt()).isEqualTo(4);
    assertThat(put.body()).doesNotContain("pw-v4-api");
    HttpResponse<String> card = http(core).signedGet("/api/master-key");
    assertThat(card.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(card.body()).path("protects").asInt()).isEqualTo(4);
    // "Created Jul 14, never rotated": the first start made the key.
    assertThat(JSON.readTree(card.body()).path("createdAt").asString()).isNotBlank();
    assertThat(JSON.readTree(card.body()).path("rotatedAt").isNull()).isTrue();
    assertNoValueFields(JSON.readTree(card.body()));
  }

  @Test
  void rotationReWrapsEveryDataKeyAndResumesAfterACrash() throws Exception {
    Path home = TestGateway.newHome("secrets-rotate");
    ConfigurableApplicationContext core = start(home);
    SecretService secrets = core.getBean(SecretService.class);
    for (int i = 1; i <= 8; i++) {
      secrets.createLocal(SecretOwner.connection("conn-" + i), "/credentials/password", "conn-" + i + "/password",
          ("value-" + i + "-abcdef").toCharArray());
    }
    byte[] mac = core.getBean(HmacKeys.class).mac(HmacKeys.Purpose.ROW_MAC, new byte[] {1, 2, 3});
    MasterKeyService masterKey = core.getBean(MasterKeyService.class);
    masterKey.stopRotationAfter = 3;

    masterKey.rotate("yearly rotation");
    MasterKeyStatus.Rotation stopped = await(masterKey, "failed");
    assertThat(stopped.done()).isEqualTo(3);
    long target = stopped.targetKeyId();
    assertThat(core.getBean(SecretVersionRepository.class).countWrappedByOtherKeys(target)).isEqualTo(5);
    for (int i = 1; i <= 8; i++) {
      assertThat(resolve(secrets, "conn-" + i, "/credentials/password")).isEqualTo("value-" + i + "-abcdef");
    }
    stop(core);

    ConfigurableApplicationContext again = start(home);
    MasterKeyService resumed = again.getBean(MasterKeyService.class);
    assertThat(await(resumed, "done").targetKeyId()).isEqualTo(target);
    // Resumed while the core started, maybe done before the listeners existed: announced once they do.
    awaitHeard(again, "ROTATED");
    assertThat(again.getBean(SecretVersionRepository.class).countWrappedByOtherKeys(target)).isZero();
    SecretService secretsAgain = again.getBean(SecretService.class);
    for (int i = 1; i <= 8; i++) {
      assertThat(resolve(secretsAgain, "conn-" + i, "/credentials/password")).isEqualTo("value-" + i + "-abcdef");
    }
    assertThat(again.getBean(HmacKeys.class).mac(HmacKeys.Purpose.ROW_MAC, new byte[] {1, 2, 3}))
        .as("purpose keys survive rotation").isEqualTo(mac);
    assertThat(resumed.status().keys()).hasSize(2).filteredOn(MasterKeyStatus.Key::primary).singleElement()
        .satisfies(k -> assertThat(k.id()).isEqualTo(target));
    assertThat(resumed.status().keys()).filteredOn(k -> !k.primary()).singleElement()
        .satisfies(k -> assertThat(k.status()).isEqualTo("disabled"));
    assertThat(resumed.status().problems()).contains("backup-needed");
    assertThat(resumed.status().rotatedAt()).as("rotated when the new primary took over").isNotNull()
        .isAfterOrEqualTo(resumed.status().createdAt());

    // The backup of the new key lets the disabled one go.
    Path backup = home.getParent().resolve(home.getFileName() + "-backup.json");
    TestHttp http = http(again);
    String token = grant(http, backup, "master-key-backup");
    HttpResponse<String> backedUp = http.signed("POST", "/api/master-key/backup",
        "{\"saveToken\":\"" + token + "\",\"passphrase\":\"correct horse battery\"}");
    assertThat(backedUp.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(backedUp.body()).path("passphrase").asBoolean()).isTrue();
    assertThat(Files.getPosixFilePermissions(backup))
        .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    assertThat(resumed.status().keys()).singleElement().satisfies(k -> assertThat(k.id()).isEqualTo(target));
    assertThat(resumed.status().problems()).doesNotContain("backup-needed");
    assertThat(resumed.status().lastBackupAt()).isNotNull();
    assertThat(http.signed("POST", "/api/master-key/backup", "{\"saveToken\":\"" + token + "\"}").statusCode())
        .as("a save token works once").isEqualTo(422);
  }

  @Test
  void aSecretSealedWithTheOldKeyWhileTheRotationRunsIsReWrappedBeforeTheKeyIsDisabled() throws Exception {
    Path home = TestGateway.newHome("secrets-rotate-race");
    ConfigurableApplicationContext core = start(home);
    SecretService secrets = core.getBean(SecretService.class);
    MasterKeyService masterKey = core.getBean(MasterKeyService.class);
    secrets.createLocal(SecretOwner.connection("conn-1"), "/credentials/password", "conn-1/password",
        "value-1-abcdef".toCharArray());
    SecretRepository rows = core.getBean(SecretRepository.class);
    SecretVersionRepository versions = core.getBean(SecretVersionRepository.class);
    TransactionTemplate tx = new TransactionTemplate(core.getBean(PlatformTransactionManager.class));
    CountDownLatch sealed = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    AtomicReference<Throwable> failed = new AtomicReference<>();

    // A Replace or Create that sealed with the primary of the moment and commits after the rotation's last batch.
    Thread writer = Thread.ofVirtual().start(() -> {
      try {
        masterKey.sealing(() -> {
          SecretEntity secret = new SecretEntity("race-db/password", SecretKind.LOCAL, SecretOwnerKind.CONNECTION,
              "race-db", "/credentials/password", Instant.now());
          var value = masterKey.seal(secret.getId(), 1, "value-race-123".getBytes(StandardCharsets.UTF_8));
          sealed.countDown();
          try {
            assertThat(commit.await(30, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          tx.executeWithoutResult(s -> {
            SecretEntity saved = rows.save(secret);
            SecretVersionEntity version = new SecretVersionEntity(saved, 1, SecretVersionState.CURRENT,
                Envelope.FORMAT, Instant.now());
            version.seal(value.ciphertext(), value.wrappedDek(), value.keyId());
            versions.save(version);
            saved.setCurrentVersion(1);
          });
          return null;
        });
      } catch (Throwable e) {
        failed.set(e);
      }
    });
    assertThat(sealed.await(10, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> masterKey.seal("sec_x", 1, new byte[] {1})).as("only inside sealing()")
        .isInstanceOf(IllegalStateException.class);

    masterKey.rotate("race");
    Thread.sleep(500);
    assertThat(masterKey.rotation().state()).as("the rotation waits for the secret being written")
        .isEqualTo("running");
    commit.countDown();
    writer.join(Duration.ofSeconds(30));
    assertThat(failed.get()).isNull();

    long target = await(masterKey, "done").targetKeyId();
    assertThat(versions.countWrappedByOtherKeys(target)).isZero();
    assertThat(resolve(secrets, "race-db", "/credentials/password")).isEqualTo("value-race-123");
    assertThat(resolve(secrets, "conn-1", "/credentials/password")).isEqualTo("value-1-abcdef");
    assertThat(masterKey.status().keys()).filteredOn(k -> !k.primary()).singleElement()
        .satisfies(k -> assertThat(k.status()).isEqualTo("disabled"));
  }

  @Test
  void expiredPreviousVersionsAreShreddedByAnyReplace() throws Exception {
    Path home = TestGateway.newHome("secrets-shred");
    ConfigurableApplicationContext core = start(home);
    SecretService secrets = core.getBean(SecretService.class);
    SecretInfo first = secrets.createLocal(SecretOwner.connection("shred-a"), "/credentials/password",
        "shred-a/password", "pw-a-1-xyz".toCharArray());
    SecretInfo second = secrets.createLocal(SecretOwner.connection("shred-b"), "/credentials/password",
        "shred-b/password", "pw-b-1-xyz".toCharArray());
    secrets.replace(first.id(), "pw-a-2-xyz".toCharArray(), false, null);
    SecretVersionRepository versions = core.getBean(SecretVersionRepository.class);
    assertThat(versions.findBySecretIdAndSecretVersion(first.id(), 1).orElseThrow().getWrappedDek()).isNotNull();

    // Eight days later, while the core kept running: the next Replace of any secret shreds the expired version.
    new TransactionTemplate(core.getBean(PlatformTransactionManager.class)).executeWithoutResult(s -> core
        .getBean(SecretRepository.class).findById(first.id()).orElseThrow()
        .setRotatedAt(Instant.now().minus(Duration.ofDays(8))));
    secrets.replace(second.id(), "pw-b-2-xyz".toCharArray(), false, null);

    assertThat(versions.findBySecretIdAndSecretVersion(first.id(), 1).orElseThrow()).satisfies(v -> {
      assertThat(v.getState()).isEqualTo(SecretVersionState.DESTROYED);
      assertThat(v.getWrappedDek()).isNull();
      assertThat(v.getCiphertext()).isNull();
    });
    assertThat(versions.findBySecretIdAndSecretVersion(second.id(), 1).orElseThrow().getState())
        .as("a previous version within its 7 days stays").isEqualTo(SecretVersionState.PREVIOUS);
    assertThat(resolve(secrets, "shred-a", "/credentials/password")).isEqualTo("pw-a-2-xyz");
  }

  @Test
  void lockedModeAnswersOnlyTheMasterKeyAndRestoreUnlocksWithoutRestart() throws Exception {
    Path home = TestGateway.newHome("secrets-locked");
    ConfigurableApplicationContext core = start(home);
    SecretService secrets = core.getBean(SecretService.class);
    for (String name : List.of("dev-db", "orders-mongo", "app-logs")) {
      secrets.createLocal(SecretOwner.connection(name), "/credentials/password", name + "/password",
          (name + "-secret-1").toCharArray());
    }
    byte[] mac = core.getBean(HmacKeys.class).mac(HmacKeys.Purpose.AUDIT_HEAD, new byte[] {9});
    Path backup = home.getParent().resolve(home.getFileName() + "-backup.json");
    core.getBean(MasterKeyService.class).backup(backup, "pass phrase 1".toCharArray());
    stop(core);
    Files.delete(home.resolve("master.keyset"));

    ConfigurableApplicationContext locked = start(home);
    assertThat(locked.getBean(MasterKeyLock.class).reason()).contains(MasterKeyLock.Reason.MASTER_KEY_MISSING);
    TestHttp http = http(locked);
    HttpResponse<String> api = http.signedGet("/api/secrets");
    assertThat(api.statusCode()).isEqualTo(503);
    assertThat(api.headers().firstValue("Content-Type")).contains("application/problem+json");
    assertThat(JSON.readTree(api.body()).path("locked").asString()).isEqualTo("MASTER_KEY_MISSING");
    HttpResponse<String> mcp = http.send(http.request("/mcp/dev").header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
        .build());
    assertThat(mcp.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(mcp.body()).path("error").path("code").asInt()).isEqualTo(-32603);
    assertThat(JSON.readTree(mcp.body()).path("error").path("message").asString())
        .isEqualTo("MCP Gateway is locked: the master key was not found");
    JsonNode card = JSON.readTree(http.signedGet("/api/master-key").body());
    assertThat(card.path("locked").asString()).isEqualTo("MASTER_KEY_MISSING");
    assertThat(card.path("protects").asInt()).isEqualTo(3);
    assertThat(card.path("problems")).extracting(JsonNode::asString).contains("key-missing");
    // What a new key would destroy and what keeps working, for the locked screen (the only status that answers).
    JsonNode by = card.path("protectsBy").get(0);
    assertThat(card.path("protectsBy").size()).isEqualTo(1);
    assertThat(by.path("kind").asString()).isEqualTo("connection");
    assertThat(by.path("secrets").asInt()).isEqualTo(3);
    assertThat(by.path("owners")).extracting(JsonNode::asString).containsExactly("app-logs", "dev-db",
        "orders-mongo");
    assertThat(card.path("references").path("count").asInt()).isZero();
    assertThat(card.path("createdAt").asString()).as("the lost key's date stays in the database").isNotBlank();
    assertThatThrownBy(() -> resolve(locked.getBean(SecretService.class), "dev-db", "/credentials/password"))
        .hasMessageContaining("locked");
    assertThatThrownBy(() -> locked.getBean(HmacKeys.class).mac(HmacKeys.Purpose.ROW_MAC, new byte[1]))
        .isInstanceOf(IllegalStateException.class);

    HttpResponse<String> wrong = http.signed("POST", "/api/master-key/restore",
        "{\"openToken\":\"" + grant(http, backup, "master-key-restore") + "\",\"passphrase\":\"nope\"}");
    assertThat(wrong.statusCode()).isEqualTo(422);
    assertThat(JSON.readTree(wrong.body()).path("detail").asString()).contains("passphrase is wrong");
    HttpResponse<String> restored = http.signed("POST", "/api/master-key/restore",
        "{\"openToken\":\"" + grant(http, backup, "master-key-restore") + "\",\"passphrase\":\"pass phrase 1\"}");
    assertThat(restored.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(restored.body()).path("secretsReadable").asInt()).isEqualTo(3);

    assertThat(locked.getBean(MasterKeyLock.class).locked()).isFalse();
    assertThat(http.signedGet("/api/secrets").statusCode()).isEqualTo(200);
    assertThat(resolve(locked.getBean(SecretService.class), "dev-db", "/credentials/password"))
        .isEqualTo("dev-db-secret-1");
    assertThat(locked.getBean(HmacKeys.class).mac(HmacKeys.Purpose.AUDIT_HEAD, new byte[] {9})).isEqualTo(mac);
    assertThat(http.signed("POST", "/api/master-key/restore", "{\"openToken\":\"x\"}").statusCode())
        .as("restore only exists while locked").isEqualTo(422);
  }

  @Test
  void createReplacesAnUnusableKeyAndDestroysWhatTheOldOneProtected() throws Exception {
    Path home = TestGateway.newHome("secrets-create");
    ConfigurableApplicationContext core = start(home);
    SecretService secrets = core.getBean(SecretService.class);
    secrets.createLocal(SecretOwner.connection("dev-db"), "/credentials/password", "dev-db/password",
        "pw-1-abcdef".toCharArray());
    secrets.createLocal(SecretOwner.connection("github-mcp"), "/env/GITHUB_TOKEN", "github-mcp/token",
        "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789".toCharArray());
    stop(core);
    Files.setPosixFilePermissions(home.resolve("master.keyset"),
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    Files.writeString(home.resolve("master.keyset"), "{\"not\":\"a keyset\"}");

    ConfigurableApplicationContext locked = start(home);
    assertThat(locked.getBean(MasterKeyLock.class).reason()).contains(MasterKeyLock.Reason.MASTER_KEY_INVALID);
    TestHttp http = http(locked);
    assertThat(http.signed("POST", "/api/master-key/create", "{\"confirm\":\"yes\"}").statusCode()).isEqualTo(422);
    HttpResponse<String> created = http.signed("POST", "/api/master-key/create",
        "{\"confirm\":\"danger\",\"reason\":\"lost the backup\"}");
    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(created.body()).path("needValue")).extracting(JsonNode::asString)
        .containsExactly("dev-db/password", "github-mcp/token");

    assertThat(locked.getBean(MasterKeyLock.class).locked()).isFalse();
    try (var listing = Files.list(home)) {
      assertThat(listing.map(p -> p.getFileName().toString()))
          .anyMatch(n -> n.startsWith("master.keyset.replaced-")).contains("master.keyset");
    }
    SecretService after = locked.getBean(SecretService.class);
    assertThat(after.list()).extracting(SecretInfo::state).containsOnly("needs-value");
    assertThatThrownBy(() -> resolve(after, "dev-db", "/credentials/password")).hasMessageContaining("needs a value");
    JdbcTemplate jdbc = jdbc(locked);
    assertThat(jdbc.queryForObject("select count(*) from secret_version where wrapped_dek is not null", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("select reason from setup_change where subject = 'master key' order by id desc "
        + "limit 1", String.class)).isEqualTo("lost the backup");
    SecretInfo devDb = after.list().getFirst();
    after.replace(devDb.id(), "pw-new-123".toCharArray(), false, null);
    assertThat(resolve(after, "dev-db", "/credentials/password")).isEqualTo("pw-new-123");
  }

  @Test
  void aLostKeyThatNoSecretNeedsIsReplacedWithAWarning() throws Exception {
    Path home = TestGateway.newHome("secrets-lost");
    ConfigurableApplicationContext core = start(home);
    byte[] mac = core.getBean(HmacKeys.class).mac(HmacKeys.Purpose.HANDLES, new byte[] {7});
    stop(core);
    Files.delete(home.resolve("master.keyset"));
    keyEvents.clear();

    ConfigurableApplicationContext again = start(home);
    assertThat(again.getBean(MasterKeyLock.class).locked()).isFalse();
    MasterKeyStatus status = again.getBean(MasterKeyService.class).status();
    assertThat(status.warnings()).contains("new-key-created");
    assertThat(Files.exists(home.resolve("master.keyset"))).isTrue();
    assertThat(again.getBean(HmacKeys.class).mac(HmacKeys.Purpose.HANDLES, new byte[] {7}))
        .as("new purpose keys: old handles stop verifying").isNotEqualTo(mac);
    assertThat(keyEvents).as("RowMac (M1.4) leaves MAC'd rows unverified on this")
        .containsExactly(MasterKeyChanged.Kind.REPLACED_LOST);
    assertThat(again.getBean(AfterCommitListener.class).heard).as("announced once the listeners exist")
        .containsExactly("REPLACED_LOST");
    assertThat(jdbc(again).queryForObject("select change_to from setup_change where subject = "
        + "'master key'", String.class)).isEqualTo("new key (no secret needed the old one)");
  }

  @Test
  void aDamagedPurposeRootStartsTheCoreLockedNotBroken() throws Exception {
    Path home = TestGateway.newHome("secrets-root");
    ConfigurableApplicationContext core = start(home);
    core.getBean(SecretService.class).createLocal(SecretOwner.connection("dev-db"), "/credentials/password",
        "dev-db/password", "pw-root-1".toCharArray());
    stop(core);
    // The key ids still match: only decrypting the root shows that something is wrong.
    try (java.sql.Connection db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + home.resolve("gateway.db"))) {
      assertThat(db.createStatement().executeUpdate("update setting set value = json_set(value, '$.ct', 'AAAAAAAA') "
          + "where key = '" + MasterKeyService.MAC_ROOT_SETTING + "'")).isEqualTo(1);
    }

    ConfigurableApplicationContext locked = start(home);
    assertThat(locked.getBean(MasterKeyLock.class).reason()).contains(MasterKeyLock.Reason.MASTER_KEY_INVALID);
    TestHttp http = http(locked);
    HttpResponse<String> status = http.signedGet("/api/master-key");
    assertThat(status.statusCode()).isEqualTo(200);
    JsonNode card = JSON.readTree(status.body());
    assertThat(card.path("locked").asString()).isEqualTo("MASTER_KEY_INVALID");
    assertThat(card.path("keys")).as("nothing of the key is shown while it is unusable").isEmpty();
    assertThat(http.signedGet("/api/secrets").statusCode()).isEqualTo(503);
    assertThatThrownBy(() -> locked.getBean(HmacKeys.class).mac(HmacKeys.Purpose.ROW_MAC, new byte[1]))
        .isInstanceOf(IllegalStateException.class);

    HttpResponse<String> created = http.signed("POST", "/api/master-key/create", "{\"confirm\":\"danger\"}");
    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(locked.getBean(MasterKeyLock.class).locked()).isFalse();
    assertThat(locked.getBean(HmacKeys.class).mac(HmacKeys.Purpose.ROW_MAC, new byte[1])).hasSize(32);
  }

  @Test
  void aKeyChangeThatCantBeRecordedLeavesEverythingAsItWas() throws Exception {
    Path home = TestGateway.newHome("secrets-failclosed");
    ConfigurableApplicationContext core = start(home);
    core.getBean(SecretService.class).createLocal(SecretOwner.connection("dev-db"), "/credentials/password",
        "dev-db/password", "pw-fail-closed-1".toCharArray());
    MasterKeyService masterKey = core.getBean(MasterKeyService.class);
    Path backup = home.getParent().resolve(home.getFileName() + "-backup.json");
    masterKey.backup(backup, null);
    byte[] file = Files.readAllBytes(home.resolve("master.keyset"));
    AfterCommitListener heard = core.getBean(AfterCommitListener.class);

    // The Setup change can't be written (the audit is down, the database refuses): the rotation doesn't happen.
    refuseSetupChanges(core, true);
    assertThatThrownBy(() -> masterKey.rotate("yearly")).hasStackTraceContaining("setup changes are unavailable");
    assertThat(Files.readAllBytes(home.resolve("master.keyset"))).as("the file is as it was").isEqualTo(file);
    assertThat(masterKey.status().keys()).as("the keyset in use is as it was").hasSize(1);
    assertThat(masterKey.rotation().state()).isEqualTo("idle");
    JdbcTemplate jdbc = jdbc(core);
    assertThat(jdbc.queryForList("select rotation_target_key_id from master_key_state", Long.class))
        .allMatch(java.util.Objects::isNull);
    assertThat(resolve(core.getBean(SecretService.class), "dev-db", "/credentials/password"))
        .isEqualTo("pw-fail-closed-1");
    refuseSetupChanges(core, false);

    // The commit itself fails, after the new file was written: the file goes back to the keyset still in use.
    refuse(core, "create trigger test_refuse_key_state before update on master_key_state begin "
        + "select raise(abort, 'the commit fails (test)'); end");
    assertThatThrownBy(() -> masterKey.rotate("yearly")).hasStackTraceContaining("the commit fails");
    assertThat(Files.readAllBytes(home.resolve("master.keyset"))).isEqualTo(file);
    assertThat(masterKey.status().keys()).hasSize(1);
    assertThat(heard.heard).as("a change that didn't commit is never announced").isEmpty();
    refuse(core, "drop trigger test_refuse_key_state");
    stop(core);
    ConfigurableApplicationContext again = start(home);
    assertThat(again.getBean(MasterKeyService.class).status().keys()).as("nothing to resume after a restart")
        .hasSize(1);
    assertThat(again.getBean(MasterKeyService.class).rotation().state()).isEqualTo("idle");
    stop(again);

    Files.delete(home.resolve("master.keyset"));
    ConfigurableApplicationContext locked = start(home);
    MasterKeyService lockedKey = locked.getBean(MasterKeyService.class);
    refuseSetupChanges(locked, true);
    assertThatThrownBy(() -> lockedKey.restore(backup, null, null))
        .hasStackTraceContaining("setup changes are unavailable");
    assertThat(home.resolve("master.keyset")).as("no file the database never heard of").doesNotExist();
    assertThatThrownBy(() -> lockedKey.create("lost it")).hasStackTraceContaining("setup changes are unavailable");
    assertThat(home.resolve("master.keyset")).doesNotExist();
    try (var listing = Files.list(home)) {
      assertThat(listing.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("master.keyset."));
    }
    assertThat(locked.getBean(MasterKeyLock.class).reason()).contains(MasterKeyLock.Reason.MASTER_KEY_MISSING);
    assertThat(jdbc(locked).queryForObject("select count(*) from secret_version where "
        + "wrapped_dek is not null", Integer.class)).as("nothing was destroyed").isEqualTo(1);

    refuseSetupChanges(locked, false);
    assertThat(lockedKey.restore(backup, null, "found the backup").secretsReadable()).isEqualTo(1);
    assertThat(resolve(locked.getBean(SecretService.class), "dev-db", "/credentials/password"))
        .isEqualTo("pw-fail-closed-1");
  }

  @Test
  void oldKeysStillEnabledWithoutATargetFinishTheirRotationAtStart() throws Exception {
    Path home = TestGateway.newHome("secrets-unfinished");
    ConfigurableApplicationContext core = start(home);
    SecretService secrets = core.getBean(SecretService.class);
    for (int i = 1; i <= 3; i++) {
      secrets.createLocal(SecretOwner.connection("conn-" + i), "/credentials/password", "conn-" + i + "/password",
          ("value-" + i + "-unfinished").toCharArray());
    }
    stop(core);
    // The core stopped after writing a new primary and before committing the rotation target.
    KeysetFile file = new KeysetFile(home);
    var next = Keysets.withNewPrimary(file.load(null).handle());
    file.write(next, null);
    long primary = Envelope.primaryKeyId(next);

    ConfigurableApplicationContext again = start(home);
    MasterKeyService masterKey = again.getBean(MasterKeyService.class);
    assertThat(await(masterKey, "done").targetKeyId()).isEqualTo(primary);
    assertThat(again.getBean(SecretVersionRepository.class).countWrappedByOtherKeys(primary)).isZero();
    assertThat(masterKey.status().keys()).filteredOn(k -> !k.primary()).singleElement()
        .satisfies(k -> assertThat(k.status()).isEqualTo("disabled"));
    assertThat(jdbc(again).queryForObject("select change_to from setup_change where subject = "
        + "'master key' order by id desc limit 1", String.class)).isEqualTo("key " + primary + " (finishing a rotation)");
    assertThat(resolve(again.getBean(SecretService.class), "conn-2", "/credentials/password"))
        .isEqualTo("value-2-unfinished");
  }

  @Test
  void osProtectionRewritesTheFileOnTheFly() throws Exception {
    Path home = TestGateway.newHome("secrets-protect");
    ConfigurableApplicationContext core = start(home);
    SecretService secrets = core.getBean(SecretService.class);
    secrets.createLocal(SecretOwner.connection("dev-db"), "/credentials/password", "dev-db/password",
        "pw-protected-1".toCharArray());
    TestHttp http = http(core);
    String key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    assertThat(http.signed("POST", "/internal/keyset/protect", "{\"fileKey\":\"" + key + "\"}").statusCode())
        .isEqualTo(204);
    assertThat(Files.readString(home.resolve("master.keyset"))).contains("encryptedKeyset");
    assertThat(resolve(secrets, "dev-db", "/credentials/password")).isEqualTo("pw-protected-1");

    // The bootstrap line reads the key in either Base64 alphabet, and so does protect (FB FF FF → "+///").
    byte[] slashes = new byte[32];
    java.util.Arrays.fill(slashes, (byte) 0xff);
    slashes[0] = (byte) 0xfb;
    assertThat(http.signed("POST", "/internal/keyset/protect", "{\"fileKey\":\""
        + java.util.Base64.getEncoder().encodeToString(slashes) + "\"}").statusCode()).isEqualTo(204);
    assertThat(resolve(secrets, "dev-db", "/credentials/password")).isEqualTo("pw-protected-1");

    assertThat(http.signed("POST", "/internal/keyset/protect", "{\"fileKey\":null}").statusCode()).isEqualTo(204);
    assertThat(Files.readString(home.resolve("master.keyset"))).doesNotContain("encryptedKeyset");
    assertThat(http.signed("POST", "/internal/keyset/protect", "{\"fileKey\":\"short\"}").statusCode()).isEqualTo(422);
  }

  /**
   * Modules consume these events with {@code @TransactionalEventListener(AFTER_COMMIT)} (ARCHITECTURE.md §2.2), which
   * hears nothing published outside a transaction: every change must reach such a listener after it committed, with
   * the new keys already usable.
   */
  @Test
  void transactionalListenersHearEveryChangeAfterItCommits() throws Exception {
    Path home = TestGateway.newHome("secrets-events");
    ConfigurableApplicationContext core = start(home);
    SecretService secrets = core.getBean(SecretService.class);
    AfterCommitListener heard = core.getBean(AfterCommitListener.class);
    SecretInfo devDb = secrets.createLocal(SecretOwner.connection("dev-db"), "/credentials/password",
        "dev-db/password", "pw-events-1".toCharArray());
    SecretInfo flaky = secrets.createLocal(SecretOwner.connection("flaky-db"), "/credentials/password",
        "flaky-db/password", "pw-flaky-1".toCharArray());

    secrets.replace(devDb.id(), "pw-events-2".toCharArray(), false, null);
    secrets.replace(devDb.id(), "pw-events-3".toCharArray(), true, null);
    secrets.replace(flaky.id(), "pw-flaky-2".toCharArray(), true, null);
    assertThat(heard.heard).as("Replace, also after its test; not a version whose test failed")
        .containsExactly("dev-db v2", "dev-db v3");

    MasterKeyService masterKey = core.getBean(MasterKeyService.class);
    masterKey.rotate(null);
    await(masterKey, "done");
    awaitHeard(core, "ROTATED");
    TestHttp http = http(core);
    assertThat(http.signed("POST", "/internal/keyset/protect", "{\"fileKey\":\""
        + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}").statusCode()).isEqualTo(204);
    assertThat(http.signed("POST", "/internal/keyset/protect", "{\"fileKey\":null}").statusCode()).isEqualTo(204);
    assertThat(heard.heard).containsExactly("dev-db v2", "dev-db v3", "ROTATED", "PROTECTION_CHANGED",
        "PROTECTION_CHANGED");
    Path backup = home.getParent().resolve(home.getFileName() + "-backup.json");
    masterKey.backup(backup, null);
    stop(core);

    Files.delete(home.resolve("master.keyset"));
    ConfigurableApplicationContext locked = start(home);
    locked.getBean(MasterKeyService.class).restore(backup, null, null);
    assertThat(locked.getBean(AfterCommitListener.class).heard).as("the restored keys are in use when it is heard")
        .containsExactly("RESTORED");
    stop(locked);

    Files.setPosixFilePermissions(home.resolve("master.keyset"),
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    Files.writeString(home.resolve("master.keyset"), "garbage");
    ConfigurableApplicationContext invalid = start(home);
    invalid.getBean(MasterKeyService.class).create("lost both");
    assertThat(invalid.getBean(AfterCommitListener.class).heard).containsExactly("CREATED");
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

  private ConfigurableApplicationContext start(Path home) {
    // Command-line arguments, not builder defaults: application.yml would override those (gateway.dev: false).
    ConfigurableApplicationContext context = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .initializers(ctx -> {
          ctx.getBeanFactory().registerSingleton("testConnectionOwners", new TestOwners());
          ((GenericApplicationContext) ctx).registerBean(AfterCommitListener.class);
        })
        .listeners(event -> {
          if (event instanceof PayloadApplicationEvent<?> payload && payload.getPayload() instanceof MasterKeyChanged c) {
            keyEvents.add(c.kind());
          }
        })
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + TestGateway.LAUNCH_SECRET,
            "--server.port=0");
    contexts.add(context);
    return context;
  }

  private void stop(ConfigurableApplicationContext context) {
    context.close();
    contexts.remove(context);
  }

  /** A trigger that makes every Setup change fail, as an unavailable audit would. */
  private static void refuseSetupChanges(ConfigurableApplicationContext context, boolean refuse) {
    refuse(context, refuse ? "create trigger test_refuse_setup_change before insert on setup_change begin "
        + "select raise(abort, 'setup changes are unavailable (test)'); end" : "drop trigger test_refuse_setup_change");
  }

  /** The core has no JdbcTemplate bean (JPA only, decision 3): one over its primary pool. */
  private static JdbcTemplate jdbc(ConfigurableApplicationContext context) {
    return new JdbcTemplate(context.getBean(DataSource.class));
  }

  /** Runs {@code ddl} on the writer pool. */
  private static void refuse(ConfigurableApplicationContext context, String ddl) {
    JdbcTemplate jdbc = jdbc(context);
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
        .executeWithoutResult(s -> jdbc.execute(ddl));
  }

  private static TestHttp http(ConfigurableApplicationContext context) {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new TestHttp(port, TestGateway.launchSecret());
  }

  private static String grant(TestHttp http, Path path, String purpose) {
    HttpResponse<String> response = http.signed("POST", "/internal/files/grant",
        "{\"path\":\"" + path + "\",\"purpose\":\"" + purpose + "\"}");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body()).path("token").asString();
  }

  private static String resolve(SecretService secrets, String connection, String field) throws Exception {
    try (ScopedSecretResolver resolver = secrets.resolver(SecretOwner.connection(connection), Set.of(field),
        new Taint())) {
      return new String(resolver.secret(field).chars());
    }
  }

  /** Waits until the AFTER_COMMIT listener of {@code context} heard {@code what} (the rotation thread announces it). */
  private static void awaitHeard(ConfigurableApplicationContext context, String what) throws InterruptedException {
    AfterCommitListener listener = context.getBean(AfterCommitListener.class);
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!listener.heard.contains(what) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(listener.heard).contains(what);
  }

  private static MasterKeyStatus.Rotation await(MasterKeyService masterKey, String state) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      MasterKeyStatus.Rotation rotation = masterKey.rotation();
      if (rotation.state().equals(state)) {
        return rotation;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Rotation never reached " + state + ": " + masterKey.rotation());
  }

  /** The write-only invariant (§9): no response field could hold a secret value. */
  static void assertNoValueFields(JsonNode node) {
    if (node.isObject()) {
      for (Iterator<String> names = node.propertyNames().iterator(); names.hasNext(); ) {
        String name = names.next();
        assertThat(name.toLowerCase()).as("field " + name).isNotIn("value", "password", "token", "secret",
            "ciphertext", "wrappeddek", "plaintext");
        assertNoValueFields(node.get(name));
      }
    } else if (node.isArray()) {
      node.forEach(SecretsIT::assertNoValueFields);
    }
  }

  /**
   * A module's listener as §2.2 writes it: AFTER_COMMIT ({@code @Async} in production, synchronous here). Records what
   * it heard and, for key changes, whether the purpose keys were usable at that moment.
   */
  static final class AfterCommitListener {
    final List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ObjectProvider<HmacKeys> keys;

    AfterCommitListener(ObjectProvider<HmacKeys> keys) {
      this.keys = keys;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void secretChanged(SecretVersionChanged event) {
      heard.add(event.owner().id() + " v" + event.version());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void keyChanged(MasterKeyChanged event) {
      String usable;
      try {
        keys.getObject().mac(HmacKeys.Purpose.ROW_MAC, new byte[] {1});
        usable = "";
      } catch (RuntimeException e) {
        usable = " (keys unusable: " + e.getMessage() + ")";
      }
      heard.add(event.kind() + usable);
    }
  }

  /** Connections as the connections module (M1.4) will describe them: prod-* is prod, flaky-db fails its test. */
  static final class TestOwners implements SecretOwners {
    @Override
    public SecretOwner.Kind kind() {
      return SecretOwner.Kind.CONNECTION;
    }

    @Override
    public Optional<Owner> describe(String ownerId) {
      return Optional.of(new Owner(ownerId, ownerId.startsWith("prod") ? Env.PROD : Env.DEV, List.of("dev")));
    }

    @Override
    public Optional<TestOutcome> testWithVersion(String ownerId, String field, int version) {
      return Optional.of(ownerId.equals("flaky-db") ? new TestOutcome(false, "password authentication failed")
          : new TestOutcome(true, "Healthy · 12 ms"));
    }
  }
}
