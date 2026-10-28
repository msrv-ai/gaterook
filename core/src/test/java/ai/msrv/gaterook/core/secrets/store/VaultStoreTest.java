package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.store.vault.VaultSettings;
import ai.msrv.gaterook.core.secrets.store.vault.VaultStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    assertThat(vault.to("kv/metadata/")).extracting(RecordedRequest::getMethod).containsOnly("LIST");
    assertThat(vault.to("kv1/")).extracting(RecordedRequest::getMethod).containsExactly("LIST");
    assertThat(vault.to("kv/data/")).isEmpty();
    assertThat(vault.issued.get()).isZero();
    assertThat(folder.toString()).doesNotContain(FakeVault.DB_PASSWORD, FakeVault.SECRET_ID, vault.token(1));
    assertThat(vault.to("kv/metadata/").getFirst().getHeaders().get("X-Vault-Token")).isEqualTo(vault.token(1));
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
        .hasMessageContaining("issues short-lived credentials");
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
