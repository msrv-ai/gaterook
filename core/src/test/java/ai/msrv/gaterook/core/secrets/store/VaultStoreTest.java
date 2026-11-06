package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.store.vault.VaultSettings;
import ai.msrv.gaterook.core.secrets.store.vault.VaultStore;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Vault client against a fake Vault 2.2.1 (report 12 §5, §6): texts for 403, 307, destroyed and deleted versions,
 * the AppRole token in memory and in the call's taint, re-sign-in after a revoked token, renewal at two thirds,
 * leases revoked by path, the SSH CA.
 */
class VaultStoreTest {

  private FakeVault vault;
  private VaultStore store;
  private final List<CharSequence> taint = new ArrayList<>();
  private final List<char[]> handedOut = new ArrayList<>();
  private final TestClock clock = new TestClock(Instant.parse("2026-10-20T10:00:00Z"));

  @BeforeEach
  void start() throws Exception {
    vault = new FakeVault();
    store = new VaultStore(settings(vault.address()), field -> {
      assertThat(field).isEqualTo(VaultSettings.SECRET_ID);
      char[] value = FakeVault.SECRET_ID.toCharArray();
      handedOut.add(value);
      return value;
    }, clock);
  }

  @AfterEach
  void stop() {
    store.close();
    vault.close();
  }

  private static VaultSettings settings(String address) {
    return new VaultSettings(URI.create(address), null, null, VaultSettings.Auth.APPROLE, "approle", FakeVault.ROLE_ID,
        null, Duration.ofHours(1), 60, false);
  }

  private StoreCall call() {
    return StoreCall.within(v -> taint.add(v.toString()), Duration.ofSeconds(10), clock);
  }

  private RemoteValue read(String uri) {
    return store.read(SecretRef.parse(uri), call());
  }

  @Test
  void dottedCredentialKeysResolveFromBothKvVersionsWithoutChangingTheirPath() {
    vault.responses.put("kv/data/stage/db", "{\"data\":{\"data\":{\"database.password\":\"cnry-pass\","
        + "\"database.username\":\"app\"},\"metadata\":{\"version\":4}}}");
    vault.responses.put("kv1/app", "{\"data\":{\"database.password\":\"cnry-pass\",\"database.username\":\"app\"}}");
    for (String path : List.of("kv/stage/db", "kv1/app")) {
      try (RemoteValue password = read("vault://" + path + "#database.password");
          RemoteValue username = read("vault://" + path + "#database.username")) {
        assertThat(password.value()).isEqualTo("cnry-pass".getBytes(StandardCharsets.UTF_8));
        assertThat(username.value()).isEqualTo("app".getBytes(StandardCharsets.UTF_8));
      }
    }
    assertThat(vault.to("kv/data/stage/db")).hasSize(2);
    assertThat(vault.to("kv1/app")).hasSize(2);
  }

  @Test
  void browsesKvMountsAndOneFolderWithoutReadingValues() {
    assertThat(store.browse("", call()).entries()).containsExactly(
        new SecretStores.VaultEntry("kv", "kv", true), new SecretStores.VaultEntry("kv1", "kv1", true));
    assertThat(store.browse("kv", call()).entries()).containsExactly(
        new SecretStores.VaultEntry("stage", "kv/stage", true));
    SecretStores.VaultBrowse folder = store.browse("kv/stage", call());
    assertThat(folder.path()).isEqualTo("kv/stage");
    assertThat(folder.unsupportedEntries()).isZero();
    assertThat(folder.entries()).containsExactly(new SecretStores.VaultEntry("db", "kv/stage/db", true),
        new SecretStores.VaultEntry("db", "kv/stage/db", false));
    assertThat(store.browse("kv1", call()).entries()).containsExactly(
        new SecretStores.VaultEntry("app", "kv1/app", false));
    assertThat(vault.to("kv/metadata")).extracting(RecordedRequest::getMethod).containsOnly("LIST");
    assertThat(vault.to("kv/metadata").getFirst().getUrl().encodedPath()).isEqualTo("/v1/kv/metadata");
    assertThat(vault.to("kv1")).extracting(RecordedRequest::getMethod).containsExactly("LIST");
    assertThat(vault.to("kv/data/")).isEmpty();
    assertThat(vault.issued.get()).isZero();
    assertThat(folder.toString()).doesNotContain(FakeVault.DB_PASSWORD, FakeVault.SECRET_ID, vault.token(1));
    assertThat(vault.to("kv/metadata").getFirst().getHeaders().get("X-Vault-Token")).isEqualTo(vault.token(1));
  }

