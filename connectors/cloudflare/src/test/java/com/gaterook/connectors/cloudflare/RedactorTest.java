package com.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The masking rules of Cloudflare answers, one by one. */
class RedactorTest {

  private static ObjectNode json(String text) {
    return (ObjectNode) JsonMapper.builder().build().readTree(text);
  }

  @Test
  void secretKeysAreMaskedButNamesCountsAndCursorsStay() {
    ObjectNode o = json("{\"token\":\"t-1\",\"tunnel_secret\":\"s\",\"client_secret\":\"c\",\"secret_name\":\"DB\","
        + "\"token_type\":\"bearer\",\"api_key_id\":\"k1\",\"cursor\":\"x\",\"token_count\":3,\"nested\":{"
        + "\"password\":\"p\",\"passwordRequired\":true},\"list\":[{\"private_key\":\"-----BEGIN\"}]}");
    int n = Redactor.redact(o);
    assertThat(n).isEqualTo(5);
    assertThat(o.path("token").asString()).isEqualTo(Redactor.MASK);
    assertThat(o.path("secret_name").asString()).isEqualTo("DB");
    assertThat(o.path("token_type").asString()).isEqualTo("bearer");
    assertThat(o.path("api_key_id").asString()).isEqualTo("k1");
    assertThat(o.path("cursor").asString()).isEqualTo("x");
    assertThat(o.at("/nested/password").asString()).isEqualTo(Redactor.MASK);
    assertThat(o.at("/nested/passwordRequired").asBoolean()).isTrue();
    assertThat(o.at("/list/0/private_key").asString()).isEqualTo(Redactor.MASK);
    assertThat(Redactor.redact(o)).as("masking twice masks nothing more").isZero();
  }

  @Test
  void destinationsKeepTheirPlace() {
    assertThat(Redactor.destination("https://user:pa55@logs.example.com/ingest/v1?header_Authorization=Basic%20abc"
        + "&tag=edge").text()).isEqualTo("https://‹masked›@logs.example.com/ingest/v1?header_Authorization=‹masked›"
        + "&tag=edge");
    assertThat(Redactor.destination("gs://bucket/path/{DATE}").text()).isEqualTo("gs://bucket/path/{DATE}");
    assertThat(Redactor.destination("azure://container/logs?sv=2022-11-02&sig=abcDEF123%2B&se=2027-01-01").text())
        .isEqualTo("azure://container/logs?sv=2022-11-02&sig=‹masked›&se=2027-01-01");
  }

  @Test
  void requestUrlsLoseOnlyCredentials() {
    assertThat(Redactor.query("/v1/orders?page=2&access_token=abc&session_id=s1&utm_source=x").text())
        .isEqualTo("/v1/orders?page=2&access_token=‹masked›&session_id=‹masked›&utm_source=x");
    assertThat(Redactor.query("/plain/path").count()).isZero();
  }

  @Test
  void pagesVariablesAreMaskedByTypeAndName() {
    ObjectNode o = json("{\"env_vars\":{\"NODE_VERSION\":{\"type\":\"plain_text\",\"value\":\"22\"},\"GH_TOKEN\":{"
        + "\"type\":\"plain_text\",\"value\":\"ghp_x\"},\"SENTRY_DSN\":{\"type\":\"plain_text\",\"value\":\"https://k@o"
        + ".ingest.sentry.io/1\"},\"OTHER\":{\"type\":\"secret_text\",\"value\":\"v\"},\"LEGACY\":\"flat\"}}");
    assertThat(Redactor.redact(o)).isEqualTo(4);
    assertThat(o.at("/env_vars/NODE_VERSION/value").asString()).isEqualTo("22");
    assertThat(o.at("/env_vars/GH_TOKEN/value").asString()).isEqualTo(Redactor.MASK);
    assertThat(o.at("/env_vars/SENTRY_DSN/value").asString()).isEqualTo(Redactor.MASK);
    assertThat(o.at("/env_vars/OTHER/value").asString()).isEqualTo(Redactor.MASK);
    assertThat(o.at("/env_vars/LEGACY").asString()).isEqualTo(Redactor.MASK);
  }
}
