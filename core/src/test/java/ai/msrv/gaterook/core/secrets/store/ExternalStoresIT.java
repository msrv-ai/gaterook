package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionTexts;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.invocation.ConnectorInvoker;
import ai.msrv.gaterook.core.platform.FileGrants;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * External stores on a real core (ARCHITECTURE.md §10 S1): a Vault connected over the admin API, connections whose
 * password is a reference or Vault dynamic credentials, a reference to a store that isn't connected, Test reference,
 * SSH certificates, rows changed outside GateRook, Disconnect — and no value or token in any answer, result or log.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExternalStoresIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static FakeVault vault;
  /** The Vault of {@code vault-stage}: a server of its own, with a secret the first Vault doesn't have. */
  private static FakeVault stageVault;
  private static TestCore core;
  private static AdminMvc api;
  private static String storeId;

  @BeforeAll
  static void start() throws Exception {
    vault = new FakeVault();
    stageVault = new FakeVault();
    stageVault.responses.put("kv/data/stage/orders", "{\"data\":{\"data\":{\"password\":\""
        + FakeVault.DB_PASSWORD + "\",\"database\":\"orders\"},\"metadata\":{\"created_time\":"
        + "\"2026-10-16T09:00:00Z\",\"deletion_time\":\"\",\"destroyed\":false,\"version\":2}}}");
    core = TestCore.start("external-stores");
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
    if (vault != null) {
      // The core revoked what it still held when it stopped.
      assertThat(vault.revokedLeases).isNotEmpty();
      vault.close();
    }
    if (stageVault != null) {
      stageVault.close();
    }
  }

  private static ObjectNode vaultConfig(String address) {
    ObjectNode config = JSON.createObjectNode().put("address", address);
    config.putObject("auth").put("method", "approle").put("roleId", FakeVault.ROLE_ID);
    return config;
  }

  private static String connection(String name, String env, String source, String ref) {
    return """
        {"name":"%s","type":"pgfake","env":"%s","config":{"connection":{"host":"db.test","port":5432},
         "credentials":{"user":"app"}},"secrets":{"/credentials/password":{"source":"%s","ref":"%s"}}}"""
        .formatted(name, env, source, ref);
  }

  private static ai.msrv.gaterook.connector.api.ConnectorManifest manifest(String type) {
    return core.bean(ConnectorRegistry.class).requireManifest(type);
  }

  private static ConnectionConfig cfg(String name) {
    return core.bean(ConnectionConfigs.class).byName(name).orElseThrow().config();
  }

  private static JsonNode secretRow(String name) {
    return StreamSupport.stream(api.ok("/api/secrets").spliterator(), false)
        .filter(r -> r.path("name").asString().equals(name)).findFirst().orElseThrow(() -> new AssertionError(
            "no secret " + name));
  }

  @Test
  @Order(1)
  void aStoreOnAnAddressThatIsntOneIsRefused() {
    for (String address : List.of("http://vault.acme.internal", "https://169.254.169.254",
        "http://127.0.0.1:" + core.bean(LocalListener.class).port(), "https://user@vault.acme.internal")) {
      ObjectNode body = JSON.createObjectNode().put("kind", "vault").put("name", "bad");
      body.set("config", vaultConfig(address));
      body.putObject("secrets").put("/auth/secretId", FakeVault.SECRET_ID);
      assertThat(api.post("/api/secret-stores", body).status()).as(address).isEqualTo(422);
    }
    for (String address : List.of("http://169.254.169.254", "http://127.0.0.1:" + core.bean(LocalListener.class).port(),
        "http://user@vault.acme.internal")) {
      ObjectNode body = JSON.createObjectNode().put("kind", "vault").put("name", "bad");
      body.set("config", vaultConfig(address).put("allowPlainHttp", true));
      body.putObject("secrets").put("/auth/secretId", FakeVault.SECRET_ID);
      assertThat(api.post("/api/secret-stores", body).status()).as(address).isEqualTo(422);
    }
    ObjectNode invalidConsent = JSON.createObjectNode().put("kind", "vault").put("name", "bad");
    invalidConsent.set("config", vaultConfig(vault.address()).put("allowPlainHttp", "true"));
    invalidConsent.putObject("secrets").put("/auth/secretId", FakeVault.SECRET_ID);
    assertThat(api.post("/api/secret-stores", invalidConsent).status()).isEqualTo(422);
    assertThat(api.ok("/api/secret-stores")).isEmpty();
  }

  @Test
  @Order(2)
  void connectingAVaultKeepsItsSecretIdWriteOnly() {
    ObjectNode body = JSON.createObjectNode().put("kind", "vault").put("name", "vault").put("reason", "S1 test");
    body.set("config", vaultConfig(vault.address()).put("allowPlainHttp", true));
    body.putObject("secrets").put("/auth/secretId", FakeVault.SECRET_ID);
    JsonNode created = api.expect(201, HttpMethod.POST, "/api/secret-stores", body);
    storeId = created.path("id").asString();
    assertThat(created.path("kind").asString()).isEqualTo("vault");
    assertThat(created.path("verified").asBoolean()).isTrue();
    assertThat(created.path("signIn").get(0).path("field").asString()).isEqualTo("/auth/secretId");
    assertThat(created.path("signIn").get(0).path("state").asString()).isEqualTo("stored");
    assertThat(created.path("config").has("secretId")).isFalse();
    assertThat(created.path("config").path("allowPlainHttp").asBoolean()).isTrue();
    assertThat(api.ok("/api/secret-stores/" + storeId).path("config").path("allowPlainHttp").asBoolean()).isTrue();

    JsonNode test = api.expect(200, HttpMethod.POST, "/api/secret-stores/" + storeId + "/test", null);
    assertThat(test.path("ok").asBoolean()).as(test.toString()).isTrue();
    assertThat(test.path("identity").path("role").asString()).isEqualTo("gaterook");
    assertThat(api.ok("/api/secret-stores/" + storeId).path("state").asString()).isEqualTo("connected");

    JsonNode signIn = secretRow("vault/approle-secret-id");
    assertThat(signIn.path("ownerKind").asString()).isEqualTo("store");
    assertThat(signIn.path("source").asString()).isEqualTo("local");

    JsonNode form = api.ok("/api/connector-types/pgfake/form");
    assertThat(StreamSupport.stream(form.path("secretSources").spliterator(), false)
        .filter(s -> s.path("id").asString().equals("vault")).findFirst().orElseThrow().path("available")
        .asBoolean()).isTrue();
  }

  @Test
  @Order(3)
  void aConnectionReadsItsPasswordFromVaultAndNeverShowsIt() throws Exception {
    api.expect(201, HttpMethod.POST, "/api/connections", connection("stage-db", "stage", "reference",
        "vault://kv/stage/db#password"));

    JsonNode row = secretRow("stage-db/password");
    assertThat(row.path("source").asString()).isEqualTo("vault");
    assertThat(row.path("state").asString()).isEqualTo("reference");
    assertThat(row.path("ref").asString()).isEqualTo("vault://kv/stage/db#password");
    assertThat(row.path("store").asString()).isEqualTo("vault");
    assertThat(api.ok("/api/secret-stores/" + storeId).path("references").get(0).path("name").asString())
        .isEqualTo("stage-db/password");

    JsonNode tested = api.expect(200, HttpMethod.POST, "/api/connections/stage-db/test", null);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();

    // A call that echoes the password: the agent gets it masked, together with the Vault token.
    ConnectionConfig cfg = cfg("stage-db");
    ConnectorInvoker.Outcome outcome = core.bean(ConnectorInvoker.class).execute(new ConnectorInvoker.Request("tc_s1",
        cfg, new ToolCall("tc_s1", "sql_query", "stage-db", JSON.createObjectNode().put("sql", "SELECT SECRET"),
        null), core.bean(EnvironmentPolicies.class).limits(cfg.env()), MaskPolicy.defaults(cfg.env()), null, null));
    assertThat(outcome.ok()).isTrue();
    assertThat(JSON.writeValueAsString(outcome.result())).startsWith("{").doesNotContain(FakeVault.DB_PASSWORD)
        .contains("the password is");
    assertThat(outcome.maskedCount()).isPositive();
    assertThat(secretRow("stage-db/password").path("remoteVersion").asString()).isEqualTo("4");

    // The value comes from the cache now: Vault is asked once for both reads.
    int before = vault.to("kv/data/stage/db").size();
    Taint taint = new Taint();
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg, taint)) {
      assertThat(r.secret("/credentials/password").unsafeString()).isEqualTo(FakeVault.DB_PASSWORD);
      assertThat(r.meta("/credentials/password").source()).isEqualTo("reference");
    }
    assertThat(vault.to("kv/data/stage/db")).hasSize(before);
    assertThat(ai.msrv.gaterook.core.platform.Masker.standard().text("pw " + FakeVault.DB_PASSWORD, taint,
        MaskPolicy.SECRETS_ONLY).text()).doesNotContain(FakeVault.DB_PASSWORD);
  }

  @Test
  @Order(4)
  void testReferenceSaysWhatIsThereWithoutTheValue() {
    int readsBefore = vault.to("kv/data/").size();
    JsonNode mounts = api.expect(200, HttpMethod.POST, "/api/secret-stores/" + storeId + "/vault/browse",
        "{\"path\":\"\"}");
    assertThat(mounts.path("path").asString()).isEmpty();
    assertThat(mounts.path("entries")).hasSize(2);
    JsonNode folder = api.expect(200, HttpMethod.POST, "/api/secret-stores/" + storeId + "/vault/browse",
        "{\"path\":\"kv/stage\"}");
    assertThat(folder.path("entries")).hasSize(2);
    assertThat(folder.path("entries").get(1).path("path").asString()).isEqualTo("kv/stage/db");
    assertThat(folder.path("entries").get(1).path("directory").asBoolean()).isFalse();
    assertThat(folder.path("unsupportedEntries").asInt()).isZero();
    vault.listings.put("kv/metadata/mixed", "[\"db\",\"unsupported name\"]");
    JsonNode mixed = api.expect(200, HttpMethod.POST, "/api/secret-stores/" + storeId + "/vault/browse",
        "{\"path\":\"kv/mixed\"}");
    assertThat(mixed.path("unsupportedEntries").asInt()).isEqualTo(1);
    assertThat(mixed.path("entries")).hasSize(1);
    assertThat(mixed.path("entries").get(0).path("name").asString()).isEqualTo("db");
    assertThat(vault.to("kv/data/")).hasSize(readsBefore);
    assertThat(api.post("/api/secret-stores/" + storeId + "/vault/browse", "{\"path\":\"kv/../db\"}").status())
        .isEqualTo(422);
    JsonNode browseDenied = api.expect(422, HttpMethod.POST, "/api/secret-stores/" + storeId + "/vault/browse",
        "{\"path\":\"kv/forbidden\"}");
    assertThat(browseDenied.path("detail").asString()).contains("list permission", "kv/metadata/forbidden");

    JsonNode suggestions = api.expect(200, HttpMethod.POST, "/api/secret-stores/" + storeId + "/vault/references", null);
    assertThat(suggestions.path("complete").asBoolean()).isTrue();
    assertThat(suggestions.path("issues")).isEmpty();
    assertThat(StreamSupport.stream(suggestions.path("references").spliterator(), false).map(JsonNode::asString))
        .containsExactly("vault://kv/stage/db#password", "vault://kv/stage/db#user", "vault://kv1/app#api_key");
    assertThat(suggestions.toString()).doesNotContain(FakeVault.DB_PASSWORD, FakeVault.SECRET_ID, vault.token(1),
        "cnry-kv1-api-key");
    vault.deniedPaths.add("kv/metadata");
    JsonNode partial = api.expect(200, HttpMethod.POST, "/api/secret-stores/" + storeId + "/vault/references", null);
    assertThat(partial.path("complete").asBoolean()).isFalse();
    assertThat(partial.path("issues").get(0).path("reason").asString()).isEqualTo("denied");
    assertThat(partial.path("references").get(0).asString()).isEqualTo("vault://kv1/app#api_key");
    vault.deniedPaths.clear();

    JsonNode keys = api.expect(200, HttpMethod.POST, "/api/secret-refs/test", "{\"uri\":\"vault://kv/stage/db\","
        + "\"store\":\"" + storeId + "\"}");
    assertThat(keys.path("found").asBoolean()).isTrue();
    assertThat(keys.path("type").asString()).isEqualTo("json");
    assertThat(StreamSupport.stream(keys.path("keys").spliterator(), false).map(JsonNode::asString))
        .containsExactlyInAnyOrder("password", "user");
    assertThat(keys.toString()).doesNotContain(FakeVault.DB_PASSWORD, FakeVault.SECRET_ID, vault.token(1));

    JsonNode found = api.expect(200, HttpMethod.POST, "/api/secret-refs/test", "{\"uri\":\"vault://kv/stage/db"
        + "#password\"}");
    assertThat(found.path("found").asBoolean()).isTrue();
    assertThat(found.path("resolvedVersion").asString()).isEqualTo("4");
    assertThat(found.path("store").asString()).isEqualTo("vault");
    assertThat(found.path("type").asString()).isEqualTo("string");
    assertThat(found.path("hints").get(0).path("code").asString()).isEqualTo("ref.unpinned");

    JsonNode destroyed = api.expect(200, HttpMethod.POST, "/api/secret-refs/test",
        "{\"uri\":\"vault://kv/stage/db?version=2#password\"}");
    assertThat(destroyed.path("found").asBoolean()).isFalse();
    assertThat(destroyed.path("error").asString()).isEqualTo("Version 2 of kv/stage/db is destroyed.");
    assertThat(destroyed.path("reason").asString()).isEqualTo("gone");

    JsonNode denied = api.expect(200, HttpMethod.POST, "/api/secret-refs/test", "{\"uri\":\"vault://kv/forbidden#x\"}");
    assertThat(denied.path("error").asString()).isEqualTo("The gateway's token can't read kv/data/forbidden.");

    JsonNode dynamic = api.expect(200, HttpMethod.POST, "/api/secret-refs/test",
        "{\"uri\":\"vault://database/creds/prod-replica-readonly\"}");
    assertThat(dynamic.path("found").asBoolean()).isTrue();
    assertThat(dynamic.path("type").asString()).isEqualTo("dynamic");
    assertThat(vault.issued.get()).as("no database user just to test").isZero();

    assertThat(api.post("/api/secret-refs/test", "{\"uri\":\"vault://kv/../db\"}").status()).isEqualTo(422);
    JsonNode aws = api.expect(200, HttpMethod.POST, "/api/secret-refs/test",
        "{\"uri\":\"aws-sm://eu-central-1/stage/billing-api#webhook_secret\"}");
    assertThat(aws.path("reason").asString()).isEqualTo("not-connected");
  }

  @Test
  @Order(5)
  void aReferenceToAStoreThatIsntConnectedFailsWithItsName() {
    api.expect(201, HttpMethod.POST, "/api/connections", connection("billing-api", "stage", "aws-sm",
        "aws-sm://eu-central-1/stage/billing-api#webhook_secret"));
    JsonNode row = secretRow("billing-api/password");
    assertThat(row.path("source").asString()).isEqualTo("aws-sm");
    assertThat(row.path("state").asString()).isEqualTo("not-connected");
    assertThat(row.path("store").isNull()).isTrue();

    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg("billing-api"), new Taint())) {
      assertThatThrownBy(() -> r.secret("/credentials/password")).isInstanceOf(SecretUnavailableException.class)
          .hasMessage("billing-api/password can't be resolved: AWS Secrets Manager isn't connected.");
    }
    JsonNode tested = api.expect(200, HttpMethod.POST, "/api/connections/billing-api/test", null);
    assertThat(tested.path("ok").asBoolean()).isFalse();
    assertThat(tested.toString()).contains("AWS Secrets Manager isn't connected.");

    // A typed reference in the form is tested without saving anything.
    JsonNode draft = api.expect(200, HttpMethod.POST, "/api/connections/test", """
        {"type":"pgfake","env":"dev","config":{"connection":{"host":"db.test","port":5432},"credentials":{"user":"a"}},
         "secrets":{"/credentials/password":{"source":"reference","ref":"vault://kv/stage/db#password"}}}""");
    assertThat(draft.path("ok").asBoolean()).as(draft.toString()).isTrue();
  }

  @Test
  @Order(6)
  void dynamicCredentialsAreLeasedPerConnectionAndRevokedWithIt() throws Exception {
    api.expect(201, HttpMethod.POST, "/api/connections", connection("prod-replica", "prod", "vault-dynamic",
        "vault://database/creds/prod-replica-readonly"));
    assertThat(StreamSupport.stream(api.ok("/api/secrets").spliterator(), false)
        .map(r -> r.path("name").asString())).as("Short-lived, not a stored secret").doesNotContain(
            "prod-replica/password");
    // A reference that issues credentials can't be stored as a plain value.
    assertThat(api.post("/api/connections", connection("wrong", "dev", "reference",
        "vault://database/creds/prod-replica-readonly")).status()).isEqualTo(422);

    ConnectionConfig cfg = cfg("prod-replica");
    Taint taint = new Taint();
    String first;
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg, taint)) {
      assertThat(r.meta("/credentials/password").source()).isEqualTo("dynamic");
      SecretResolver.Login login = r.login("/credentials/password");
      first = login.version();
      assertThat(login.username()).isEqualTo("v-approle-prod-rep-1");
      assertThat(new String(login.password().chars())).isEqualTo(FakeVault.DYNAMIC_PASSWORD_PREFIX + "1");
      assertThat(login.version()).startsWith("lease ");
      assertThat(login.notAfter()).isNotNull();
      assertThat(r.meta("/credentials/password").notAfter()).isEqualTo(login.notAfter());
    }
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg, new Taint())) {
      assertThat(r.login("/credentials/password").version()).as("one lease shared by the calls").isEqualTo(first);
    }
    assertThat(vault.issued.get()).isEqualTo(1);
    JsonNode shortLived = api.ok("/api/secrets/short-lived");
    assertThat(shortLived.toString()).contains("Vault dynamic database user", "prod-replica")
        .doesNotContain(FakeVault.DYNAMIC_PASSWORD_PREFIX);

    api.expect(200, HttpMethod.DELETE, "/api/connections/prod-replica", "{\"confirmName\":\"prod-replica\","
        + "\"reason\":\"S1 test\"}");
    TestCore.await(() -> vault.revokedLeases.contains("database/creds/prod-replica-readonly/lease1"),
        "the lease revoked with its connection");
  }

  @Test
  @Order(7)
  void vaultSignsAnSshKeyForOneCall() throws Exception {
    ObjectNode config = JSON.createObjectNode();
    config.putObject("credentials").putObject("vault").put("store", "vault").put("mount", "ssh-client-signer")
        .put("role", "gw-runbooks");
    ConnectionConfig cfg = new ConnectionConfig("conn-ssh-1", "dev-vm", "ssh", Env.DEV, 1, config, Set.of(), null);
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg, new Taint())) {
      SecretResolver.SshCertificate cert = r.sshCertificate("/credentials/vault",
          KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic(),
          new SecretResolver.SshCertRequest("deploy", Duration.ofMinutes(5), "gw-call-1", null));
      assertThat(cert.openSshText()).startsWith("ssh-ed25519-cert-v01@openssh.com ");
      assertThat(cert.serial()).isEqualTo("5b:0f:21");
      assertThatThrownBy(() -> r.sshCertificate("/credentials/vault",
          KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic(),
          new SecretResolver.SshCertRequest("deploy", Duration.ofHours(2), null, null)))
          .hasMessage("An SSH certificate lives 1 to 30 minutes.");
    }
    assertThat(api.ok("/api/secrets/short-lived").toString()).contains("SSH certificate from Vault CA",
        "ssh-client-signer/sign/gw-runbooks");
    // The kit's card: "Principal deploy".
    assertThat(api.ok("/api/secrets/short-lived").findParents("mechanism").stream()
        .filter(r -> r.path("mechanism").asString().equals("SSH certificate from Vault CA")).findFirst()
        .orElseThrow().path("principal").asString()).isEqualTo("deploy");
  }

  @Test
  @Order(8)
  void aReferenceChangedOutsideIsNotFollowed() {
    assertThat(core.jdbc().update("update secret set ref_uri = 'vault://kv/prod/db#password' where name = "
        + "'stage-db/password'")).isEqualTo(1);
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg("stage-db"), new Taint())) {
      assertThatThrownBy(() -> r.secret("/credentials/password"))
          .hasMessageContaining("was changed outside GateRook");
    }
    assertThat(secretRow("stage-db/password").path("state").asString()).isEqualTo("changed-outside");

    // A store whose address was changed in the database isn't used at all.
    String config = core.jdbc().queryForObject("select config from external_store where id = ?", String.class,
        storeId);
    String moved = config.replace(vault.address(), "https://vault.evil.example");
    assertThat(moved).isNotEqualTo(config);
    assertThat(core.jdbc().update("update external_store set config = ? where id = ?", moved, storeId)).isEqualTo(1);
    assertThat(core.bean(StoreClients.class).row(storeId).verified()).isFalse();
    JsonNode test = api.expect(200, HttpMethod.POST, "/api/secret-stores/" + storeId + "/test", null);
    assertThat(test.path("ok").asBoolean()).as(test.toString()).isFalse();
    assertThat(test.path("error").asString()).contains("was changed outside GateRook");
    int requestsBeforeBrowse = vault.requests.size();
    JsonNode browse = api.expect(422, HttpMethod.POST, "/api/secret-stores/" + storeId + "/vault/browse",
        "{\"path\":\"kv/stage\"}");
    assertThat(browse.path("detail").asString()).contains("was changed outside GateRook");
    JsonNode suggestions = api.expect(422, HttpMethod.POST, "/api/secret-stores/" + storeId + "/vault/references", null);
    assertThat(suggestions.path("detail").asString()).contains("was changed outside GateRook");
    assertThat(vault.requests).hasSize(requestsBeforeBrowse);
    assertThat(api.ok("/api/secret-stores/" + storeId).path("verified").asBoolean()).isFalse();
    assertThat(core.bean(ai.msrv.gaterook.core.platform.IntegrityAlerts.class).has("secret-store", storeId)).isTrue();

    // The person reviews both and keeps them: the reference points where it now says.
    api.expect(200, HttpMethod.POST, "/api/secret-stores/" + storeId + "/confirm-integrity", "{\"reason\":\"ok\"}");
    assertThat(core.bean(ai.msrv.gaterook.core.platform.IntegrityAlerts.class).has("secret-store", storeId)).isFalse();
    assertThat(secretRow("stage-db/password").path("state").asString()).isEqualTo("reference");
    JsonNode back = api.expect(200, HttpMethod.PUT, "/api/secrets/" + secretRow("stage-db/password").path("id")
        .asString() + "/reference", "{\"uri\":\"vault://kv/stage/db#password\",\"reason\":\"back\"}");
    assertThat(back.path("ref").asString()).isEqualTo("vault://kv/stage/db#password");
  }

  @Test
  @Order(9)
  void disconnectingLeavesReferencesNotConnected() {
    assertThat(api.delete("/api/secret-stores/" + storeId, "{\"confirmName\":\"wrong\"}").status()).isEqualTo(422);
    JsonNode gone = api.expect(200, HttpMethod.DELETE, "/api/secret-stores/" + storeId,
        "{\"confirmName\":\"vault\",\"reason\":\"S1 test\"}");
    assertThat(gone.path("referencesNotConnected").toString()).contains("stage-db/password");
    assertThat(gone.path("secretsDestroyed").toString()).contains("vault/approle-secret-id");
    assertThat(secretRow("stage-db/password").path("state").asString()).isEqualTo("not-connected");
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg("stage-db"), new Taint())) {
      assertThatThrownBy(() -> r.secret("/credentials/password"))
          .hasMessage("stage-db/password can't be resolved: HashiCorp Vault isn't connected.");
    }

    // Connected again: the references of its kind are bound to it.
    ObjectNode body = JSON.createObjectNode().put("kind", "vault").put("name", "vault-2");
    body.set("config", vaultConfig(vault.address()));
    body.putObject("secrets").put("/auth/secretId", FakeVault.SECRET_ID);
    storeId = api.expect(201, HttpMethod.POST, "/api/secret-stores", body).path("id").asString();
    assertThat(secretRow("stage-db/password").path("state").asString()).isEqualTo("reference");
    assertThat(secretRow("stage-db/password").path("store").asString()).isEqualTo("vault-2");
  }

  @Test
  @Order(10)
  void aSecondVaultIsASourceOfItsOwnAndReferencesGoToTheOneChosen() {
    ObjectNode body = JSON.createObjectNode().put("kind", "vault").put("name", "vault-stage");
    body.set("config", vaultConfig(stageVault.address()));
    body.putObject("secrets").put("/auth/secretId", FakeVault.SECRET_ID);
    String second = api.expect(201, HttpMethod.POST, "/api/secret-stores", body).path("id").asString();
    assertThat(second).isNotEqualTo(storeId);

    // Each Vault is a source of its own, with the address that tells it apart.
    List<JsonNode> vaults = StreamSupport.stream(api.ok("/api/connector-types/pgfake/form").path("secretSources")
        .spliterator(), false).filter(s -> s.path("id").asString().equals("vault")).toList();
    assertThat(vaults).extracting(s -> s.path("store").asString()).containsExactly("vault-2", "vault-stage");
    assertThat(vaults).extracting(s -> s.path("detail").asString()).containsExactly(vault.address(),
        stageVault.address());
    assertThat(vaults).allSatisfy(s -> assertThat(s.path("available").asBoolean()).isTrue());

    // Two Vaults could hold vault://kv/…: without a choice each is asked in turn, by name, and the reference is
    // bound to the first that has it.
    String unchosen = """
        {"name":"orders-db","type":"pgfake","env":"dev","config":{"connection":{"host":"db.test","port":5432},
         "credentials":{"user":"app"}},"secrets":{"/credentials/password":{"source":"reference",
         "ref":"vault://kv/stage/orders#password"}}}""";
    JsonNode draft = api.expect(200, HttpMethod.POST, "/api/connections/test", unchosen);
    assertThat(draft.path("ok").asBoolean()).as(draft.toString()).isTrue();
    JsonNode found = api.expect(200, HttpMethod.POST, "/api/secret-refs/test",
        "{\"uri\":\"vault://kv/stage/orders#password\"}");
    assertThat(found.path("found").asBoolean()).isTrue();
    assertThat(found.path("store").asString()).isEqualTo("vault-stage");
    assertThat(found.path("hints").toString()).contains("Found in vault-stage, asked after vault-2 doesn't have it");
    api.expect(201, HttpMethod.POST, "/api/connections", unchosen);
    assertThat(secretRow("orders-db/password").path("store").asString()).isEqualTo("vault-stage");
    JsonNode tested = api.expect(200, HttpMethod.POST, "/api/connections/orders-db/test", null);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();

    // Both have kv/stage/db: the first by name wins, unless the person chose one.
    String both = unchosen.replace("orders-db", "both-db").replace("kv/stage/orders", "kv/stage/db");
    api.expect(201, HttpMethod.POST, "/api/connections", both);
    assertThat(secretRow("both-db/password").path("store").asString()).isEqualTo("vault-2");
    api.expect(201, HttpMethod.POST, "/api/connections", both.replace("both-db", "chosen-db").replace("\"ref\"",
        "\"store\":\"vault-stage\",\"ref\""));
    assertThat(secretRow("chosen-db/password").path("store").asString()).isEqualTo("vault-stage");
    assertThat(secretRow("stage-db/password").path("store").asString()).isEqualTo("vault-2");

    // Neither has it: nothing is saved, and the answer says what each Vault said.
    JsonNode missing = api.expect(422, HttpMethod.POST, "/api/connections", unchosen.replace("orders-db",
        "missing-db").replace("kv/stage/orders", "kv/stage/nowhere"));
    assertThat(missing.toString()).contains("isn't in a connected HashiCorp Vault",
        "vault-2 doesn't have it; vault-stage doesn't have it");
    assertThat(api.get("/api/connections/missing-db").status()).isEqualTo(404);
  }

  @Test
  @Order(11)
  void aVaultTokenIsReadFromTheFileThePersonChose() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("gw-agent-token", "");
    try {
      java.nio.file.Files.writeString(file, vault.token(41) + "\n");
      FileGrants grants = core.bean(FileGrants.class);
      ObjectNode body = JSON.createObjectNode().put("kind", "vault").put("name", "vault-agent");
      ObjectNode config = JSON.createObjectNode().put("address", vault.address());
      // A path the request names itself is never read.
      config.putObject("auth").put("method", "token-file").put("file", "/etc/passwd");
      body.set("config", config);
      JsonNode missing = api.expect(422, HttpMethod.POST, "/api/secret-stores", body);
      assertThat(missing.toString()).contains("Choose the file that holds the Vault token", "/files/~1auth~1file");

      body.putObject("files").put("/auth/file", grants.grant(file, FileGrants.Purpose.OPENAPI));
      assertThat(api.expect(422, HttpMethod.POST, "/api/secret-stores", body).toString())
          .contains("chosen for something else");

      // A store refused for another reason keeps the file choice for the next try.
      String grant = grants.grant(file, FileGrants.Purpose.VAULT_TOKEN);
      body.putObject("files").put("/auth/file", grant);
      body.put("name", "Vault Agent");
      api.expect(422, HttpMethod.POST, "/api/secret-stores", body);
      body.put("name", "vault-agent");
      JsonNode created = api.expect(201, HttpMethod.POST, "/api/secret-stores", body);
      String id = created.path("id").asString();
      assertThat(created.path("config").path("auth").path("method").asString()).isEqualTo("token-file");
      assertThat(created.path("config").path("auth").path("file").asString()).isEqualTo(file.toString());
      assertThat(created.path("signIn")).as("nothing of the token is stored").isEmpty();
      assertThat(api.expect(422, HttpMethod.POST, "/api/secret-stores", body.put("name", "vault-agent-2"))
          .toString()).as("the choice is used once").contains("expired");

      JsonNode test = api.expect(200, HttpMethod.POST, "/api/secret-stores/" + id + "/test", null);
      assertThat(test.path("ok").asBoolean()).as(test.toString()).isTrue();
      assertThat(test.path("identity").path("auth").asString()).isEqualTo("token-file");
      assertThat(vault.to("auth/token/lookup-self").getLast().getHeaders().get("X-Vault-Token"))
          .isEqualTo(vault.token(41));

      // An edit keeps the chosen file whatever the request names.
      ObjectNode edit = JSON.createObjectNode().put("version", api.ok("/api/secret-stores/" + id).path("version")
          .asLong());
      edit.set("config", config);
      JsonNode edited = api.expect(200, HttpMethod.PUT, "/api/secret-stores/" + id, edit);
      assertThat(edited.path("config").path("auth").path("file").asString()).isEqualTo(file.toString());
      api.expect(200, HttpMethod.DELETE, "/api/secret-stores/" + id, "{\"confirmName\":\"vault-agent\"}");
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  @Test
  @Order(12)
  void anyTextFieldCanBeReadFromVaultAtCallTime() throws Exception {
    String both = """
        {"name":"users-db","type":"pgfake","env":"dev","config":{"connection":{"host":"db.test","port":5432}},
         "secrets":{"/credentials/user":%s,"/credentials/password":%s}}""";
    String userRef = "{\"source\":\"reference\",\"store\":\"vault-stage\",\"ref\":\"vault://kv/stage/db#user\"}";
    String passwordRef = "{\"source\":\"reference\",\"store\":\"vault-stage\","
        + "\"ref\":\"vault://kv/stage/db#password\"}";
    // A text field is typed into the config or read from a store; it is never a value stored here.
    assertThat(api.expect(422, HttpMethod.POST, "/api/connections", both.formatted(
        "{\"source\":\"local\",\"value\":\"app\"}", passwordRef)).toString()).contains("Type the user");
    // A reference typed as a value is refused: a person sets it as the field's reference.
    assertThat(api.expect(422, HttpMethod.POST, "/api/connections", """
        {"name":"users-db","type":"pgfake","env":"dev","config":{"connection":{"host":"db.test","port":5432},
         "credentials":{"user":"vault://kv/stage/db#user"}},"secrets":{"/credentials/password":%s}}"""
        .formatted(passwordRef)).toString()).contains("Read it from the store", "/config/credentials/user");
    // Where the credentials go stays typed: whoever writes the store could send them elsewhere.
    assertThat(api.expect(422, HttpMethod.POST, "/api/connections", """
        {"name":"users-db","type":"pgfake","env":"dev","config":{"connection":{"port":5432},"credentials":{}},
         "secrets":{"/connection/host":%s,"/credentials/password":%s}}""".formatted(userRef, passwordRef))
        .toString()).contains("Type the host", "never read from a store");

    JsonNode draft = api.expect(200, HttpMethod.POST, "/api/connections/test", both.formatted(userRef, passwordRef));
    assertThat(draft.path("ok").asBoolean()).as(draft.toString()).isTrue();
    api.expect(201, HttpMethod.POST, "/api/connections", both.formatted(userRef, passwordRef));

    JsonNode user = secretRow("users-db/user");
    assertThat(user.path("source").asString()).isEqualTo("vault");
    assertThat(user.path("ref").asString()).isEqualTo("vault://kv/stage/db#user");
    assertThat(user.path("store").asString()).isEqualTo("vault-stage");
    JsonNode detail = api.ok("/api/connections/users-db");
    assertThat(detail.path("config").path("credentials").has("user")).isFalse();
    assertThat(detail.path("secrets").toString()).contains("/credentials/user", "/credentials/password");

    ConnectionConfig cfg = cfg("users-db");
    assertThat(cfg.string("/credentials/user")).isNull();
    Taint taint = new Taint();
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg, taint)) {
      assertThat(r.isSet("/credentials/user")).isTrue();
      SecretResolver.Login login = r.login("/credentials/password");
      assertThat(login.username()).isEqualTo("app");
      assertThat(new String(login.password().chars())).isEqualTo(FakeVault.DB_PASSWORD);
      assertThat(login.version()).isEqualTo("v4");
      assertThat(login.notAfter()).isNull();
      // The config of a call has the user, read from its store: a connector reads it like a typed one.
      ConnectionConfig call = ConnectionTexts.fill(cfg, manifest("pgfake"), r);
      assertThat(call.string("/credentials/user")).isEqualTo("app");
      assertThat(call.version()).isEqualTo(cfg.version());
    }
    // The user isn't a secret: an agent may see it; the password stays masked.
    String masked = ai.msrv.gaterook.core.platform.Masker.standard().text("user app, password "
        + FakeVault.DB_PASSWORD, taint, MaskPolicy.SECRETS_ONLY).text();
    assertThat(masked).contains("user app").doesNotContain(FakeVault.DB_PASSWORD);

    // Any text field: the user from a store next to a typed password, a note from the first Vault that has it.
    api.expect(201, HttpMethod.POST, "/api/connections", """
        {"name":"notes-db","type":"pgfake","env":"dev","config":{"connection":{"host":"db.test","port":5432},
         "credentials":{}},"secrets":{"/credentials/user":%s,"/credentials/password":{"source":"local",
         "value":"pw-typed-here-1"},"/connection/note":{"source":"reference",
         "ref":"vault://kv/stage/orders#database"}}}""".formatted(userRef));
    assertThat(secretRow("notes-db/connection-note").path("store").asString()).isEqualTo("vault-stage");
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg("notes-db"), new Taint())) {
      ConnectionConfig call = ConnectionTexts.fill(cfg("notes-db"), manifest("pgfake"), r);
      assertThat(call.string("/connection/note")).isEqualTo("orders");
      assertThat(call.string("/credentials/user")).isEqualTo("app");
      SecretResolver.Login login = r.login("/credentials/password");
      assertThat(login.username()).isEqualTo("app");
      assertThat(new String(login.password().chars())).isEqualTo("pw-typed-here-1");
    }
    JsonNode tested = api.expect(200, HttpMethod.POST, "/api/connections/notes-db/test", null);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();

    // An edit that leaves the user out keeps where it comes from; a user typed in its place replaces the reference.
    api.expect(200, HttpMethod.PUT, "/api/connections/users-db", """
        {"env":"dev","version":%d,"config":{"connection":{"host":"db.test","port":5433},"credentials":{}}}"""
        .formatted(detail.path("version").asLong()));
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg("users-db"), new Taint())) {
      assertThat(r.login("/credentials/password").username()).isEqualTo("app");
    }
    api.expect(200, HttpMethod.PUT, "/api/connections/users-db", """
        {"env":"dev","version":%d,"config":{"connection":{"host":"db.test","port":5433},"credentials":{"user":"x"}}}"""
        .formatted(api.ok("/api/connections/users-db").path("version").asLong()));
    assertThat(cfg("users-db").string("/credentials/user")).isEqualTo("x");
    assertThat(api.ok("/api/connections/users-db").path("secrets").toString()).doesNotContain("/credentials/user");
    // And a typed field can be read from a store in an edit.
    api.expect(200, HttpMethod.PUT, "/api/connections/users-db", """
        {"env":"dev","version":%d,"config":{"connection":{"host":"db.test","port":5433},"credentials":{}},
         "secrets":{"/credentials/user":%s}}""".formatted(api.ok("/api/connections/users-db").path("version")
        .asLong(), userRef));
    try (ScopedSecretResolver r = core.bean(SecretService.class).resolver(cfg("users-db"), new Taint())) {
      assertThat(r.login("/credentials/password").username()).isEqualTo("app");
    }
  }

  @Test
  @Order(20)
  void noAnswerCarriesAValueOrAToken() {
    List<String> canaries = List.of(FakeVault.DB_PASSWORD, FakeVault.SECRET_ID, FakeVault.DYNAMIC_PASSWORD_PREFIX,
        "hvs.cnry-token-");
    for (AdminMvc.Answer a : api.answers()) {
      for (String c : canaries) {
        assertThat(a.body()).as(a.method() + " " + a.target()).doesNotContain(c);
      }
      if (a.body() != null && a.body().startsWith("{") || a.body() != null && a.body().startsWith("[")) {
        assertThat(AdminMvc.secretLookingValues(a.json())).as(a.method() + " " + a.target()).isEmpty();
      }
    }
  }
}
