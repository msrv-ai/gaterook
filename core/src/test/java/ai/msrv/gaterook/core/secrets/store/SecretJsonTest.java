package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Values out of a store's JSON answer without a String of them; the rest as trees. */
class SecretJsonTest {

  private static byte[] b(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void treesAtAPath() {
    byte[] body = b("{\"request_id\":\"r\",\"data\":{\"path\":\"kv/\",\"type\":\"kv\",\"options\":{\"version\":\"2\"}}}");
    assertThat(SecretJson.tree(body, List.of("data")).path("path").asString()).isEqualTo("kv/");
    assertThat(SecretJson.tree(body, List.of("data", "options")).path("version").asString()).isEqualTo("2");
    assertThat(SecretJson.tree(body, List.of()).path("request_id").asString()).isEqualTo("r");
    assertThat(SecretJson.tree(body, List.of("nope")).isMissingNode()).isTrue();
    assertThat(SecretJson.tree(b("{\"errors\":[\"permission denied\"]}"), List.of("errors")).get(0).asString())
        .isEqualTo("permission denied");
  }

  @Test
  void picksOneKeyAsBytesAndNamesTheOthers() {
    byte[] body = b("{\"data\":{\"data\":{\"password\":\"s3cr\\u00e9t\",\"port\":5432,\"tags\":[\"a\"]},"
        + "\"metadata\":{\"version\":4}}}");
    SecretJson.Picked p = SecretJson.pick(body, List.of("data", "data"), "password");
    assertThat(new String(p.value(), StandardCharsets.UTF_8)).isEqualTo("s3crét");
    assertThat(p.string()).isTrue();
    assertThat(p.keys()).containsExactly("password", "port", "tags");
    SecretJson.Picked number = SecretJson.pick(body, List.of("data", "data"), "port");
    assertThat(new String(number.value(), StandardCharsets.UTF_8)).isEqualTo("5432");
    assertThat(number.string()).isFalse();
    SecretJson.Picked missing = SecretJson.pick(body, List.of("data", "data"), "user");
    assertThat(missing.present()).isTrue();
    assertThat(missing.value()).isNull();
    SecretJson.Picked whole = SecretJson.pick(body, List.of("data", "data"), null);
    assertThat(new String(whole.value(), StandardCharsets.UTF_8))
        .isEqualTo("{\"password\":\"s3crét\",\"port\":5432,\"tags\":[\"a\"]}");
    assertThat(SecretJson.pick(b("{\"data\":null}"), List.of("data"), "x").present()).isFalse();
  }

  @Test
  void knowsAnObjectFromOtherJson() {
    assertThat(SecretJson.isObject(b("{\"a\":1}"))).isTrue();
    assertThat(SecretJson.isObject(b("{\"a\":1} trailing"))).isFalse();
    assertThat(SecretJson.isObject(b("plain"))).isFalse();
    assertThat(SecretJson.isObject(b("[1]"))).isFalse();
  }
}
