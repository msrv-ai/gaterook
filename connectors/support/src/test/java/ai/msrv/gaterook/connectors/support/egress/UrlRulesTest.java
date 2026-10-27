package ai.msrv.gaterook.connectors.support.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Report 10 §6.2: base URLs from people, paths from agents. */
class UrlRulesTest {

  private final HttpUrl base = UrlRules.parse("https://billing.stage.acme.dev/v1");

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "/refunds                 | https://billing.stage.acme.dev/v1/refunds",
      "/refunds/re_1            | https://billing.stage.acme.dev/v1/refunds/re_1",
      "/refunds?limit=5&x=a%20b | https://billing.stage.acme.dev/v1/refunds?limit=5&x=a%20b",
      "/refunds#frag            | https://billing.stage.acme.dev/v1/refunds",
      "/                        | https://billing.stage.acme.dev/v1/",
      "/a%20b                   | https://billing.stage.acme.dev/v1/a%20b",
      "/a;v=1/b                 | https://billing.stage.acme.dev/v1/a;v=1/b",
  })
  void resolvesPathsUnderTheBase(String path, String expected) {
    assertThat(UrlRules.resolve(base, path)).hasToString(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"refunds", "//evil.com/x", "/\\evil.com/x", "\\\\evil.com/x", "/../admin", "/v2/../../admin",
      "/%2e%2e/admin", "/..%2fadmin", "/a%2Fb", "/a%5cb", "/a%00", "/a%1f", "/a%7F", "/.", "/./x", "/a/%2E", "/a b",
      "/a\nb", "/a\tb",
      // Servlet containers cut ;parameters off a segment before they resolve dot segments: ..;x is .. to them.
      "/..;/admin", "/a/..;jsessionid=1/b", "/.;x/a", "/%2e%2e;x/admin", "/..%3Badmin", "/..%3b/admin"})
  void refusesPathsThatEscapeOrSmuggle(String path) {
    assertThatThrownBy(() -> UrlRules.resolve(base, path)).isInstanceOf(UrlRules.UrlRejectedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://user@billing.stage.acme.dev/v1", "https://user:pw@billing.stage.acme.dev/",
      "ftp://billing.stage.acme.dev/", "https://billing.stage.acme.dev/#x", "https://2130706433/",
      "http://0177.0.0.1/", "http://127.1/", "https://metadata.google.internal/computeMetadata/v1/",
      "https://billing.stage.acme.dev/a%2fb", "https://billing.stage.acme.dev/ a", "", "not a url",
      // The host OkHttp reads, not only the host as written: 127.1 behind a backslash, 0177.0.0.1 in full-width digits.
      "https://127.1\\x/", "https://\uFF10\uFF11\uFF17\uFF17.\uFF10.\uFF10.\uFF11/",
      "https://billing.stage.acme.dev/v1/..;/admin"})
  void refusesBadBaseUrls(String url) {
    assertThatThrownBy(() -> UrlRules.parse(url)).isInstanceOf(UrlRules.UrlRejectedException.class);
  }

  @Test
  void prefixMatchingIsBySegmentNotByText() {
    HttpUrl v1 = UrlRules.parse("https://billing.stage.acme.dev/v1/");
    assertThat(UrlRules.within(v1, HttpUrl.get("https://billing.stage.acme.dev/v1/refunds"))).isTrue();
    assertThat(UrlRules.within(v1, HttpUrl.get("https://billing.stage.acme.dev/v1"))).isTrue();
    assertThat(UrlRules.within(base, HttpUrl.get("https://billing.stage.acme.dev/v10"))).isFalse();
    assertThat(UrlRules.within(v1, HttpUrl.get("https://billing.stage.acme.dev.evil.com/v1/refunds"))).isFalse();
    assertThat(UrlRules.within(v1, HttpUrl.get("http://billing.stage.acme.dev/v1/refunds"))).isFalse();
    assertThat(UrlRules.within(v1, HttpUrl.get("https://billing.stage.acme.dev:8443/v1/refunds"))).isFalse();
    assertThat(UrlRules.within(v1, HttpUrl.get("https://BILLING.stage.acme.dev./v1/x"))).isTrue();
    assertThat(UrlRules.within(v1, HttpUrl.get("https://billing.stage.acme.dev/admin"))).isFalse();
  }
}
