package com.gaterook.core.mcp;

import static com.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.core.admin.AdminMvc;
import com.gaterook.core.endpoints.EndpointService;
import com.gaterook.core.endpoints.TokenService;
import com.gaterook.core.platform.LocalListener;
import com.gaterook.core.secrets.store.FakeVault;
import com.gaterook.core.secrets.SecretService;
import com.gaterook.testfixtures.TestCore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Store discovery is inert; only the person's signed Connect request consumes bootstrap credentials. */
class ConnectionImportStoresIT {
  private static TestCore core;
  private static AdminMvc admin;
  private static McpClient agent;
  private static FakeVault vault;

  @BeforeAll static void start() throws Exception {
    core = TestCore.start("connection-import-stores");
    core.bean(EndpointService.class).create("store-import", null, EndpointService.StartFrom.EMPTY, null);
    int port = core.bean(LocalListener.class).port();
    agent = new McpClient(port, "store-import", core.bean(TokenService.class).issue("store-import", "agent", 7, null).token());
    admin = new AdminMvc(core.context(), port);
    vault = new FakeVault();
  }

  @AfterAll static void stop() throws Exception {
    if (core != null) core.close();
    if (vault != null) vault.close();
  }

  @Test void explicitConnectUsesOnlySelectedBootstrapAndBindsRenamedStoreReferences() throws Exception {
    JsonNode session = session();
    ObjectNode manifest = manifest("discovered-vault");
    ObjectNode proposal = (ObjectNode) manifest.path("stores").get(0);
    proposal.putObject("secrets").putObject("/auth/secretId").put("source", "local").put("value", FakeVault.SECRET_ID);
    ((ObjectNode) proposal.path("secrets")).putObject("/auth/token").put("source", "file")
        .put("path", core.home().resolve("missing-unused-token").toString());
    ObjectNode candidate = manifest.withArray("connections").addObject().put("id", "database")
        .put("name", "vault-import-db").put("type", "pgfake").put("env", "dev");
    candidate.set("config", connectionConfig());
    candidate.putObject("secrets").putObject("/credentials/password").put("source", "reference")
        .put("ref", "vault://kv/stage/db#password").put("store", "discovered-vault");
    long before = admin.ok("/api/secret-stores").size();
    int reads = vault.requests.size();
    stage(session, manifest);
    assertThat(admin.ok("/api/secret-stores").size()).isEqualTo(before);
    assertThat(vault.requests).hasSize(reads);
    assertThat(admin.ok(path(session)).toString()).doesNotContain(FakeVault.SECRET_ID);
    assertThat(agent.legacyCall("connect_import_store", capability(session)).error().isMissingNode()).isFalse();

    ObjectNode request = args("name", "reviewed-vault");
    request.set("config", proposal.path("config").deepCopy());
    ((ObjectNode) request.path("config")).put("allowPlainHttp", true);
    request.putArray("preparedFields").add("/auth/secretId");
    String route = path(session) + "/stores/vault-proposal/connect";
    assertThat(admin.sendUnsigned(HttpMethod.POST, route, request).status()).isEqualTo(401);
    JsonNode connected = admin.expect(200, HttpMethod.POST, route, request);
    assertThat(connected.path("name").asString()).isEqualTo("reviewed-vault");
    assertThat(connected.path("signIn").size()).isEqualTo(1);
    assertThat(connected.toString()).doesNotContain(FakeVault.SECRET_ID);
    assertThat(vault.requests).hasSize(reads);
    assertThat(admin.expect(200, HttpMethod.POST, route, request).path("id")).isEqualTo(connected.path("id"));
    assertThat(admin.ok("/api/secret-stores").size()).isEqualTo(before + 1);
    JsonNode review = admin.ok(path(session));
    assertThat(review.at("/manifest/stores/0/connectedStore")).isEqualTo(connected.path("id"));
    assertThat(review.at("/manifest/connections/0/secretSources/0/store")).isEqualTo(connected.path("id"));
    // Testing is a separate explicit admin action and proves the staged AppRole credential was stored correctly.
    assertThat(admin.expect(200, HttpMethod.POST, "/api/secret-stores/" + connected.path("id").asString() + "/test", args())
        .path("ok").asBoolean()).isTrue();
    ObjectNode commit = args("endpoint", "store-import");
    commit.putArray("selections").addObject().put("candidateId", "database").put("name", "vault-import-db")
        .put("env", "dev").set("config", connectionConfig());
    admin.expect(200, HttpMethod.POST, path(session) + "/commit", commit);
    JsonNode detail = admin.ok("/api/connections/vault-import-db");
    assertThat(detail.toString()).doesNotContain(FakeVault.SECRET_ID, FakeVault.DB_PASSWORD);
    assertThat(core.bean(SecretService.class).get(detail.at("/secrets/0/secretId").asString()).store()).isEqualTo("reviewed-vault");
  }

