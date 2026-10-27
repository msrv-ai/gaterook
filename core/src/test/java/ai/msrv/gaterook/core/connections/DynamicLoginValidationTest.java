package ai.msrv.gaterook.core.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connections.internal.SecretFields;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SchemaValidator;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class DynamicLoginValidationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PASSWORD = "/credentials/password";
  private static final ConnectorManifest MANIFEST = ConnectorManifest
      .builder("login-test", "1.0.0", ConnectorManifest.Kind.BUILT_IN)
      .configSchema(JSON.readTree("""
          {"type":"object","required":["credentials","host"],"properties":{
            "host":{"type":"string","minLength":1},
            "credentials":{"type":"object","x-gateway-secret":"login","required":["user"],
              "properties":{"user":{"type":"string","maxLength":63},
                "password":{"type":"string","writeOnly":true}}},
            "other":{"type":"string","writeOnly":true}}}
          """)).build();
  private final SecretService secrets = mock(SecretService.class);
  private final ConnectionService service = new ConnectionService(null, null, null, null, secrets,
      new SchemaValidator(), new Masker(), null, null, null, null, null, null, Clock.systemUTC(),
      mock(PlatformTransactionManager.class));

  @Test
  void newDynamicLoginValidatesWithoutAddingAUserToTheSavedConfig() {
    ObjectNode config = config();
    ObjectNode before = config.deepCopy();
    service.validate(MANIFEST, config, Set.of(PASSWORD), Set.of(PASSWORD), Env.DEV, false);
    assertThat(config).isEqualTo(before);
    assertThat(config.at("/credentials/user").isMissingNode()).isTrue();
  }

  @Test
  void staticCredentialsAndUnrelatedDynamicSecretsStillRequireTheUser() {
    assertUserRequired(() -> service.validate(MANIFEST, config(), Set.of(PASSWORD), Env.DEV));
    assertUserRequired(() -> service.validate(MANIFEST, config(), Set.of(PASSWORD, "/other"),
        Set.of("/other"), Env.DEV, false));
  }

  @Test
  void dynamicLoginDoesNotSkipOtherRequiredSettings() {
    ObjectNode config = config();
    config.remove("host");
    assertThatThrownBy(() -> service.validate(MANIFEST, config, Set.of(PASSWORD), Set.of(PASSWORD), Env.DEV, false))
        .isInstanceOfSatisfying(ProblemException.class, error -> assertThat(error.field()).isEqualTo("/config/host"));
  }

  @Test
  void storedDynamicMetadataWorksEvenWhenTheStoreIsNotConnected() {
    when(secrets.get("leased-password")).thenReturn(info("vault://database/creds/reader"));
    ObjectNode config = config();
    SecretFields.set(config, PASSWORD, SecretFields.placeholder("leased-password"));
    ObjectNode before = config.deepCopy();
    service.validate(MANIFEST, config, Set.of(), Env.DEV);
    assertThat(config).isEqualTo(before);
    assertThat(service.dynamicSecretFields(Map.of(PASSWORD, "leased-password"))).containsExactly(PASSWORD);
  }

  @Test
  void staticAndMissingStoredSecretsDoNotProvideAUser() {
    when(secrets.get("stored-password")).thenReturn(info("vault://kv/database#password"));
    when(secrets.get("missing-password")).thenThrow(SecretService.NoSuchSecretException.class);
    for (String id : new String[] {"stored-password", "missing-password"}) {
      ObjectNode config = config();
      SecretFields.set(config, PASSWORD, SecretFields.placeholder(id));
      assertUserRequired(() -> service.validate(MANIFEST, config, Set.of(), Env.DEV));
    }
  }

  private static void assertUserRequired(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class,
        error -> assertThat(error.field()).isEqualTo("/config/credentials/user"));
  }

  private static ObjectNode config() {
    return (ObjectNode) JSON.readTree("{\"host\":\"db.example.test\",\"credentials\":{}}");
  }

  private static SecretInfo info(String ref) {
    return new SecretInfo("leased-password", "password", SecretOwner.connection("connection-id"), PASSWORD,
        "dev-db", List.of(), "vault", null, null, null, "not-connected", false, null, ref, null);
  }
}
