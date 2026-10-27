package ai.msrv.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GlobTest {

  @ParameterizedTest
  @CsvSource({
      "arn:aws:ecs:eu-central-1:*:service/stage/*, arn:aws:ecs:eu-central-1:412398765012:service/stage/api, true",
      "arn:aws:ecs:eu-central-1:*:service/stage/*, arn:aws:ecs:eu-central-1:412398765012:service/prod/api,  false",
      "arn:aws:rds:eu-central-1:*:db:stage-*,      arn:aws:rds:eu-central-1:1:db:stage-orders,              true",
      "//run.googleapis.com/projects/acme-stage/locations/*/services/*, //run.googleapis.com/projects/acme-stage/locations/eu/services/api, true",
      "*,                                          anything/at:all,                                          true",
      "a?c,                                        abc,                                                      true",
      "a?c,                                        ac,                                                       false",
      "stage-*,                                    prod-stage-x,                                             false",
      "*-x,                                        a-b-x,                                                    true",
  })
  void textGlobsCrossSeparators(String pattern, String value, boolean matches) {
    assertThat(Glob.text(pattern).matches(value)).isEqualTo(matches);
  }

  @ParameterizedTest
  @CsvSource({
      "/invoices/**,  /invoices,          true",
      "/invoices/**,  /invoices/,         true",
      "/invoices/**,  /invoices/1/lines,  true",
      "/invoices/**,  /invoicesX,         false",
      "/refunds/*,    /refunds/re_1,      true",
      "/refunds/*,    /refunds/re_1/x,    false",
      "/refunds/*,    /refunds,           false",
      "/**,           /,                  true",
      "/**,           /a/b/c,             true",
      "/a/*/c,        /a/b/c,             true",
      "/a/*/c,        /a/b/x/c,           false",
      "/a/**/c,       /a/b/x/c,           true",
      "/a/**/c,       /a/c,               false",
      "/v?/x,         /v1/x,              true",
      "/v?/x,         ///x,               false",
  })
  void pathGlobsStayInSegments(String pattern, String value, boolean matches) {
    assertThat(Glob.path(pattern).matches(value)).isEqualTo(matches);
  }

  @Test
  void pathologicalPatternsStayLinear() {
    String pattern = "*a*a*a*a*a*a*a*a*a*a*a*a*b";
    String value = "a".repeat(20_000);
    long start = System.nanoTime();
    assertThat(Glob.text(pattern).matches(value)).isFalse();
    assertThat(Glob.path("/**a**a**a**a**a**a**b").matches("/" + value)).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
  }
}