  @Test
  void browsingExplainsListPermissionAndHandlesEmptyFolders() {
    assertThatThrownBy(() -> store.browse("kv/forbidden", call()))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.DENIED))
        .hasMessageContainingAll("list kv/metadata/forbidden", "Grant list permission", "manually");
    assertThat(store.browse("kv/empty", call()).entries()).isEmpty();
    assertThatThrownBy(() -> store.browse("database", call()))
        .hasMessage("Choose a KV secrets engine to browse stored secrets.");
    assertThat(vault.issued.get()).isZero();
    assertThat(vault.to("database/")).isEmpty();
  }

  @Test
  void browsingRejectsUnsafeRequestsAndOmitsUnsupportedNames() {
    for (String path : List.of("/kv", "kv/", "kv//stage", "kv/../stage", "kv/./stage", "kv/%2e%2e", "kv/x?list=true",
        "kv/x#key", "kv/a b", "kv/é", "kv/" + "a".repeat(129), "vault://kv/stage")) {
      assertThatThrownBy(() -> store.browse(path, call())).as(path).isInstanceOf(StoreException.class);
    }
    assertThatThrownBy(() -> store.browse(null, call())).isInstanceOf(StoreException.class);
    assertThat(vault.requests).isEmpty();
    vault.listings.put("kv/metadata/unsafe", "[\"../escape\",\".\",\"/root\",\"foo/bar\",\"x?y\",\"with space\","
        + "\"password\",\"folder/\"]");
    SecretStores.VaultBrowse mixed = store.browse("kv/unsafe", call());
    assertThat(mixed.unsupportedEntries()).isEqualTo(6);
    assertThat(mixed.entries()).containsExactly(new SecretStores.VaultEntry("folder", "kv/unsafe/folder", true),
        new SecretStores.VaultEntry("password", "kv/unsafe/password", false));
    vault.mountListing = "{\"kv/\":{\"type\":\"kv\"},\"with space/\":{\"type\":\"kv\"},"
        + "\"team/kv/\":{\"type\":\"kv\"},\"database/\":{\"type\":\"database\"}}";
    SecretStores.VaultBrowse engines = store.browse("", call());
    assertThat(engines.unsupportedEntries()).isEqualTo(1);
    assertThat(engines.entries()).containsExactly(new SecretStores.VaultEntry("kv", "kv", true),
        new SecretStores.VaultEntry("team/kv", "team/kv", true));
    vault.listings.put("kv/metadata/unsafe", "[7]");
    assertThatThrownBy(() -> store.browse("kv/unsafe", call())).hasMessage("Vault returned an invalid folder listing.");
    assertThat(vault.to("kv/data/")).isEmpty();
  }

  @Test
  void browsingBoundsFolderAndMountEntryCounts() {
    var json = JsonMapper.builder().build();
    var keys = json.createArrayNode();
    var mounts = json.createObjectNode();
    for (int i = 0; i < 1000; i++) {
      keys.add("secret-" + i);
      mounts.putObject("kv-" + i + "/").put("type", "kv");
    }
    vault.listings.put("kv/metadata/large", keys.toString());
    assertThat(store.browse("kv/large", call()).entries()).hasSize(1000);
    vault.mountListing = mounts.toString();
    assertThat(store.browse("", call()).entries()).hasSize(1000);
    keys.add("one-too-many");
    mounts.putObject("one-too-many/").put("type", "kv");
    vault.listings.put("kv/metadata/large", keys.toString());
    vault.mountListing = mounts.toString();
    assertThatThrownBy(() -> store.browse("kv/large", call()))
        .hasMessageContainingAll("more than 1000 entries", "narrower folder");
    assertThatThrownBy(() -> store.browse("", call()))
        .hasMessageContainingAll("more than 1000 entries", "narrower folder");
  }

  @Test
  void discoversAllKvPathsAndKeysWithoutReturningValuesOrIssuingCredentials() {
    vault.listings.put("kv/metadata/stage/db", "[\"nested\"]");
    vault.responses.put("kv/data/stage/db/nested", "{\"data\":{\"data\":{\"token\":\"cnry-nested-token\"},"
        + "\"metadata\":{\"version\":1}}}");
    SecretStores.VaultReferences found = store.references(call());
    assertThat(found.complete()).isTrue();
    assertThat(found.issues()).isEmpty();
    assertThat(found.references()).containsExactly("vault://kv/stage/db#password", "vault://kv/stage/db#user",
        "vault://kv/stage/db/nested#token", "vault://kv1/app#api_key");
    assertThat(found.toString()).doesNotContain(FakeVault.DB_PASSWORD, FakeVault.SECRET_ID, vault.token(1),
        "cnry-nested-token", "cnry-kv1-api-key");
    assertThat(vault.issued.get()).isZero();
    assertThat(vault.requests).extracting(RecordedRequest::getMethod).containsOnly("GET", "LIST", "POST");
    assertThat(vault.requests.stream().filter(r -> r.getMethod().equals("POST")))
        .allSatisfy(r -> assertThat(r.getUrl().encodedPath()).isEqualTo("/v1/auth/approle/login"));
    int reads = vault.to("kv/data/").size();
    store.references(call());
    assertThat(vault.to("kv/data/")).hasSize(reads * 2);
  }

  @Test
  void discoveryKeepsAllowedReferencesWhenOtherPathsDenyListingOrReading() {
    vault.listings.put("kv/metadata", "[\"forbidden/\",\"forbidden\",\"stage/\"]");
    SecretStores.VaultReferences found = store.references(call());
    assertThat(found.complete()).isFalse();
    assertThat(found.references()).containsExactly("vault://kv/stage/db#password", "vault://kv/stage/db#user",
        "vault://kv1/app#api_key");
    assertThat(found.issues()).hasSize(2).allSatisfy(issue -> assertThat(issue.reason()).isEqualTo("denied"));
    assertThat(found.issues()).extracting(SecretStores.VaultDiscoveryIssue::message)
        .anySatisfy(message -> assertThat(message).contains("list kv/metadata/forbidden", "list permission"))
        .anySatisfy(message -> assertThat(message).contains("read kv/data/forbidden"));
    assertThat(vault.issued.get()).isZero();
  }

  @Test
  void discoveryReportsRootDenialAndExpiredDeadlineInsteadOfAnEmptyCompleteList() {
    vault.deniedPaths.add("sys/internal/ui/mounts");
    SecretStores.VaultReferences denied = store.references(call());
    assertThat(denied.complete()).isFalse();
    assertThat(denied.references()).isEmpty();
    assertThat(denied.issues()).singleElement().satisfies(issue -> {
      assertThat(issue.path()).isEmpty();
      assertThat(issue.reason()).isEqualTo("denied");
      assertThat(issue.message()).contains("list Vault secrets engines");
    });
    StoreCall expired = call();
    clock.advance(Duration.ofSeconds(11));
    int requests = vault.requests.size();
    SecretStores.VaultReferences timedOut = store.references(expired);
    assertThat(timedOut.complete()).isFalse();
    assertThat(timedOut.issues()).singleElement().satisfies(issue -> {
      assertThat(issue.reason()).isEqualTo("unavailable");
      assertThat(issue.message()).contains("ran out of time");
    });
    assertThat(vault.requests).hasSize(requests);
  }

  @Test
  void discoveryOmitsUnsupportedNamesAndDoesNotInterpretAKeyAsAQuery() {
    vault.listings.put("kv/metadata/stage", "[\"db\",\"unsupported name\"]");
    vault.responses.put("kv/data/stage/db", "{\"data\":{\"data\":{\"password\":\"cnry-value\","
        + "\"password?version=1\":\"cnry-other\",\"nested/key\":\"cnry-nested\"},\"metadata\":{\"version\":4}}}");
    SecretStores.VaultReferences found = store.references(call());
    assertThat(found.complete()).isFalse();
    assertThat(found.references()).containsExactly("vault://kv/stage/db#password", "vault://kv1/app#api_key");
    assertThat(found.issues()).hasSize(2).allSatisfy(issue -> assertThat(issue.reason()).isEqualTo("unsupported"));
    assertThat(found.toString()).doesNotContain("cnry-value", "cnry-other", "cnry-nested", "?version=1");
  }

  @Test
  void discoveryBoundsReferenceCountAndFolderDepth() {
    var json = JsonMapper.builder().build();
    var payload = json.createObjectNode();
    var data = payload.putObject("data").putObject("data");
    for (int i = 0; i < 5001; i++) {
      data.put("key-" + i, "cnry-large-value");
    }
    vault.responses.put("kv/data/stage/db", payload.toString());
    SecretStores.VaultReferences limited = store.references(call());
    assertThat(limited.complete()).isFalse();
    assertThat(limited.references()).hasSize(5000);
    assertThat(limited.issues()).anySatisfy(issue -> {
      assertThat(issue.reason()).isEqualTo("limit");
      assertThat(issue.message()).contains("5000 selectable keys");
    });
    vault.responses.clear();
    vault.listings.put("kv/metadata", "[\"deep/\"]");
    String path = "kv/metadata/deep";
    for (int i = 0; i < 70; i++) {
      vault.listings.put(path, "[\"d/\"]");
      path += "/d";
    }
    SecretStores.VaultReferences deep = store.references(call());
    assertThat(deep.complete()).isFalse();
    assertThat(deep.references()).containsExactly("vault://kv1/app#api_key");
    assertThat(deep.issues()).anySatisfy(issue -> {
      assertThat(issue.reason()).isEqualTo("limit");
      assertThat(issue.message()).contains("64 folder levels");
    });
  }

  @Test
  void readsAKeyOfAKvV2SecretWithItsVersion() {
    try (RemoteValue v = read("vault://kv/stage/db#password")) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo(FakeVault.DB_PASSWORD);
      assertThat(v.version()).isEqualTo("4");
      assertThat(v.created()).isEqualTo(Instant.parse("2026-10-16T09:00:00Z"));
      assertThat(v.type()).isEqualTo("string");
      assertThat(v.keys()).containsExactlyInAnyOrder("password", "user");
    }
    RecordedRequest get = vault.to("kv/data/stage/db").getFirst();
    assertThat(get.getHeaders().get("X-Vault-Token")).isEqualTo(vault.token(1));
    assertThat(get.getHeaders().get("X-Vault-Request")).isEqualTo("true");
    // The token used for the call is in its taint; the AppRole secret ID was zeroed after the sign-in.
    assertThat(taint).extracting(CharSequence::toString).contains(vault.token(1));
    assertThat(handedOut).singleElement().satisfies(s -> assertThat(new String(s)).isEqualTo("\0".repeat(s.length)));

    read("vault://kv/stage/db#user").close();
    assertThat(vault.to("sys/internal/ui/mounts")).as("the mount is looked up once").hasSize(1);
    assertThat(vault.logins.get()).isEqualTo(1);
  }

  @Test
  void aWholeSecretIsItsJsonAndKvV1HasNoVersion() {
    try (RemoteValue v = read("vault://kv1/app")) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo("{\"api_key\":\"cnry-kv1-api-key\"}");
      assertThat(v.version()).isNull();
      assertThat(v.type()).isEqualTo("json");
    }
    assertThatThrownBy(() -> read("vault://kv1/app?version=2#api_key"))
        .hasMessage("kv1/ is a KV version 1 engine: its secrets have no versions. Remove ?version= from the "
            + "reference.");
  }

  @Test
  void destroyedAndDeletedVersionsSaySo() {
    assertThatThrownBy(() -> read("vault://kv/stage/db?version=2#password"))
        .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.GONE))
        .hasMessage("Version 2 of kv/stage/db is destroyed.");
    assertThatThrownBy(() -> read("vault://kv/stage/db?version=3#password"))
        .hasMessage("Version 3 of kv/stage/db is deleted.");
    assertThatThrownBy(() -> read("vault://kv/stage/missing#password"))
        .isInstanceOfSatisfying(StoreException.class,
            e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.NOT_FOUND))
        .hasMessage("No secret at kv/stage/missing.");
    assertThatThrownBy(() -> read("vault://kv/stage/db#pasword"))
        .hasMessageStartingWith("kv/stage/db has no key \"pasword\". Keys: ")
        .hasMessageContainingAll("password", "user")
        .hasMessageNotContaining(FakeVault.DB_PASSWORD);
  }

  @Test
  void a403WithAWorkingTokenIsAMissingPermission() {
    assertThatThrownBy(() -> read("vault://kv/forbidden#x"))
        .isInstanceOfSatisfying(StoreException.class,
            e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.DENIED))
        .hasMessage("The gateway's token can't read kv/data/forbidden.");
    assertThat(vault.logins.get()).as("no new sign-in for a permission").isEqualTo(1);
    // A preflight 403 is "no capability inside that mount", not "no mount".
    assertThatThrownBy(() -> read("vault://locked/x#y")).hasMessage("The gateway's token can't read locked/x.");
  }

  @Test
  void aRevokedTokenIsReplacedByANewSignInAndTheRequestRepeatedOnce() {
    read("vault://kv/stage/db#password").close();
    vault.revokedTokens.add(vault.token(1));

    try (RemoteValue v = read("vault://kv/stage/db#password")) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo(FakeVault.DB_PASSWORD);
    }
    assertThat(vault.logins.get()).isEqualTo(2);
    assertThat(vault.to("kv/data/stage/db").getLast().getHeaders().get("X-Vault-Token")).isEqualTo(vault.token(2));
    assertThat(taint).extracting(CharSequence::toString).contains(vault.token(1), vault.token(2));
  }

  @Test
  void aStandbyRedirectIsNeverFollowed() throws Exception {
    try (MockWebServer elsewhere = new MockWebServer()) {
      elsewhere.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
      vault.standby = true;
      vault.standbyLocation = elsewhere.url("/").toString().replaceAll("/$", "");
      assertThatThrownBy(() -> read("vault://kv/stage/db#password"))
          .isInstanceOfSatisfying(StoreException.class,
              e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.UNAVAILABLE))
          .hasMessage("Vault answered from a standby node. Use the active address or the load balancer.");
      assertThat(elsewhere.getRequestCount()).isZero();
    }
  }

  @Test
  void theTokenIsRenewedAfterTwoThirdsAndReplacedNearItsEnd() {
    vault.tokenTtl = 30;
    read("vault://kv/stage/db#password").close();
    clock.advance(Duration.ofSeconds(21));
    read("vault://kv/stage/db#password").close();
    assertThat(vault.renewSelf.get()).isEqualTo(1);
    assertThat(vault.logins.get()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(28));
    read("vault://kv/stage/db#password").close();
    assertThat(vault.logins.get()).as("past 90 % of its TTL: a new sign-in").isEqualTo(2);
  }

  @Test
  void dynamicCredentialsComeWithALeaseRevokedByItsPath() {
    VaultStore.Lease lease = store.issue((SecretRef.Vault) SecretRef.parse(
        "vault://database/creds/prod-replica-readonly"), call());
    assertThat(lease.username()).isEqualTo("v-approle-prod-rep-1");
    assertThat(new String(lease.password())).isEqualTo(FakeVault.DYNAMIC_PASSWORD_PREFIX + "1");
    assertThat(lease.ttl()).isEqualTo(Duration.ofHours(1));
    assertThat(lease.renewable()).isTrue();
    assertThat(lease.toString()).doesNotContain(FakeVault.DYNAMIC_PASSWORD_PREFIX);
    assertThat(taint).extracting(CharSequence::toString).contains(FakeVault.DYNAMIC_PASSWORD_PREFIX + "1");

    assertThat(store.renew(lease.leaseId(), Duration.ofHours(1), call())).isEqualTo(Duration.ofHours(1));
    store.revoke(lease.leaseId(), call());
    assertThat(vault.revokedLeases).containsExactly("database/creds/prod-replica-readonly/lease1");

    // A reference to dynamic credentials isn't read as a value.
    assertThatThrownBy(() -> read("vault://database/creds/prod-replica-readonly"))
        .hasMessageContaining("cannot be used as a secret reference")
        .hasMessageContaining("Use a Vault KV path and key");
    // Test reference checks the role without creating a database user.
    assertThat(store.probe((SecretRef.Vault) SecretRef.parse("vault://database/creds/prod-replica-readonly"), call()))
        .isEqualTo("database engine database/");
    assertThat(vault.issued.get()).isEqualTo(1);
  }

  @Test
  void signsAnSshKeyAndRetriesWithoutAKeyIdTheRoleRefuses() throws Exception {
    PublicKey key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    vault.signRejectsKeyId = true;

    VaultStore.Signed cert = store.sign("ssh-client-signer", "gw-runbooks", key, "deploy", Duration.ofMinutes(5),
        "gw-call-42", Map.of("permit-pty", ""), call());

    assertThat(cert.openSsh()).isEqualTo("ssh-ed25519-cert-v01@openssh.com AAAAcnryCERT");
    assertThat(cert.serial()).isEqualTo("5b:0f:21");
    assertThat(cert.notAfter()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
    List<RecordedRequest> signs = vault.to("ssh-client-signer/sign/gw-runbooks");
    assertThat(signs).hasSize(2);
    String first = signs.getFirst().getBody().utf8();
    assertThat(first).contains("\"public_key\":\"ssh-ed25519 AAAA", "\"valid_principals\":\"deploy\"",
        "\"ttl\":\"300s\"", "\"cert_type\":\"user\"", "\"key_id\":\"gw-call-42\"", "\"permit-pty\":\"\"");
    assertThat(signs.getLast().getBody().utf8()).doesNotContain("key_id");
  }

  @Test
  void testShowsIdentityAndWarnsAboutWriteAccess() {
    vault.capabilities = "[\"create\",\"read\",\"update\"]";
    SecretStores.TestResult result = store.test(List.of(SecretRef.parse("vault://kv/stage/db#password")), call());

    assertThat(result.ok()).isTrue();
    assertThat(result.server().path("version").asString()).isEqualTo("2.2.1");
    assertThat(result.identity().path("role").asString()).isEqualTo("gaterook");
    assertThat(result.identity().path("ttl").asString()).isEqualTo("1h");
    assertThat(result.permissions()).singleElement().satisfies(p -> {
      assertThat(p.ref()).isEqualTo("vault://kv/stage/db#password");
      assertThat(p.capabilities()).containsExactly("create", "read", "update");
    });
    assertThat(result.hints()).extracting(SecretStores.Hint::text)
        .contains("This token can write kv/data/stage/db. Give the gateway a read-only policy.");
    assertThat(result.toString()).doesNotContain(FakeVault.DB_PASSWORD, vault.token(1), FakeVault.SECRET_ID);
  }

  @Test
  void testOfASealedVaultFails() {
    vault.sealed = true;
    SecretStores.TestResult result = store.test(List.of(), call());
    assertThat(result.ok()).isFalse();
    assertThat(result.error()).isEqualTo("Vault is sealed: unseal it, then test again.");
    assertThat(result.reason()).isEqualTo("unavailable");
  }

  @Test
  void aWrongSecretIdSaysWhatToCheck() {
    try (VaultStore wrong = new VaultStore(settings(vault.address()), f -> "cnry-wrong".toCharArray(), clock)) {
      assertThatThrownBy(() -> wrong.read(SecretRef.parse("vault://kv/stage/db#password"), call()))
          .hasMessage("Vault refused the AppRole sign-in: check the role ID and the secret ID (it may have expired or "
              + "used up its uses).");
    }
  }

  @Test
  void remoteHttpRequiresExplicitConsent() {
    for (String address : List.of("http://vault.acme.internal", "http://10.0.4.2:8200", "http://8.8.8.8:8200")) {
      assertThatThrownBy(() -> StoreAddress.check(address, Set.of()))
          .hasMessageStartingWith("Use https:// or confirm the risk of HTTP");
      assertThatThrownBy(() -> StoreAddress.check(address, Set.of(), false))
          .isInstanceOf(StoreException.class);
      assertThat(StoreAddress.check(address + "/", Set.of(), true)).hasToString(address);
    }
    assertThat(StoreAddress.check("http://127.0.0.1:8200", Set.of())).hasToString("http://127.0.0.1:8200");
    assertThat(StoreAddress.check("http://localhost:8200", Set.of())).hasToString("http://localhost:8200");
    assertThat(StoreAddress.check("http://[::1]:8200", Set.of())).hasToString("http://[::1]:8200");
    assertThat(StoreAddress.check("https://vault.acme.internal:8200/", Set.of()))
        .hasToString("https://vault.acme.internal:8200");
    assertThat(StoreAddress.check("https://10.0.4.2:8200", Set.of())).hasToString("https://10.0.4.2:8200");
  }

  @Test
  void httpConsentDoesNotBypassAddressProtections() {
    for (String address : List.of("http://169.254.169.254", "http://169.254.10.20", "http://[fe80::1]",
        "http://127.0.0.1:7777", "http://localhost:7777", "http://[::1]:7777", "http://2130706433",
        "http://0177.0.0.1", "http://203.0.113.10", "http://user@vault.acme.internal", "http://vault.acme.internal?token=test",
        "http://vault.acme.internal#fragment", "http://vault.acme.internal/../secret",
        "http://vault.acme.internal/%2e%2e/secret", "ftp://vault.acme.internal")) {
      assertThatThrownBy(() -> StoreAddress.check(address, Set.of(7777), true)).as(address)
          .isInstanceOf(StoreException.class);
    }
    assertThatThrownBy(() -> StoreAddress.check("https://169.254.169.254", Set.of()))
        .isInstanceOf(StoreException.class);
    assertThatThrownBy(() -> StoreAddress.check("https://user@vault.acme.internal", Set.of()))
        .hasMessage("Write the address without a user, query or fragment.");
  }

  @Test
  void vaultSettingsReadThePersistedHttpConsent() {
    var config = JsonMapper.builder().build().createObjectNode().put("address", "http://vault.acme.internal:8200");
    config.putObject("auth").put("method", "token");
    assertThatThrownBy(() -> VaultSettings.of(config, Set.of())).isInstanceOf(StoreException.class);
    config.put("allowPlainHttp", false);
    assertThatThrownBy(() -> VaultSettings.of(config, Set.of())).isInstanceOf(StoreException.class);
    config.put("allowPlainHttp", true);
    assertThat(VaultSettings.of(config, Set.of()).address()).hasToString("http://vault.acme.internal:8200");
  }

  @Test
  void localhostNamesOnACorePortAreRefusedWhenSaved() {
    for (String address : List.of("http://localhost.:7777", "http://vault.localhost:7777", "https://LOCALHOST.:7777",
        "https://a.b.localhost.:7777")) {
      assertThatThrownBy(() -> StoreAddress.check(address, Set.of(7777), true)).as(address)
          .isInstanceOf(StoreException.class).hasMessageContaining("GateRook itself");
    }
    assertThat(StoreAddress.check("https://vault.localhost:8200", Set.of(7777)))
        .hasToString("https://vault.localhost:8200");
  }

  @Test
  void requestsNeverGoToTheCoreItselfByAddressOrByName() {
    int port = URI.create(vault.address()).getPort();
    for (String address : List.of(vault.address(), "http://localhost:" + port)) {
      try (VaultStore core = new VaultStore(settings(address), f -> FakeVault.SECRET_ID.toCharArray(), clock,
          Set.of(port))) {
        assertThatThrownBy(() -> core.read(SecretRef.parse("vault://kv/stage/db#password"), call())).as(address)
            .isInstanceOfSatisfying(StoreException.class, e -> {
              assertThat(e.reason()).isEqualTo(StoreException.Reason.INVALID);
              assertThat(e.getMessage()).contains("is GateRook itself");
            });
      }
    }
    assertThat(vault.requests).isEmpty();
  }

  @Test
  void signingRefusesMountsAndRolesThatAreNotPlainVaultNames() throws Exception {
    PublicKey key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    List<List<String>> unsafe = List.of(
        List.of("ssh-client-signer", "../../sys/policies/acl/gw"), List.of("ssh-client-signer", "gw-runbooks/../x"),
        List.of("ssh-client-signer", "gw-runbooks?x=1"), List.of("ssh-client-signer", "gw-runbooks#x"),
        List.of("ssh-client-signer", "gw%2Frunbooks"), List.of("ssh-client-signer", ".."),
        List.of("ssh-client-signer", ""), List.of("../sys", "gw-runbooks"), List.of("ssh/../../sys", "gw-runbooks"),
        List.of("ssh-client-signer/.", "gw-runbooks"), List.of("/ssh-client-signer", "gw-runbooks"),
        List.of("ssh%2F..", "gw-runbooks"), List.of("ssh-client-signer?x=", "gw-runbooks"));
    for (List<String> p : unsafe) {
      assertThatThrownBy(() -> store.sign(p.get(0), p.get(1), key, "deploy", Duration.ofMinutes(5), null, Map.of(),
          call())).as(String.join(" ", p)).isInstanceOfSatisfying(StoreException.class,
              e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.INVALID));
    }
    assertThat(vault.requests).isEmpty();

    // Nested mounts and dotted role names stay valid Vault names: this one just doesn't exist.
    assertThatThrownBy(() -> store.sign("team-a/ssh", "gw_runbooks.v2", key, "deploy", Duration.ofMinutes(5), null,
        Map.of(), call())).isInstanceOfSatisfying(StoreException.class,
            e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.NOT_FOUND));
  }

  @Test
  void revokingRefusesLeaseIdsThatLeaveTheirPath() {
    for (String id : List.of("database/creds/r/../../../sys/seal", "../sys/seal", "database/creds/r/x?y=1",
        "database/creds/r/x#y", "database/creds/r/%2e%2e", "database/creds//x", "/database/creds/r/x",
        "database/creds/r/x\\y", "database/creds/r/ x", "")) {
      assertThatThrownBy(() -> store.revoke(id, call())).as(id).isInstanceOfSatisfying(StoreException.class,
          e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.INVALID));
    }
    assertThat(vault.to("sys/leases/revoke")).isEmpty();
  }

  @Test
  void theVaultLoginIsReadFromTheVaultTokenFileOnly() {
    Path standard = Path.of(System.getProperty("user.home"), ".vault-token");
    for (String file : new String[] {null, "~/.vault-token", standard.toString()}) {
      assertThat(VaultSettings.of(cliConfig(file), Set.of()).tokenFile()).as(String.valueOf(file)).isEqualTo(standard);
    }
    for (String file : List.of("/etc/passwd", "~/.ssh/id_ed25519", "~/.gaterook/master.keyset", ".vault-token",
        "~/../other/.vault-token", "~/.vault-token/../.ssh/id_ed25519", "~/./.vault-token")) {
      assertThatThrownBy(() -> VaultSettings.of(cliConfig(file), Set.of())).as(file)
          .isInstanceOfSatisfying(StoreException.class, e -> {
            assertThat(e.reason()).isEqualTo(StoreException.Reason.INVALID);
            assertThat(e.getMessage()).contains("~/.vault-token");
          });
    }
  }

  @Test
  void theVaultLoginTokenFileMustBeOneTokenInARegularFile(@TempDir Path dir) throws Exception {
    Path good = Files.writeString(dir.resolve("vault-token"), vault.token(7) + "\n");
    Path link = dir.resolve("link");
    try {
      Files.createSymbolicLink(link, good);
    } catch (UnsupportedOperationException | IOException e) {
      link = null;
    }
    Map<Path, String> refused = new java.util.LinkedHashMap<>();
    if (link != null) {
      refused.put(link, "symbolic link");
    }
    refused.put(Files.createDirectory(dir.resolve("folder")), "regular file");
    refused.put(Files.write(dir.resolve("large"), "a".repeat(16 * 1024 + 1).getBytes(StandardCharsets.US_ASCII)),
        "too large");
    refused.put(Files.writeString(dir.resolve("lines"), vault.token(7) + "\nsecond line\n"), "one Vault token");
    refused.put(Files.writeString(dir.resolve("spaced"), "user:" + vault.token(7) + " x"), "one Vault token");
    refused.put(Files.writeString(dir.resolve("empty"), "\n"), "one Vault token");
    for (Map.Entry<Path, String> e : refused.entrySet()) {
      try (VaultStore cli = cliStore(e.getKey())) {
        assertThatThrownBy(() -> cli.read(SecretRef.parse("vault://kv/stage/db#password"), call()))
            .as(e.getKey().getFileName().toString()).isInstanceOf(StoreException.class)
            .hasMessageContaining(e.getValue());
      }
    }
    assertThat(vault.requests).as("no part of a refused file is sent").isEmpty();

    try (VaultStore cli = cliStore(good); RemoteValue v = cli.read(SecretRef.parse("vault://kv/stage/db#password"),
        call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo(FakeVault.DB_PASSWORD);
    }
    assertThat(vault.to("auth/token/lookup-self").getFirst().getHeaders().get("X-Vault-Token"))
        .isEqualTo(vault.token(7));
  }

  @Test
  void aChosenTokenFileIsAnAbsolutePathTheCoreWrote() {
    var config = cliConfig(null);
    ((tools.jackson.databind.node.ObjectNode) config.path("auth")).put("method", "token-file");
    assertThatThrownBy(() -> VaultSettings.of(config, Set.of())).isInstanceOfSatisfying(StoreException.class,
        e -> assertThat(e.getMessage()).contains("Choose the file that holds the Vault token"));
    for (String file : List.of("vault-token", "~/.vault-agent/token", "/var/run/../etc/passwd", "/var/run/./token")) {
      ((tools.jackson.databind.node.ObjectNode) config.path("auth")).put("file", file);
      assertThatThrownBy(() -> VaultSettings.of(config, Set.of())).as(file)
          .isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.getMessage()).contains("absolute path"));
    }
    Path chosen = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("agent-token");
    ((tools.jackson.databind.node.ObjectNode) config.path("auth")).put("file", chosen.toString());
    VaultSettings settings = VaultSettings.of(config, Set.of());
    assertThat(settings.auth()).isEqualTo(VaultSettings.Auth.TOKEN_FILE);
    assertThat(settings.tokenFile()).isEqualTo(chosen);
    assertThat(settings.secretFields()).as("nothing of a token file is stored").isEmpty();
  }

  @Test
  void aChosenTokenFileIsReadAtEverySignIn(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("agent-token"), vault.token(8) + "\n");
    try (VaultStore store = tokenFileStore(file);
        RemoteValue v = store.read(SecretRef.parse("vault://kv/stage/db#password"), call())) {
      assertThat(new String(v.value(), StandardCharsets.UTF_8)).isEqualTo(FakeVault.DB_PASSWORD);
    }
    assertThat(vault.to("auth/token/lookup-self").getFirst().getHeaders().get("X-Vault-Token"))
        .isEqualTo(vault.token(8));
    assertThat(vault.to("auth/approle")).isEmpty();

    // Vault Agent wrote a new token there: the next sign-in reads it, nothing was copied.
    Files.writeString(file, vault.token(9) + "\n");
    try (VaultStore store = tokenFileStore(file);
        RemoteValue v = store.read(SecretRef.parse("vault://kv/stage/db#password"), call())) {
      assertThat(v.value()).isNotEmpty();
    }
    assertThat(vault.to("auth/token/lookup-self").getLast().getHeaders().get("X-Vault-Token"))
        .isEqualTo(vault.token(9));

    Files.delete(file);
    try (VaultStore store = tokenFileStore(file)) {
      assertThatThrownBy(() -> store.read(SecretRef.parse("vault://kv/stage/db#password"), call()))
          .isInstanceOf(StoreException.class).hasMessageContaining("choose another file");
    }
  }

  @Test
  void aChosenTokenFileIsTestedAsSuch(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("agent-token"), vault.token(8));
    try (VaultStore store = tokenFileStore(file)) {
      SecretStores.TestResult result = store.test(List.of(), call());
      assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
      assertThat(result.identity().path("auth").asString()).isEqualTo("token-file");
      assertThat(result.checks()).anySatisfy(c -> assertThat(c.detail()).isEqualTo("The token in " + file
          + " works"));
      assertThat(result.hints()).noneSatisfy(h -> assertThat(h.code()).isEqualTo("store.personal-token"));
    }
  }

  private VaultStore tokenFileStore(Path tokenFile) {
    return new VaultStore(new VaultSettings(URI.create(vault.address()), null, null, VaultSettings.Auth.TOKEN_FILE,
        "approle", null, tokenFile, Duration.ofHours(1), 60, false), f -> {
          throw new AssertionError("A token file needs no stored secret");
        }, clock);
  }

  private VaultStore cliStore(Path tokenFile) {
    return new VaultStore(new VaultSettings(URI.create(vault.address()), null, null, VaultSettings.Auth.CLI_TOKEN,
        "approle", null, tokenFile, Duration.ofHours(1), 60, false), f -> {
          throw new AssertionError("The vault login needs no stored secret");
        }, clock);
  }

  private static tools.jackson.databind.node.ObjectNode cliConfig(String file) {
    var config = JsonMapper.builder().build().createObjectNode().put("address", "https://vault.acme.internal");
    var auth = config.putObject("auth").put("method", "cli-token");
    if (file != null) {
      auth.put("file", file);
    }
    return config;
  }

  /** A clock the test moves. */
  static final class TestClock extends Clock {
    private volatile Instant now;

    TestClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