  @Test void sourceFileIsReadOnlyOnConnectAndExistingBindingDoesNotResolveBootstrap() throws Exception {
    JsonNode session = session();
    ObjectNode manifest = manifest("file-vault");
    ObjectNode proposal = (ObjectNode) manifest.path("stores").get(0);
    Path source = core.home().resolve("selected-vault-secret-id");
    proposal.putObject("secrets").putObject("/auth/secretId").put("source", "file").put("path", source.toString());
    stage(session, manifest);
    ObjectNode request = args("name", "file-vault");
    request.set("config", proposal.path("config").deepCopy());
    ((ObjectNode) request.path("config")).put("allowPlainHttp", true);
    request.putArray("preparedFields").add("/auth/secretId");
    String route = path(session) + "/stores/vault-proposal/connect";
    admin.expect(422, HttpMethod.POST, route, request);
    Files.writeString(source, FakeVault.SECRET_ID + "\r\n");
    JsonNode connected = admin.expect(200, HttpMethod.POST, route, request);
    Files.delete(source);
    assertThat(admin.expect(200, HttpMethod.POST, "/api/secret-stores/" + connected.path("id").asString() + "/test", args())
        .path("ok").asBoolean()).isTrue();
    assertThat(admin.expect(200, HttpMethod.POST, route, request).path("id")).isEqualTo(connected.path("id"));

    JsonNode bind = session();
    ObjectNode another = manifest("alias-vault");
    ((ObjectNode) another.path("stores").get(0)).putObject("secrets").putObject("/auth/secretId")
        .put("source", "file").put("path", source.toString());
    stage(bind, another);
    long before = admin.ok("/api/secret-stores").size();
    JsonNode existing = admin.expect(200, HttpMethod.POST, path(bind) + "/stores/vault-proposal/connect",
        args("existingStore", connected.path("id").asString()));
    assertThat(existing.path("id")).isEqualTo(connected.path("id"));
    assertThat(admin.ok("/api/secret-stores").size()).isEqualTo(before);
    assertThat(admin.ok(path(bind)).at("/manifest/stores/0/connectedStore")).isEqualTo(connected.path("id"));
  }

  @Test void existingStoreIdentityIsRecheckedAfterTheUiMetadataWasFetched() throws Exception {
    ObjectNode create = args("kind", "vault", "name", "identity-vault");
    ObjectNode original = create.putObject("config").put("address", "https://vault.example.test").put("namespace", "/team/");
    original.putObject("auth").put("method", "token");
    create.putObject("secrets").put("/auth/token", "identity-test-token");
    JsonNode created = admin.expect(201, HttpMethod.POST, "/api/secret-stores", create);
    String storePath = "/api/secret-stores/" + created.path("id").asString();
    JsonNode session = session();
    ObjectNode manifest = manifest("identity-proposal");
    ObjectNode proposal = (ObjectNode) manifest.path("stores").get(0);
    proposal.set("config", original.deepCopy().put("address", "HTTPS://VAULT.EXAMPLE.TEST:443/").put("namespace", "team"));
    stage(session, manifest);
    JsonNode observed = admin.ok(storePath);
    assertThat(observed.path("verified").asBoolean()).isTrue();
    ObjectNode update = args().put("version", observed.path("version").asLong());
    update.set("config", original.deepCopy().put("address", "https://another-vault.example.test"));
    JsonNode changed = admin.expect(200, HttpMethod.PUT, storePath, update);
    assertThat(changed.path("verified").asBoolean()).isTrue();
    String route = path(session) + "/stores/vault-proposal/connect";
    ObjectNode useExisting = args("existingStore", created.path("id").asString());
    assertThat(admin.expect(422, HttpMethod.POST, route, useExisting).path("field").asString()).isEqualTo("/existingStore");
    assertThat(admin.ok(path(session)).at("/manifest/stores/0/connectedStore").isMissingNode()).isTrue();

    update.put("version", changed.path("version").asLong());
    update.set("config", original.deepCopy().put("namespace", "other-team"));
    changed = admin.expect(200, HttpMethod.PUT, storePath, update);
    admin.expect(422, HttpMethod.POST, route, useExisting);
    update.put("version", changed.path("version").asLong());
    update.set("config", original.deepCopy());
    changed = admin.expect(200, HttpMethod.PUT, storePath, update);
    assertThat(admin.expect(200, HttpMethod.POST, route, useExisting).path("id")).isEqualTo(created.path("id"));

    // A retry identifies the completed binding; it neither rebinds nor consumes bootstrap credentials.
    update.put("version", changed.path("version").asLong());
    update.set("config", original.deepCopy().put("namespace", "changed-after-binding"));
    admin.expect(200, HttpMethod.PUT, storePath, update);
    assertThat(admin.expect(200, HttpMethod.POST, route, useExisting).path("id")).isEqualTo(created.path("id"));
  }

  private static JsonNode session() {
    return admin.expect(201, HttpMethod.POST, "/api/connection-imports", args("repository", "example-repository", "endpoint", "store-import"));
  }

  private static String path(JsonNode session) { return "/api/connection-imports/" + session.path("id").asString(); }
  private static ObjectNode capability(JsonNode session) {
    return args("sessionId", session.path("id").asString(), "submissionToken", session.path("submissionToken").asString());
  }
  private static void stage(JsonNode session, ObjectNode manifest) throws Exception {
    Files.writeString(Path.of(session.path("importFile").asString()), manifest.toString());
    JsonNode result = agent.legacyCall("submit_connection_import", capability(session)).result();
    assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
    assertThat(result.toString()).doesNotContain(FakeVault.SECRET_ID);
  }
  private static ObjectNode manifest(String name) {
    ObjectNode manifest = args().put("version", 1);
    manifest.putArray("connections");
    ObjectNode store = manifest.putArray("stores").addObject().put("id", "vault-proposal").put("kind", "vault").put("name", name);
    store.putObject("config").put("address", vault.address()).putObject("auth").put("method", "approle").put("roleId", FakeVault.ROLE_ID);
    return manifest;
  }
  private static ObjectNode connectionConfig() {
    ObjectNode config = args();
    config.putObject("connection").put("host", "database.test");
    config.putObject("credentials").put("user", "reader");
    return config;
  }
}
