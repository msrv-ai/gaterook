package ai.msrv.gaterook.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class SecretInfoTest {

  @ParameterizedTest
  @ValueSource(strings = {"vault://database/creds/reader", "vault://team/database/creds/reader"})
  void identifiesDynamicLoginsWhileTheirStoreIsDisconnected(String ref) {
    assertThat(info(ref).dynamicReference()).isTrue();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "not-a-reference", "vault://kv/database#password",
      "vault://database/creds/reader#password", "vault://database/creds/reader?version=1",
      "vault://database/creds/reader/nested", "vault://creds/reader",
      "gcp-sm://projects/example/secrets/password/versions/latest", "aws-sm://eu-central-1/database/password"})
  void otherReferencesDoNotSupplyDynamicLogins(String ref) {
    assertThat(info(ref).dynamicReference()).isFalse();
  }

  @Test
  void classifyingAReferenceDoesNotAddFieldsToSecretMetadata() {
    var json = JsonMapper.builder().build().valueToTree(info("vault://database/creds/reader"));
    assertThat(json.size()).isEqualTo(15);
    assertThat(json.has("dynamicReference")).isFalse();
    assertThat(json.path("ref").asString()).isEqualTo("vault://database/creds/reader");
    assertThat(json.path("state").asString()).isEqualTo("not-connected");
  }

  private static SecretInfo info(String ref) {
    return new SecretInfo("password", "password", SecretOwner.connection("connection-id"),
        "/credentials/password", "dev-db", List.of(), "vault", null, null, null,
        "not-connected", false, null, ref, null);
  }
}
