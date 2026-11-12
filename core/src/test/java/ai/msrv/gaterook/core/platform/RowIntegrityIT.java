package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.audit.internal.AuditRetentionJob;
import ai.msrv.gaterook.core.connectors.plugins.PluginCatalog;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.settings.SettingsService;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Rows that decide access and where a secret goes (ARCHITECTURE.md §4): access tokens and local secrets edited or
 * inserted outside the core are refused, and the upgrade that gave them their MAC signs what an older version wrote,
 * once, visibly.
 */
class RowIntegrityIT {

  private static final String PASSWORD = "/credentials/password";

  @Test
  void accessTokensMadeOrRevivedOutsideTheCoreAuthenticateNobody() {
    try (TestCore core = TestCore.start("row-integrity-tokens")) {
      core.connection("ri-db", "pgfake", Env.DEV, "db.test");
      core.bean(EndpointService.class).create("ri-ep", null, EndpointService.StartFrom.EMPTY, null);
      core.bean(EndpointConnections.class).add("ri-ep", "ri-db", null);
      TokenService tokens = core.bean(TokenService.class);
      TokenService.Issued issued = tokens.issue("ri-ep", "ri-token", 7, null);
      assertThat(authenticate(core, issued.token())).isPresent();

      // A row with the hash of a value someone knows, on the same endpoint.
      String forged = "gwt_" + "A".repeat(43);
      long now = Instant.now().toEpochMilli();
      core.jdbc().update("insert into api_token (id, endpoint_id, kind, name, prefix, hash, created_at, expires_at, "
          + "version) select 'tok_forged', endpoint_id, 'NAMED', 'forged', 'gwt_AAAA', ?, ?, ?, 0 from api_token "
          + "where id = ?", TokenService.hash(forged), now, now + 86_400_000L, issued.id());
      assertThat(authenticate(core, forged)).as("an unsigned row").isEmpty();
      assertThat(core.bean(IntegrityAlerts.class).has("token", "tok_forged")).isTrue();

      // A revocation undone by clearing revoked_at.
      tokens.revoke(issued.id(), "done");
      assertThat(authenticate(core, issued.token())).isEmpty();
      core.jdbc().update("update api_token set revoked_at = null, revoke_reason = null where id = ?", issued.id());
      assertThat(authenticate(core, issued.token())).as("signed as revoked").isEmpty();
      assertThat(tokens.revoked(ai.msrv.gaterook.core.platform.Caller.Kind.LOCAL_TOKEN, issued.id())).isTrue();

      // A later expiry.
      TokenService.Issued other = tokens.issue("ri-ep", "ri-other", 1, null);
      core.jdbc().update("update api_token set expires_at = expires_at + 86400000 where id = ?", other.id());
      assertThat(authenticate(core, other.token())).isEmpty();
    }
  }

  @Test
  void aSecretMovedToAnotherConnectionOrSetBackIsNotHandedOut() throws Exception {
    try (TestCore core = TestCore.start("row-integrity-secrets")) {
      String prod = core.connection("ri-prod", "pgfake", Env.PROD, "db.test").id();
      String dev = core.connection("ri-dev", "pgfake", Env.DEV, "db.test").id();
      SecretService secrets = core.bean(SecretService.class);
      assertThat(password(secrets, dev)).isEqualTo("pw-ri-dev-0123");

      // The prod password moved onto the dev connection's field, its own secret out of the way.
      core.jdbc().update("update secret set field = '/credentials/moved' where owner_id = ? and field = ?", dev,
          PASSWORD);
      core.jdbc().update("update secret set owner_id = ? where owner_id = ? and field = ?", dev, prod, PASSWORD);
      assertThatThrownBy(() -> password(secrets, dev)).hasMessageContaining("changed outside GateRook");
      core.jdbc().update("update secret set owner_id = ? where owner_id = ? and field = ?", prod, dev, PASSWORD);
      core.jdbc().update("update secret set field = ? where owner_id = ? and field = '/credentials/moved'", PASSWORD,
          dev);
      assertThat(password(secrets, dev)).isEqualTo("pw-ri-dev-0123");
      assertThat(password(secrets, prod)).isEqualTo("pw-ri-prod-0123");

      // An older version brought back.
      String id = core.jdbc().queryForObject("select id from secret where owner_id = ? and field = ?", String.class,
          prod, PASSWORD);
      secrets.replace(id, "pw-ri-prod-v2".toCharArray(), false, "rotated");
      assertThat(password(secrets, prod)).isEqualTo("pw-ri-prod-v2");
      core.jdbc().update("update secret set current_version = 1 where id = ?", id);
      assertThatThrownBy(() -> password(secrets, prod)).hasMessageContaining("changed outside GateRook");
    }
  }

