package ai.msrv.gaterook.core.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Trust settings ({@code x-person-only}) never travel in an endpoint share: an export leaves them out and says so, and
 * an import drops them from an entry that carries them, whatever they hold, before anything checks the entry, and
 * keeps the connection a draft until the person confirms their own. The schema marks them where the real connectors
 * do and further: at the top level, a placeholder defined in a conditional branch, inside an object, in array items and
 * as the items themselves, behind a {@code "#/"} reference and behind {@code "#"}, deeper than a template reaches.
 */
class ConnectionSharesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

  private static final JsonNode SCHEMA = JSON.readTree("""
      {"type":"object","additionalProperties":false,"properties":{
        "host":{"type":"string"},
        "trustedExec":{"type":"string","x-person-only":true},
        "allowExec":true,
        "tls":{"type":"object","properties":{"minVersion":{"type":"string"},
          "pins":{"type":"array","items":{"type":"string"},"x-person-only":true}}},
        "replicas":{"type":"array","items":{"type":"object","properties":{"host":{"type":"string"},
          "fingerprint":{"type":"string","x-person-only":true}}}},
        "trustedKeys":{"type":"array","items":{"type":"string","x-person-only":true}},
        "jump":{"type":"object","properties":{"host":{"type":"string"},"hostKey":{"$ref":"#/$defs/hostKey"}}},
        "next":{"$ref":"#"},
        "trust":{"type":"object","x-person-only":true,"properties":{"clientCert":{"type":"string"},
          "clientKey":{"type":"string","writeOnly":true}}},
        "credentials":{"type":"object","x-gateway-secret":"login","properties":{"user":{"type":"string"},
          "password":{"type":"string","writeOnly":true}}}},
       "allOf":[{"if":{"properties":{"host":{"const":"local"}}},"then":{"properties":{
         "allowExec":{"type":"boolean","default":false,"x-person-only":true}}}}],
       "$defs":{"hostKey":{"type":"string","x-person-only":true}}}
      """);

  /** Every kind of trust setting the configs below hold, in their order. */
  private static final List<String> KINDS = List.of("/trustedExec", "/allowExec", "/tls/pins",
      "/replicas/*/fingerprint", "/trustedKeys/*", "/jump/hostKey", "/next/trustedExec", "/next/next/trustedExec",
      "/trust");

  /** What the configs below hold apart from their trust settings: what a share carries and an import creates. */
  private static final JsonNode PORTABLE = JSON.readTree("""
      {"host":"db.example.test","tls":{"minVersion":"TLSv1.3"},
       "replicas":[{"host":"r1.example.test"},{"host":"r2.example.test"}],"trustedKeys":[],
       "jump":{"host":"bastion.example.test"},"next":{"next":{"host":"deep.example.test"}},
       "credentials":{"user":"app"}}
      """);

  /** Values of trust settings that may appear nowhere outside this computer's own connection. */
  private static final List<String> TRUSTED = List.of("exec-top-7f3a", "pin-one-7f3a", "fp-replica-7f3a",
      "key-one-7f3a", "key-two-7f3a", "jump-key-7f3a", "exec-next-7f3a", "exec-deep-7f3a", "cert-7f3a");

  private final ConnectionConfigs configs = mock(ConnectionConfigs.class);
  private final ConnectionService connections = mock(ConnectionService.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final SecretService secrets = mock(SecretService.class);
  private final ConnectionShares shares = new ConnectionShares(configs, connections, registry, secrets,
      Masker.standard());

  @BeforeEach
  void setup() {
    ConnectorManifest manifest = ConnectorManifest.builder("trustfake", "1", ConnectorManifest.Kind.BUILT_IN)
        .name("Trust test").configSchema(SCHEMA).build();
    when(registry.type("trustfake")).thenReturn(Optional.of(new ConnectorType(manifest, true, true)));
    when(registry.manifest("trustfake")).thenReturn(Optional.of(manifest));
    when(configs.all()).thenReturn(List.of());
  }

  /** A connection as this computer stores it: the person trusted all of this here, and two secrets are stored. */
  private static ObjectNode storedConfig() {
    return (ObjectNode) JSON.readTree("""
        {"host":"db.example.test","trustedExec":"exec-top-7f3a","allowExec":true,
         "tls":{"minVersion":"TLSv1.3","pins":["pin-one-7f3a"]},
         "replicas":[{"host":"r1.example.test","fingerprint":"fp-replica-7f3a"},{"host":"r2.example.test",
           "fingerprint":""}],
         "trustedKeys":["key-one-7f3a","key-two-7f3a"],
         "jump":{"host":"bastion.example.test","hostKey":"jump-key-7f3a"},
         "next":{"trustedExec":"exec-next-7f3a","next":{"host":"deep.example.test","trustedExec":"exec-deep-7f3a"}},
         "trust":{"clientCert":"cert-7f3a","clientKey":{"$secret":"sec_trust"}},
         "credentials":{"user":"app","password":{"$secret":"sec_pw"}}}
        """);
  }

  /** An entry as an older GateRook exported it: the trust settings travel, some of them null, false or empty. */
  private static ObjectNode olderEntry() {
    ObjectNode entry = (ObjectNode) JSON.readTree("""
        {"id":"trusted-db","name":"trusted-db","type":"trustfake","env":"dev",
         "config":{"host":"db.example.test","trustedExec":null,"allowExec":false,
           "tls":{"minVersion":"TLSv1.3","pins":[]},
           "replicas":[{"host":"r1.example.test","fingerprint":"fp-replica-7f3a"},{"host":"r2.example.test",
             "fingerprint":""}],
           "trustedKeys":["key-one-7f3a","key-two-7f3a"],
           "jump":{"host":"bastion.example.test","hostKey":{}},
           "next":{"trustedExec":"exec-next-7f3a","next":{"host":"deep.example.test","trustedExec":"exec-deep-7f3a"}},
           "trust":{"clientCert":"cert-7f3a"},
           "credentials":{"user":"app"}},
         "secrets":{"/credentials/password":"pw-shared-0123","/trust/clientKey":"key-from-elsewhere-7f3a"},
         "omittedSecretFields":[],"tools":[],"templates":[],"warnings":[]}
        """);
    return entry;
  }

  private void stored(ObjectNode config, Map<String, String> secretIds) {
    ConnectionConfig cfg = new ConnectionConfig("c-1", "trusted-db", "trustfake", Env.DEV, 3, config,
        secretIds.keySet(), null);
    when(configs.byId("c-1")).thenReturn(Optional.of(new ConnectionConfigs.Stored(cfg, true, null, NOW, NOW,
        secretIds)));
  }

  // ── Export ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anExportLeavesOutEveryTrustSettingAndSaysThatTheDestinationConfirmsThem() {
    stored(storedConfig(), Map.of("/credentials/password", "sec_pw", "/trust/clientKey", "sec_trust"));

    ObjectNode entry = shares.export("c-1", false);

    assertThat(entry.path("config")).isEqualTo(PORTABLE);
    assertThat(entry.toString()).doesNotContain(TRUSTED);
    // The credential of a trust setting stays with it: it is neither shared nor asked for on the destination.
    assertThat(entry.path("omittedSecretFields")).isEqualTo(JSON.readTree("[\"/credentials/password\"]"));
    assertThat(entry.path("secrets")).isEmpty();
    verify(secrets, never()).get("sec_trust");
    assertThat(entry.path("warnings")).hasSize(1);
    assertThat(entry.path("warnings").get(0).asString()).isEqualTo("Trust settings were omitted ("
        + String.join(", ", KINDS) + "); confirm them on the destination computer with Test connection.");
  }

  /** A local credential this connection owns would be shared with secrets included: not one of a trust setting. */
  @Test
  void anExportWithSecretsNeverIncludesTheCredentialOfATrustSetting() throws Exception {
    ObjectNode config = storedConfig();
    ((ObjectNode) config.path("credentials")).remove("password");
    stored(config, Map.of("/trust/clientKey", "sec_trust"));
    when(secrets.get("sec_trust")).thenReturn(new SecretInfo("sec_trust", "trusted-db/client-key",
        SecretOwner.connection("c-1"), "/trust/clientKey", null, List.of(), "local", 1, null, null, "stored", false,
        null, null, null));
    ScopedSecretResolver resolver = mock(ScopedSecretResolver.class);
    SecretResolver.Secret value = mock(SecretResolver.Secret.class);
    when(value.unsafeString()).thenReturn("client-key-of-this-computer-7f3a");
    when(resolver.secret("/trust/clientKey")).thenReturn(value);
    when(secrets.resolver(any(SecretOwner.class), any(), any())).thenReturn(resolver);

    ObjectNode entry = shares.export("c-1", true);

    assertThat(entry.path("secrets")).isEmpty();
    assertThat(entry.path("omittedSecretFields")).isEmpty();
    assertThat(entry.toString()).doesNotContain(TRUSTED)
        .doesNotContain("sec_trust", "client-key-of-this-computer-7f3a");
    verify(secrets, never()).get(anyString());
  }

  @Test
  void anExportOfAConnectionWithoutTrustSettingsIsAsBefore() {
    ObjectNode config = (ObjectNode) PORTABLE.deepCopy();
    stored(config, Map.of());

    ObjectNode entry = shares.export("c-1", false);

    assertThat(entry.path("config")).isEqualTo(PORTABLE);
    assertThat(entry.path("warnings")).isEmpty();
  }

  // ── Review ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theReviewShowsTheConfigAsItWillBeCreatedAndNamesWhatThePersonConfirms() {
    ObjectNode entry = olderEntry();

    assertThat(shares.previewConfig(entry)).isEqualTo(PORTABLE);
    assertThat(shares.trustNote(entry)).contains("Trust settings in this file are not imported: "
        + String.join(", ", KINDS) + ". The connection is imported off, with Setup required, until you confirm them "
        + "on this computer with Test connection.");
    // The credential of a trust setting is not one the import takes over.
    assertThat(shares.secretCount(entry)).isEqualTo(1);

    ObjectNode plain = olderEntry();
    plain.set("config", PORTABLE.deepCopy());
    ((ObjectNode) plain.path("secrets")).remove("/trust/clientKey");
    assertThat(shares.trustNote(plain)).isEmpty();
    assertThat(shares.previewConfig(plain)).isEqualTo(PORTABLE);
    assertThat(shares.secretCount(plain)).isEqualTo(1);
  }

  /** What the file says about itself never names, adds or removes a trust setting. */
  @Test
  void theNoteComesFromTheSchemaNeverFromTheFilesWarnings() {
    ObjectNode claims = olderEntry();
    claims.set("config", PORTABLE.deepCopy());
    ((ObjectNode) claims.path("secrets")).remove("/trust/clientKey");
    claims.putArray("warnings").add("Trust settings were omitted (/trustedExec); confirm them on the destination "
        + "computer with Test connection.");
    ObjectNode silent = olderEntry();
    silent.putArray("warnings").add("No trust settings in this connection.");

    assertThat(shares.trustNote(claims)).isEmpty();
    assertThat(shares.trustNote(silent)).isPresent();
  }

  // ── Validation ──────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A file's trust settings are dropped before it is checked: even values the schema would refuse, a stored-secret
   * placeholder or a local file inside one, never make the file invalid, and the check sees none of them.
   */
  @Test
  void validationLooksOnlyAtWhatAnImportCreates() {
    ObjectNode entry = olderEntry();
    ObjectNode config = (ObjectNode) entry.path("config");
    config.put("trustedExec", 42);
    ((ObjectNode) config.path("tls")).put("pins", "not a list");
    config.putArray("trustedKeys").addObject().put("unexpected", true);
    ((ObjectNode) config.path("jump")).putObject("hostKey").put("$secret", "sec_elsewhere");
    ((ObjectNode) config.path("trust")).put("credentialsFile", "/home/someone/.config/credentials.json");

    shares.validate(entry, true);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, SecretInput>> values = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<JsonNode> checked = ArgumentCaptor.forClass(JsonNode.class);
    verify(connections).missingImportSettings(eq("trusted-db"), eq("trustfake"), eq(Env.DEV), checked.capture(),
        values.capture());
    assertThat(checked.getValue()).isEqualTo(PORTABLE);
    assertThat(values.getValue()).containsOnlyKeys("/credentials/password");
  }

  /** What is not a trust setting is checked as before. */
  @Test
  void placeholdersAndLocalFilesOutsideTrustSettingsAreStillRefused() {
    ObjectNode placeholder = olderEntry();
    ((ObjectNode) placeholder.path("config").path("credentials")).putObject("user").put("$secret", "sec_elsewhere");
    ObjectNode local = olderEntry();
    ((ObjectNode) local.path("config")).put("privateKeyPath", "/home/someone/.ssh/id_ed25519");

    assertThatThrownBy(() -> shares.validate(placeholder, true)).isInstanceOf(ProblemException.class)
        .hasMessageContaining("Stored secret identifiers");
    assertThatThrownBy(() -> shares.validate(local, true)).isInstanceOf(ProblemException.class)
        .hasMessageContaining("local files");
  }

  // ── Import ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anImportDropsTrustSettingsAndKeepsTheConnectionADraftWithOneItemPerKind() {
    ConnectionShares.Prepared prepared = shares.prepareImport(olderEntry(), true, new HashSet<>());

    assertThat(prepared.config()).isEqualTo(PORTABLE);
    assertThat(prepared.secrets()).containsOnlyKeys("/credentials/password");
    List<String> expected = new ArrayList<>();
    KINDS.forEach(kind -> expected.add(kind + ": Not imported. Confirm this trust setting on this computer with Test "
        + "connection, or set it yourself."));
    assertThat(prepared.missing()).containsExactlyElementsOf(expected);
    assertThat(prepared.needsSetup()).isTrue();

    shares.preflightImport(prepared, null);
    shares.importPrepared(prepared, "test");

    verify(connections, never()).preflightCreate(anyString(), anyString(), any(), any(), any(), any());
    verify(connections, never()).create(anyString(), anyString(), any(), any(), any(), any(), any());
    ArgumentCaptor<JsonNode> created = ArgumentCaptor.forClass(JsonNode.class);
    verify(connections).createImportDraft(eq("trusted-db"), eq("trustfake"), eq(Env.DEV), created.capture(), any(),
        eq(prepared.missing()), isNull(), eq("test"));
    assertThat(created.getValue()).isEqualTo(PORTABLE);
    assertThat(created.getValue().toString()).doesNotContain(TRUSTED);
  }

  /** A trust setting counts by being there: {@code null}, {@code false} and empty values make a draft too. */
  @Test
  void anyValueOfATrustSettingMakesADraft() {
    for (String value : List.of("null", "false", "true", "\"\"", "[]", "{}", "0", "\"exec-top-7f3a\"")) {
      ObjectNode entry = olderEntry();
      entry.set("config", PORTABLE.deepCopy());
      ((ObjectNode) entry.path("config")).set("trustedExec", JSON.readTree(value));
      ((ObjectNode) entry.path("secrets")).remove("/trust/clientKey");

      ConnectionShares.Prepared prepared = shares.prepareImport(entry, true, new HashSet<>());

      assertThat(prepared.config()).as(value).isEqualTo(PORTABLE);
      assertThat(prepared.missing()).as(value).containsExactly("/trustedExec: Not imported. Confirm this trust "
          + "setting on this computer with Test connection, or set it yourself.");
    }
  }

  /** The file's warnings never decide: a claim makes no draft, and silence keeps one. */
  @Test
  void theFilesWarningsNeitherMakeNorPreventADraft() {
    ObjectNode claims = olderEntry();
    claims.set("config", PORTABLE.deepCopy());
    ((ObjectNode) claims.path("secrets")).remove("/trust/clientKey");
    claims.putArray("warnings").add("Trust settings were omitted (/trustedExec); confirm them on the destination "
        + "computer with Test connection.");
    ObjectNode silent = olderEntry();
    silent.putArray("warnings").add("No trust settings in this connection.");

    ConnectionShares.Prepared plain = shares.prepareImport(claims, true, new HashSet<>());
    ConnectionShares.Prepared draft = shares.prepareImport(silent, true, new HashSet<>());

    assertThat(plain.needsSetup()).isFalse();
    assertThat(draft.needsSetup()).isTrue();
    shares.importPrepared(plain, "test");
    verify(connections).create(eq("trusted-db"), eq("trustfake"), eq(Env.DEV), eq(PORTABLE), any(), eq("test"),
        eq(ConnectionService.CONFIRM_DANGER));
  }

  @Test
  void withoutSecretsTheCredentialOfATrustSettingIsNotAskedFor() {
    ConnectionShares.Prepared prepared = shares.prepareImport(olderEntry(), false, new HashSet<>());

    assertThat(prepared.secrets()).isEmpty();
    assertThat(prepared.missing()).contains("/credentials/password: Include or supply the credential.")
        .doesNotContain("/trust/clientKey: Include or supply the credential.")
        .contains("/trust: Not imported. Confirm this trust setting on this computer with Test connection, or set it "
            + "yourself.");
  }

  /** Leaving trust settings out never turns an entry whose settings are no object into an empty, complete one. */
  @Test
  void anEntryWhoseSettingsAreNoObjectIsStillRefused() {
    ObjectNode config = olderEntry().put("config", "not settings");
    ObjectNode values = olderEntry();
    values.putArray("secrets").add("/credentials/password");

    for (ObjectNode entry : List.of(config, values)) {
      assertThatThrownBy(() -> shares.prepareImport(entry, true, new HashSet<>()))
          .isInstanceOf(ProblemException.class).hasMessageContaining("Invalid connection settings");
      assertThatThrownBy(() -> shares.validate(entry, true))
          .isInstanceOf(ProblemException.class).hasMessageContaining("Invalid connection settings");
    }
    verify(connections, never()).missingImportSettings(anyString(), anyString(), any(), any(), any());
  }

  @Test
  void aCredentialOfATrustSettingAloneMakesADraft() {
    ObjectNode entry = olderEntry();
    entry.set("config", PORTABLE.deepCopy());

    ConnectionShares.Prepared prepared = shares.prepareImport(entry, true, new HashSet<>());

    assertThat(prepared.secrets()).containsOnlyKeys("/credentials/password");
    assertThat(prepared.missing()).containsExactly("/trust: Not imported. Confirm this trust setting on this "
        + "computer with Test connection, or set it yourself.");
    assertThat(Set.copyOf(prepared.secrets().keySet())).doesNotContain("/trust/clientKey");
  }
}
