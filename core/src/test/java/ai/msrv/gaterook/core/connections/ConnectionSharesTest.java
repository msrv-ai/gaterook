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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Trust settings ({@code x-person-only}) never travel in an endpoint share: an export leaves them out and says so, and
 * an import drops them from an entry that carries them, whatever they hold, before anything checks the entry, and
 * makes the connection a draft that the person sets up. The schema marks them where the real connectors do and
 * further: at the top level, a placeholder defined in a conditional branch, inside an object, in array items and as
 * the items themselves, behind a {@code "#/"} reference and behind {@code "#"}, deeper than a template reaches, where
 * only a {@code oneOf}, an {@code anyOf} or an {@code else} branch defines one, on a credential field, and inside a
 * marked object that also holds a value that may hold a key. Other schemas mark their root or are too complex to
 * check.
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
          "clientKey":{"type":"string","writeOnly":true},"pin":{"type":"string","x-person-only":true},
          "hook":{"type":"string","x-key-allowed":true}}},
        "credentials":{"type":"object","x-gateway-secret":"login","properties":{"user":{"type":"string"},
          "password":{"type":"string","writeOnly":true}}},
        "pinKey":{"type":"string","writeOnly":true,"x-person-only":true},
        "proxy":{"oneOf":[{"type":"null"},{"type":"object","properties":{"host":{"type":"string"},
          "pin":{"type":"string","x-person-only":true}}}]},
        "account":{"anyOf":[{"type":"integer"},{"type":"string","x-person-only":true}]},
        "remote":true},
       "allOf":[{"if":{"properties":{"host":{"const":"local"}}},"then":{"properties":{
         "allowExec":{"type":"boolean","default":false,"x-person-only":true}}}},
        {"if":{"properties":{"host":{"const":"local"}}},"then":{},"else":{"properties":{
         "remote":{"type":"object","properties":{"user":{"type":"string"},
           "key":{"type":"string","x-person-only":true}}}}}}],
       "$defs":{"hostKey":{"type":"string","x-person-only":true}}}
      """);

  /**
   * Every kind of trust setting the configs below hold, in their order: each once, however many array items hold it,
   * and {@code /trust} once, though the pin inside it is marked too.
   */
  private static final List<String> KINDS = List.of("/trustedExec", "/allowExec", "/tls/pins",
      "/replicas/*/fingerprint", "/trustedKeys/*", "/jump/hostKey", "/next/trustedExec", "/next/next/trustedExec",
      "/trust", "/proxy/pin", "/account", "/remote/key");

  /** What the configs below hold apart from their trust settings: what a share carries and an import creates. */
  private static final JsonNode PORTABLE = JSON.readTree("""
      {"host":"db.example.test","tls":{"minVersion":"TLSv1.3"},
       "replicas":[{"host":"r1.example.test"},{"host":"r2.example.test"}],"trustedKeys":[],
       "jump":{"host":"bastion.example.test"},"next":{"next":{"host":"deep.example.test"}},
       "credentials":{"user":"app"},"proxy":{"host":"proxy.example.test"},"remote":{"user":"deploy"}}
      """);

  /** Values of trust settings that may appear nowhere outside this computer's own connection. */
  private static final List<String> TRUSTED = List.of("exec-top-7f3a", "pin-one-7f3a", "fp-replica-7f3a",
      "key-one-7f3a", "key-two-7f3a", "jump-key-7f3a", "exec-next-7f3a", "exec-deep-7f3a", "cert-7f3a",
      "trust-pin-7f3a", "hook-7f3a", "proxy-pin-7f3a", "acct-7f3a", "remote-key-7f3a");

  private static final String TOO_COMPLEX = "The connection's settings are too large, or its connector's schema too "
      + "complex, to check for trust settings.";

  private final ConnectionConfigs configs = mock(ConnectionConfigs.class);
  private final ConnectionService connections = mock(ConnectionService.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final SecretService secrets = mock(SecretService.class);
  private final ConnectionShares shares = new ConnectionShares(configs, connections, registry, secrets,
      Masker.standard());

  @BeforeEach
  void setup() {
    register("trustfake", SCHEMA, true);
    when(configs.all()).thenReturn(List.of());
  }

  /** An installed connector type; one that is not usable cannot be imported. */
  private void register(String type, JsonNode schema, boolean usable) {
    ConnectorManifest manifest = ConnectorManifest.builder(type, "1", ConnectorManifest.Kind.BUILT_IN)
        .name("Trust test").configSchema(schema).build();
    when(registry.type(type)).thenReturn(Optional.of(new ConnectorType(manifest, usable, true)));
    when(registry.manifest(type)).thenReturn(Optional.of(manifest));
  }

  /**
   * A connection as this computer stores it, the way connectors and the export get it: the person trusted all of this
   * here, and two secrets are stored, whose placeholders the stored row holds and the config does not
   * ({@code ConnectionRows.toConfig}).
   */
  private static ObjectNode storedConfig() {
    return (ObjectNode) JSON.readTree("""
        {"host":"db.example.test","trustedExec":"exec-top-7f3a","allowExec":true,
         "tls":{"minVersion":"TLSv1.3","pins":["pin-one-7f3a"]},
         "replicas":[{"host":"r1.example.test","fingerprint":"fp-replica-7f3a"},{"host":"r2.example.test",
           "fingerprint":""}],
         "trustedKeys":["key-one-7f3a","key-two-7f3a"],
         "jump":{"host":"bastion.example.test","hostKey":"jump-key-7f3a"},
         "next":{"trustedExec":"exec-next-7f3a","next":{"host":"deep.example.test","trustedExec":"exec-deep-7f3a"}},
         "trust":{"clientCert":"cert-7f3a","pin":"trust-pin-7f3a","hook":"https://hooks.example.test/hook-7f3a"},
         "credentials":{"user":"app"},
         "proxy":{"host":"proxy.example.test","pin":"proxy-pin-7f3a"},"account":"acct-7f3a",
         "remote":{"user":"deploy","key":"remote-key-7f3a"}}
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
           "trust":{"clientCert":"cert-7f3a","pin":"trust-pin-7f3a","hook":"https://hooks.example.test/hook-7f3a"},
           "credentials":{"user":"app"},
           "proxy":{"host":"proxy.example.test","pin":"proxy-pin-7f3a"},"account":"acct-7f3a",
           "remote":{"user":"deploy","key":""}},
         "secrets":{"/credentials/password":"pw-shared-0123","/trust/clientKey":"key-from-elsewhere-7f3a"},
         "omittedSecretFields":[],"tools":[],"templates":[],"warnings":[]}
        """);
    return entry;
  }

  /** {@link #olderEntry} of another type, config and shared credentials. */
  private static ObjectNode entry(String type, JsonNode config, JsonNode secrets) {
    ObjectNode entry = olderEntry().put("type", type);
    entry.set("config", config.deepCopy());
    entry.set("secrets", secrets.deepCopy());
    return entry;
  }

  private void stored(ObjectNode config, Map<String, String> secretIds) {
    stored("c-1", "trustfake", config, secretIds);
  }

  private void stored(String id, String type, JsonNode config, Map<String, String> secretIds) {
    ConnectionConfig cfg = new ConnectionConfig(id, "trusted-db", type, Env.DEV, 3, config, secretIds.keySet(), null);
    when(configs.byId(id)).thenReturn(Optional.of(new ConnectionConfigs.Stored(cfg, true, null, NOW, NOW,
        secretIds)));
  }

  private static String exportWarning(String kinds) {
    return "Trust settings were omitted (" + kinds + "); confirm them on the destination computer with Test "
        + "connection.";
  }

  private static String reviewNote(String kinds) {
    return "Trust settings in this file are not imported: " + kinds + ". The connection is imported off, with Setup "
        + "required; confirm them on this computer with Test connection, then Trust, or set them in the connection "
        + "form.";
  }

  private static String setupItem(String kind) {
    return kind + ": Not imported. Confirm this trust setting on this computer with Test connection, or set it "
        + "yourself.";
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
    assertThat(entry.path("warnings").get(0).asString()).isEqualTo(exportWarning(String.join(", ", KINDS)));
  }

  /**
   * A credential field that is itself a trust setting is known to the export only by its stored secret, since the
   * config holds no placeholder: the export names it among the omitted trust settings, never among the credentials the
   * destination supplies.
   */
  @Test
  void aStoredCredentialThatIsATrustSettingIsNamedAmongTheOmittedTrustSettings() {
    stored((ObjectNode) PORTABLE.deepCopy(), Map.of("/credentials/password", "sec_pw", "/pinKey", "sec_pin"));

    ObjectNode entry = shares.export("c-1", false);

    assertThat(entry.path("config")).isEqualTo(PORTABLE);
    assertThat(entry.path("omittedSecretFields")).isEqualTo(JSON.readTree("[\"/credentials/password\"]"));
    assertThat(entry.path("warnings")).hasSize(1);
    assertThat(entry.path("warnings").get(0).asString()).isEqualTo(exportWarning("/pinKey"));
    verify(secrets, never()).get("sec_pin");
  }

  /** A local credential this connection owns would be shared with secrets included: not one of a trust setting. */
  @Test
  void anExportWithSecretsNeverIncludesTheCredentialOfATrustSetting() throws Exception {
    stored(storedConfig(), Map.of("/trust/clientKey", "sec_trust"));
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

  /**
   * A warning names kinds only within the 1,024 characters a share allows for one, and counts the rest: a config that
   * nests a reference to its root 45 levels deep holds a kind per level. A kind too long to name is counted.
   */
  @Test
  void aLongListOfKindsIsCutWithinWhatAShareAllowsForOneWarning() {
    ObjectNode config = JSON.createObjectNode();
    ObjectNode level = config;
    for (int i = 0; i < 45; i++) {
      level.put("trustedExec", "exec-" + i);
      level = level.putObject("next");
    }
    stored(config, Map.of());

    String warning = shares.export("c-1", false).path("warnings").get(0).asString();

    assertThat(warning).hasSizeLessThanOrEqualTo(1024)
        .startsWith("Trust settings were omitted (/trustedExec, /next/trustedExec, /next/next/trustedExec, ")
        .endsWith(" more); confirm them on the destination computer with Test connection.");
    assertThat(namedAndCounted(warning, "\\((.*) and (\\d+) more\\)")).isEqualTo(45);
    String note = shares.trustNote(entry("trustfake", config, JSON.createObjectNode())).orElseThrow();
    assertThat(note).hasSizeLessThanOrEqualTo(1024).contains(" more. The connection is imported off");
    assertThat(namedAndCounted(note, "imported: (.*) and (\\d+) more\\.")).isEqualTo(45);

    String name = "k".repeat(700);
    register("longfake", JSON.readTree("{\"type\":\"object\",\"properties\":{\"" + name + "\":{\"x-person-only\":true},"
        + "\"" + name + "2\":{\"x-person-only\":true}}}"), true);
    stored("c-one", "longfake", JSON.readTree("{\"" + name + "\":\"x\"}"), Map.of());
    stored("c-two", "longfake", JSON.readTree("{\"" + name + "\":\"x\",\"" + name + "2\":\"y\"}"), Map.of());
    assertThat(shares.export("c-one", false).path("warnings").get(0).asString()).isEqualTo(exportWarning("1 setting"));
    assertThat(shares.export("c-two", false).path("warnings").get(0).asString())
        .isEqualTo(exportWarning("2 settings"));
  }

  /** How many kinds a cut list names and counts: {@code pattern} finds the named list and the count of the rest. */
  private static int namedAndCounted(String text, String pattern) {
    Matcher list = Pattern.compile(pattern).matcher(text);
    assertThat(list.find()).as(text).isTrue();
    return list.group(1).split(", ").length + Integer.parseInt(list.group(2));
  }

  // ── Review ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theReviewShowsTheConfigAsItWillBeCreatedAndNamesWhatThePersonConfirms() {
    ObjectNode entry = olderEntry();

    assertThat(shares.previewConfig(entry)).isEqualTo(PORTABLE);
    assertThat(shares.trustNote(entry)).contains(reviewNote(String.join(", ", KINDS)));
    // Neither the credential of a trust setting nor a value inside one that may hold a key (/trust/hook) is one the
    // import takes over: only the shared password counts.
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
    claims.putArray("warnings").add(exportWarning("/trustedExec"));
    ObjectNode silent = olderEntry();
    silent.putArray("warnings").add("No trust settings in this connection.");

    assertThat(shares.trustNote(claims)).isEmpty();
    assertThat(shares.trustNote(silent)).isPresent();
  }

  /**
   * An entry that cannot be imported (its connector is switched off here) is reviewed as before: nothing of it is
   * created, so its type's schema is not walked, even one too complex to check, its settings are shown as the file
   * holds them, apart from values that may hold a key, and no note says how it is imported.
   */
  @Test
  void anEntryThatCannotBeImportedIsReviewedWithoutLookingForTrustSettings() {
    register("trustoff", SCHEMA, false);
    register("deepoff", chained(40), false);
    ObjectNode marked = olderEntry().put("type", "trustoff");
    marked.putArray("omittedSecretFields").add("/trust/clientKey");
    JsonNode shown = JSON.readTree("{\"host\":\"db.example.test\",\"deep\":\"x\"}");
    ObjectNode deep = entry("deepoff", shown, JSON.readTree("{\"/deep\":\"shared-0123\"}"));
    ((ObjectNode) deep.path("config")).put("url", "https://mcp.example.test/k3y-0123/mcp");

    shares.validate(deep, true);
    // As before: the URL that may hold a key is not shown and counts as a credential.
    assertThat(shares.previewConfig(deep)).isEqualTo(shown);
    assertThat(shares.secretCount(deep)).isEqualTo(2);
    assertThat(shares.trustNote(deep)).isEmpty();
    assertThat(shares.trustNote(marked)).isEmpty();
    // The file's two credentials and its value that may hold a key, which the review does not show.
    assertThat(shares.secretCount(marked)).isEqualTo(3);
    JsonNode reviewed = shares.previewConfig(marked);
    assertThat(reviewed.at("/next/trustedExec").asString()).isEqualTo("exec-next-7f3a");
    assertThat(reviewed.at("/trust/pin").asString()).isEqualTo("trust-pin-7f3a");
    assertThat(reviewed.path("trust").has("hook")).isFalse();
    assertThat(shares.omittedSecretFields(marked)).containsExactly("/trust/clientKey");
    assertThatThrownBy(() -> shares.prepareImport(marked, true, new HashSet<>())).isInstanceOf(ProblemException.class)
        .hasMessageContaining("Skip unavailable connectors");
    verify(connections, never()).missingImportSettings(anyString(), anyString(), any(), any(), any());
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
    ((ObjectNode) config.path("remote")).putObject("key").put("$secret", "sec_elsewhere");

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
  void anImportDropsTrustSettingsAndMakesTheConnectionADraftWithOneItemPerKind() {
    ConnectionShares.Prepared prepared = shares.prepareImport(olderEntry(), true, new HashSet<>());

    assertThat(prepared.config()).isEqualTo(PORTABLE);
    assertThat(prepared.secrets()).containsOnlyKeys("/credentials/password");
    List<String> expected = new ArrayList<>();
    KINDS.forEach(kind -> expected.add(setupItem(kind)));
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
      assertThat(prepared.missing()).as(value).containsExactly(setupItem("/trustedExec"));
    }
  }

  /** The file's warnings never decide: a claim makes no draft, and silence keeps one. */
  @Test
  void theFilesWarningsNeitherMakeNorPreventADraft() {
    ObjectNode claims = olderEntry();
    claims.set("config", PORTABLE.deepCopy());
    ((ObjectNode) claims.path("secrets")).remove("/trust/clientKey");
    claims.putArray("warnings").add(exportWarning("/trustedExec"));
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
        .doesNotContain("/trust/clientKey: Include or supply the credential.",
            "/trust/hook: Include or supply the credential.")
        .contains(setupItem("/trust"));
  }

  /**
   * A credential the source left out of a trust setting is not asked for, wherever the file lists it: the trust
   * setting is named instead, and the person decides it on this computer. Other omitted credentials are asked for as
   * before.
   */
  @Test
  void aCredentialTheSourceLeftOutOfATrustSettingIsNotAskedFor() {
    ObjectNode older = olderEntry();
    older.set("secrets", JSON.createObjectNode());
    older.putArray("omittedSecretFields").add("/trust/clientKey").add("/credentials/password").add("/pinKey");

    assertThat(shares.omittedSecretFields(older)).containsExactly("/credentials/password");
    assertThat(shares.prepareImport(older, true, new HashSet<>()).missing())
        .contains("/credentials/password: Supply the omitted credential.", setupItem("/trust"), setupItem("/pinKey"))
        .doesNotContain("/trust/clientKey: Supply the omitted credential.", "/pinKey: Supply the omitted credential.");

    // Listed alone, it still names its trust setting, and the connection is a draft that the person sets up.
    ObjectNode listed = olderEntry();
    listed.set("config", PORTABLE.deepCopy());
    listed.set("secrets", JSON.readTree("{\"/credentials/password\":\"pw-shared-0123\"}"));
    listed.putArray("omittedSecretFields").add("/trust/clientKey");
    assertThat(shares.omittedSecretFields(listed)).isEmpty();
    assertThat(shares.trustNote(listed)).contains(reviewNote("/trust"));
    assertThat(shares.prepareImport(listed, true, new HashSet<>()).missing()).containsExactly(setupItem("/trust"));
  }

  /**
   * A list of trust settings given as an object, as a schema without {@code "type": "array"} would let through, is left
   * out like the list: whoever iterates it reads its members as the items.
   */
  @Test
  void aListOfTrustSettingsGivenAsAnObjectIsLeftOutLikeTheList() {
    ObjectNode entry = olderEntry();
    entry.set("config", PORTABLE.deepCopy());
    ((ObjectNode) entry.path("config")).putObject("trustedKeys").put("0", "key-one-7f3a").put("next", "key-two-7f3a");
    ((ObjectNode) entry.path("secrets")).remove("/trust/clientKey");
    stored(((ObjectNode) entry.path("config")).deepCopy(), Map.of());

    ConnectionShares.Prepared prepared = shares.prepareImport(entry, true, new HashSet<>());

    assertThat(prepared.config().path("trustedKeys")).isEqualTo(JSON.createObjectNode());
    assertThat(prepared.missing()).containsExactly(setupItem("/trustedKeys/*"));
    assertThat(shares.previewConfig(entry).toString()).doesNotContain(TRUSTED);
    assertThat(shares.trustNote(entry)).contains(reviewNote("/trustedKeys/*"));
    ObjectNode exported = shares.export("c-1", false);
    assertThat(exported.toString()).doesNotContain(TRUSTED);
    assertThat(exported.path("warnings").get(0).asString()).isEqualTo(exportWarning("/trustedKeys/*"));
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
    ((ObjectNode) entry.path("secrets")).put("/pinKey", "pin-key-from-elsewhere-7f3a");

    ConnectionShares.Prepared prepared = shares.prepareImport(entry, true, new HashSet<>());

    assertThat(prepared.secrets()).containsOnlyKeys("/credentials/password");
    assertThat(prepared.missing()).containsExactly(setupItem("/trust"), setupItem("/pinKey"));
    assertThat(Set.copyOf(prepared.secrets().keySet())).doesNotContain("/trust/clientKey", "/pinKey");
  }

  // ── Other schemas ───────────────────────────────────────────────────────────────────────────────────────────

  /** A schema that marks its root makes the whole config one trust setting: nothing of it travels or is taken over. */
  @Test
  void aMarkedRootLeavesNothingOfTheConfig() {
    register("rootfake", JSON.readTree("""
        {"type":"object","x-person-only":true,"properties":{"host":{"type":"string"},
          "password":{"type":"string","writeOnly":true}}}
        """), true);
    stored("c-root", "rootfake", JSON.readTree("{\"host\":\"db.example.test\"}"), Map.of("/password", "sec_root"));

    ObjectNode exported = shares.export("c-root", true);

    assertThat(exported.path("config")).isEqualTo(JSON.createObjectNode());
    assertThat(exported.path("secrets")).isEmpty();
    assertThat(exported.path("omittedSecretFields")).isEmpty();
    assertThat(exported.path("warnings")).hasSize(1);
    assertThat(exported.path("warnings").get(0).asString()).isEqualTo(exportWarning("all settings"));
    verify(secrets, never()).get(anyString());

    ObjectNode older = entry("rootfake", JSON.readTree("{\"host\":\"db.example.test\"}"),
        JSON.readTree("{\"/password\":\"pw-shared-0123\"}"));
    assertThat(shares.previewConfig(older)).isEqualTo(JSON.createObjectNode());
    assertThat(shares.secretCount(older)).isZero();
    assertThat(shares.trustNote(older)).contains(reviewNote("all settings"));
    ConnectionShares.Prepared prepared = shares.prepareImport(older, true, new HashSet<>());
    assertThat(prepared.config()).isEqualTo(JSON.createObjectNode());
    assertThat(prepared.secrets()).isEmpty();
    assertThat(prepared.missing()).containsExactly(setupItem("Settings"));
    shares.importPrepared(prepared, "test");
    verify(connections).createImportDraft(eq("trusted-db"), eq("rootfake"), eq(Env.DEV),
        eq(JSON.createObjectNode()), any(), eq(prepared.missing()), isNull(), eq("test"));
  }

  /**
   * A connector schema too complex to check for trust settings is refused with a 422, wherever an export, a review or
   * an import meets it: in a value of the config, and in the place of a stored or shared credential.
   */
  @Test
  void aSchemaTooComplexToCheckForTrustSettingsIsRefused() {
    register("deepfake", chained(40), true);
    JsonNode holding = JSON.readTree("{\"host\":\"db.example.test\",\"deep\":\"x\"}");
    JsonNode without = JSON.readTree("{\"host\":\"db.example.test\"}");
    stored("c-deep", "deepfake", holding, Map.of());
    // As this computer stores a credential: only its secret names the place, and the walk meets it there.
    stored("c-deep-secret", "deepfake", without, Map.of("/deep", "sec_deep"));
    ObjectNode value = entry("deepfake", holding, JSON.createObjectNode());
    ObjectNode credential = entry("deepfake", without, JSON.readTree("{\"/deep\":\"shared-0123\"}"));

    List<ThrowingCallable> calls = List.of(() -> shares.export("c-deep", false),
        () -> shares.export("c-deep-secret", true), () -> shares.validate(value, true),
        () -> shares.prepareImport(value, true, new HashSet<>()), () -> shares.previewConfig(value),
        () -> shares.trustNote(value), () -> shares.secretCount(value), () -> shares.omittedSecretFields(value),
        () -> shares.validate(credential, true), () -> shares.prepareImport(credential, false, new HashSet<>()),
        () -> shares.trustNote(credential));
    for (ThrowingCallable call : calls) {
      assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> {
        assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
        assertThat(e.getMessage()).isEqualTo(TOO_COMPLEX);
      });
    }
    verify(secrets, never()).get(anyString());
    verify(connections, never()).missingImportSettings(anyString(), anyString(), any(), any(), any());
  }

  /**
   * The walk's work grows with the config: one too large to check along its connector's schema is refused like a schema
   * too complex, and one within the bound is checked to its end. Each list item has 10,001 definitions here.
   */
  @Test
  void aConfigTooLargeToCheckAlongItsSchemaIsRefusedToo() {
    register("widefake", itemsWith(10_000), true);
    ObjectNode within = entry("widefake", listOf(90), JSON.createObjectNode());
    ObjectNode beyond = entry("widefake", listOf(120), JSON.createObjectNode());

    assertThat(shares.prepareImport(within, true, new HashSet<>()).missing()).containsExactly(setupItem("/list/*/pin"));
    List<ThrowingCallable> calls = List.of(() -> shares.validate(beyond, true),
        () -> shares.prepareImport(beyond, true, new HashSet<>()), () -> shares.previewConfig(beyond));
    for (ThrowingCallable call : calls) {
      assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> {
        assertThat(e.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
        assertThat(e.getMessage()).isEqualTo(TOO_COMPLEX);
      });
    }
  }

  /** A schema of a {@code /list} whose items have {@code branches} empty {@code allOf} branches and a marked pin. */
  private static JsonNode itemsWith(int branches) {
    ObjectNode schema = JSON.createObjectNode().put("type", "object");
    ObjectNode items = schema.putObject("properties").putObject("list").put("type", "array").putObject("items");
    items.putObject("properties").putObject("pin").put("type", "string").put("x-person-only", true);
    var allOf = items.putArray("allOf");
    for (int i = 0; i < branches; i++) {
      allOf.addObject();
    }
    return schema;
  }

  /** A config whose {@code /list} has {@code size} items, the first one holding a pin. */
  private static JsonNode listOf(int size) {
    ObjectNode config = JSON.createObjectNode();
    var list = config.putArray("list");
    list.addObject().put("pin", "p");
    for (int i = 1; i < size; i++) {
      list.addObject();
    }
    return config;
  }

  /** A schema whose {@code /deep} is defined behind a chain of {@code links} references; its URL may hold a key. */
  private static JsonNode chained(int links) {
    ObjectNode schema = JSON.createObjectNode().put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    properties.putObject("host").put("type", "string");
    properties.putObject("url").put("type", "string").put("x-key-allowed", true);
    properties.putObject("deep").put("$ref", "#/$defs/d0");
    ObjectNode defs = schema.putObject("$defs");
    for (int i = 0; i < links; i++) {
      defs.putObject("d" + i).put("$ref", "#/$defs/d" + (i + 1));
    }
    defs.putObject("d" + links).put("type", "string").put("writeOnly", true).put("x-person-only", true);
    return schema;
  }
}