  /**
   * The start that applies V86 signs the tokens and secrets an older version wrote, and says so in Setup changes. The
   * schema rolled back to before V86 is made to look like that older version here; that this signs again is the
   * residual risk of ARCHITECTURE.md §9's OS-account boundary, and the second Setup change is its evidence.
   */
  @Test
  void theUpgradeSignsWhatAnOlderVersionWroteOnceAndSaysSo() throws Exception {
    Path home;
    String dev;
    TokenService.Issued issued;
    try (TestCore core = TestCore.start("row-integrity-upgrade")) {
      dev = core.connection("ri-up-db", "pgfake", Env.DEV, "db.test").id();
      core.bean(EndpointService.class).create("ri-up", null, EndpointService.StartFrom.EMPTY, null);
      core.bean(EndpointConnections.class).add("ri-up", "ri-up-db", null);
      issued = core.bean(TokenService.class).issue("ri-up", "kept", 7, null);
      home = core.home();
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("gateway.db"));
        Statement s = c.createStatement()) {
      for (String table : new String[] {"api_token", "secret", "oauth_grant", "oauth_token"}) {
        s.execute("ALTER TABLE " + table + " DROP COLUMN mac");
      }
      s.execute("DELETE FROM flyway_schema_history WHERE version = '86'");
    }
    try (TestCore core = TestCore.reopen(home)) {
      assertThat(core.context().getBeansOfType(LegacyRows.class).values()).extracting(LegacyRows::migration)
          .filteredOn("86"::equals).as("access tokens, local secrets, OAuth sign-ins and their tokens").hasSize(3);
      assertThat(authenticate(core, issued.token())).as("signed by the upgrade").isPresent();
      assertThat(password(core.bean(SecretService.class), dev)).isEqualTo("pw-ri-up-db-0123");
      assertThat(core.bean(AuditQueries.class).changes("Rows from an older version", null, 10)).singleElement()
          .satisfies(ch -> assertThat(ch.to()).contains("1 access token", "1 secret"));
      // Only that start signs: a row without a MAC written now is refused.
      core.jdbc().update("update api_token set mac = null where id = ?", issued.id());
      assertThat(authenticate(core, issued.token())).isEmpty();
    }
  }

  /**
   * The settings that act on their own (V87): every write signs them, over the key, the version and the value, and a
   * row changed, moved or set back outside the core reads as unverified. Other keys carry no MAC.
   */
  @Test
  void signedSettingsChangedOutsideTheCoreDontVerify() {
    try (TestCore core = TestCore.start("row-integrity-settings")) {
      SettingStore settings = core.bean(SettingStore.class);
      assertThat(SettingStore.SIGNED).containsExactlyInAnyOrder(AuditRetentionJob.SETTING, "catalog.last-good",
          "catalog.sequence", "modules.last-good", "modules.sequence");
      // Written by the first start, signed.
      assertThat(settings.find("audit")).hasValueSatisfying(a -> assertThat(a.verified()).isTrue());
      settings.put("catalog.sequence", JsonNodeFactory.instance.objectNode().put("sequence", 7), null);
      settings.put("audit", JsonNodeFactory.instance.objectNode().put("retentionYears", 5), null);
      assertThat(settings.find("catalog.sequence").orElseThrow().verified()).isTrue();

      // A lower value.
      core.jdbc().update("update setting set value = '{\"sequence\":3}' where key = 'catalog.sequence'");
      assertThat(settings.find("catalog.sequence").orElseThrow().verified()).isFalse();
      core.jdbc().update("update setting set value = '{\"sequence\":7}' where key = 'catalog.sequence'");
      assertThat(settings.find("catalog.sequence").orElseThrow().verified()).as("as signed").isTrue();
      // Another version of the same value.
      core.jdbc().update("update setting set version = version + 1 where key = 'catalog.sequence'");
      assertThat(settings.find("catalog.sequence").orElseThrow().verified()).isFalse();
      // Another key's value with its MAC.
      core.jdbc().update("update setting set value = (select value from setting where key = 'audit'), version = "
          + "(select version from setting where key = 'audit'), mac = (select mac from setting where key = 'audit') "
          + "where key = 'catalog.sequence'");
      assertThat(settings.find("catalog.sequence").orElseThrow().verified()).isFalse();
      assertThat(settings.find("audit").orElseThrow().verified()).isTrue();
      // The core writes it again: signed.
      settings.put("catalog.sequence", JsonNodeFactory.instance.objectNode().put("sequence", 8), null);
      assertThat(settings.find("catalog.sequence").orElseThrow().verified()).isTrue();

      // A key without a MAC reads as stored, whatever wrote it.
      settings.put("activity.saved-views", JsonNodeFactory.instance.arrayNode(), null);
      core.jdbc().update("update setting set value = '[1]' where key = 'activity.saved-views'");
      assertThat(settings.find("activity.saved-views").orElseThrow().verified()).isTrue();
      assertThat(core.jdbc().queryForObject("select count(*) from setting where mac is not null and key not in "
          + "('audit', 'catalog.last-good', 'catalog.sequence')", Integer.class)).isZero();
    }
  }

  /**
   * The start that applies V87 signs the audit retention and the catalog's last good index as the older version stored
   * them, writes the retention it read in place of a missing one, and records the catalog's sequence, so that nothing
   * stops after the upgrade. Rolled back to before V87, the next upgrade does it again (§9, as for V86).
   */
  @Test
  void theUpgradeSignsTheRetentionAndTheCatalogAsStored() throws Exception {
    KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    byte[] spki = key.getPublic().getEncoded();
    String trust = "--gateway.plugins.catalog-keys=ri-k=" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Arrays.copyOfRange(spki, spki.length - 32, spki.length));
    Path home;
    try (TestCore core = TestCore.start("row-integrity-v87", trust)) {
      SettingsService sections = core.bean(SettingsService.class);
      sections.update("audit", JsonNodeFactory.instance.objectNode().put("retentionYears", 3),
          sections.get("audit").version(), "three years");
      byte[] index = ("{\"sequence\":4,\"expiresAt\":\"" + Instant.now().plus(30, ChronoUnit.DAYS)
          .truncatedTo(ChronoUnit.SECONDS) + "\",\"entries\":[],\"revoked\":[]}").getBytes(StandardCharsets.UTF_8);
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key.getPrivate());
      signer.update(index);
      byte[] sig = ("{\"keyId\":\"ri-k\",\"alg\":\"Ed25519\",\"sig\":\""
          + Base64.getEncoder().encodeToString(signer.sign()) + "\"}").getBytes(StandardCharsets.UTF_8);
      core.bean(PluginCatalog.class).accept(index, sig, "https://catalog.test/index.json");
      home = core.home();
    }
    // Before V87: no MAC column, no recorded sequence.
    olderSchema(home, "DELETE FROM setting WHERE key = 'catalog.sequence'");
    try (TestCore core = TestCore.reopen(home, trust)) {
      assertThat(core.bean(SettingsService.class).get("audit").verified()).isTrue();
      assertThat(core.bean(AuditRetentionJob.class).retentionYears()).as("as stored").hasValue(3);
      PluginCatalog catalog = core.bean(PluginCatalog.class);
      assertThat(catalog.installProblem()).as("the cached catalog is trusted").isEmpty();
      assertThat(catalog.current().orElseThrow().sequence()).isEqualTo(4);
      assertThat(core.bean(SettingStore.class).find("catalog.sequence")).hasValueSatisfying(s -> {
        assertThat(s.verified()).isTrue();
        assertThat(s.value().path("sequence").asLong()).isEqualTo(4);
      });
      assertThat(core.bean(IntegrityAlerts.class).current()).isEmpty();
      assertThat(core.bean(AuditQueries.class).changes("Rows from an older version", null, 10)).singleElement()
          .satisfies(ch -> assertThat(ch.to()).contains("Settings › Audit & retention",
              "the catalog's last good index"));
      // Only that start signs: a row without a MAC written now is refused.
      core.jdbc().update("update setting set mac = null where key = 'audit'");
      assertThat(core.bean(AuditRetentionJob.class).retentionYears()).isEmpty();
    }
    // An older version that never saved the retention read its default in its place: the upgrade writes it.
    olderSchema(home, "DELETE FROM setting WHERE key = 'audit'");
    try (TestCore core = TestCore.reopen(home, trust)) {
      SettingsService.Section audit = core.bean(SettingsService.class).get("audit");
      assertThat(audit.verified()).isTrue();
      assertThat(audit.version()).isPositive();
      assertThat(core.bean(AuditRetentionJob.class).retentionYears()).hasValue(1);
      assertThat(core.bean(PluginCatalog.class).installProblem()).isEmpty();
      assertThat(core.bean(IntegrityAlerts.class).current()).isEmpty();
      assertThat(core.bean(AuditQueries.class).changes("Rows from an older version", null, 10)).hasSize(2);
    }
  }

  /** The database as a version before V87 left it, after {@code sql}. */
  private static void olderSchema(Path home, String sql) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("gateway.db"));
        Statement s = c.createStatement()) {
      s.execute("ALTER TABLE setting DROP COLUMN mac");
      s.execute(sql);
      s.execute("DELETE FROM flyway_schema_history WHERE version = '87'");
    }
  }

  private static Optional<Caller> authenticate(TestCore core, String token) {
    return core.bean(CallerAuthenticator.class).authenticate(new Authenticator.Request(Ingress.LOCAL,
        token.startsWith("gwt_") ? endpointOf(core, token) : null, token, null));
  }

  private static String endpointOf(TestCore core, String token) {
    return core.jdbc().queryForList("select e.slug from endpoint e join api_token t on t.endpoint_id = e.id where "
        + "t.hash = ?", String.class, TokenService.hash(token)).stream().findFirst().orElse("ri-ep");
  }

  private static String password(SecretService secrets, String connectionId) throws Exception {
    try (ScopedSecretResolver resolver = secrets.resolver(SecretOwner.connection(connectionId), Set.of(PASSWORD),
        new Taint())) {
      return new String(resolver.secret(PASSWORD).chars());
    }
  }
}
