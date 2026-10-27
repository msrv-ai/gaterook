package ai.msrv.gaterook.connectors.http;

import static ai.msrv.gaterook.connectors.http.Fixtures.call;
import static ai.msrv.gaterook.connectors.http.Fixtures.cfg;
import static ai.msrv.gaterook.connectors.http.Fixtures.policy;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * {@code classify} of report 10 §3.4 with §6.2–§6.3: no I/O, no secret; what the connection and the environment's
 * allowlist refuse is a DENY with its rule, GET and HEAD read, everything else is a mutation.
 */
class HttpClassifyTest {

  private final HttpConnector connector = new HttpConnector();
  private final ConnectionConfig billing = cfg("billing-api", Env.STAGE, """
      {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"bearer"},
       "allowedPaths":["/invoices/**","/refunds/**","/export/**"],"mutatingGetPaths":["/export/run"]}""");
  private final PolicyView stage = policy(Env.STAGE, "https://billing.stage.acme.dev");

  private Classification classify(String tool, String args) {
    return connector.classify(call(tool, args), billing, stage);
  }

  @Test
  void aGetReadsAndAWriteNeedsAPerson() {
    Classification read = classify("http_get", "{\"connection\":\"billing-api\",\"path\":\"/invoices?limit=10\"}");
    assertThat(read.kind()).isEqualTo(Classification.Kind.READ);
    assertThat(read.facts().path("path").asString()).isEqualTo("/invoices");

    assertThat(classify("http_request", "{\"method\":\"GET\",\"path\":\"/invoices/in_1\"}").kind())
        .isEqualTo(Classification.Kind.READ);
    for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
      Classification c = classify("http_request", "{\"method\":\"" + method + "\",\"path\":\"/refunds\""
          + (method.equals("DELETE") ? "" : ",\"json\":{\"amount\":1}") + "}");
      assertThat(c.kind()).as(method).isEqualTo(Classification.Kind.MUTATION);
      assertThat(c.reason()).isEqualTo(method + " changes data on billing-api");
    }
  }

  @Test
  void aGetThatChangesStateIsRefusedByHttpGetAndAskedForByHttpRequest() {
    Classification get = classify("http_get", "{\"path\":\"/export/run\"}");
    assertThat(get.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(get.ruleId()).isEqualTo("stage.http.mutating-get");
    assertThat(get.reason()).isEqualTo("GET /v1/export/run changes state on billing-api.");
    assertThat(get.hint()).isEqualTo("Use http_request; it asks first.");

    Classification request = classify("http_request", "{\"method\":\"GET\",\"path\":\"/export/run\"}");
    assertThat(request.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(classify("http_get", "{\"path\":\"/export/runs\"}").kind()).isEqualTo(Classification.Kind.READ);
  }

  /**
   * Servers route a path as they read it, not as it is written: an empty segment, a {@code ;parameter} or another case
   * still reach the GET that changes state, so {@code http_get} refuses each spelling (and allowed paths hold for the
   * path as written and as routed).
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "/export//run", "/export;x/run", "/export/run;jsessionid=1", "/Export/Run", "/EXPORT/run/", "/export/r%75n"})
  void aGetThatChangesStateIsRefusedInEverySpellingAServerRoutesToIt(String path) {
    ConnectionConfig anyPath = cfg("billing-api", Env.STAGE, """
        {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"bearer"},"mutatingGetPaths":["/export/run"]}""");
    Classification c = connector.classify(call("http_get", "{\"path\":\"" + path + "\"}"), anyPath, stage);

    assertThat(c.kind()).as(path).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).as(path).isEqualTo("stage.http.mutating-get");
    assertThat(connector.classify(call("http_request", "{\"method\":\"GET\",\"path\":\"" + path + "\"}"), anyPath,
        stage).kind()).as(path).isEqualTo(Classification.Kind.MUTATION);
  }

  @Test
  void narrowAllowedPathsDontLetAnEmptySegmentPastAGetThatChangesState() {
    // /export/** of the billing connection matches /export//run as written; the server runs /export/run.
    assertThat(classify("http_get", "{\"path\":\"/export//run\"}").ruleId()).isEqualTo("stage.http.mutating-get");
  }

  @Test
  void anAllowedPathHoldsForThePathAsWrittenAndAsRouted() {
    ConnectionConfig twoLevels = cfg("billing-api", Env.STAGE, """
        {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"bearer"},"allowedPaths":["/a/*/b"]}""");
    assertThat(connector.classify(call("http_get", "{\"path\":\"/a/x/b\"}"), twoLevels, stage).kind())
        .isEqualTo(Classification.Kind.READ);
    // Written, * matches the empty segment; routed, the server serves /a/b, which isn't allowed.
    for (String path : new String[] {"/a//b", "/a/;x/b"}) {
      Classification c = connector.classify(call("http_get", "{\"path\":\"" + path + "\"}"), twoLevels, stage);
      assertThat(c.ruleId()).as(path).isEqualTo("stage.http.url-not-allowed");
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "/admin                                   | url-not-allowed | /admin is outside the allowed paths",
      "/invoices/..;/admin                      | url-not-allowed | . or .. segments",
      "/invoices/..;jsessionid=1/admin          | url-not-allowed | . or .. segments",
      "https://billing.stage.acme.dev/v1/invoices/..;/admin | url-not-allowed | . or .. segments",
      "//evil.com/x                             | url-not-allowed | can't start with //",
      "\\\\evil.com/x                           | url-not-allowed | must start with /",
      "/invoices/../admin                       | url-not-allowed | . or .. segments",
      "/invoices/%2e%2e/admin                   | url-not-allowed | . or .. segments",
      "/invoices/..%2fadmin                     | url-not-allowed | %2F",
      "/invoices/a%00b                          | url-not-allowed | %2F, %5C or escaped control",
      "https://billing.stage.acme.dev.evil.com/v1/invoices | url-not-allowed | isn't under a base URL",
      "https://user@billing.stage.acme.dev/v1/invoices     | url-not-allowed | credentials",
      "https://billing.stage.acme.dev/v10/invoices         | url-not-allowed | isn't under a base URL",
      "http://billing.stage.acme.dev/v1/invoices           | url-not-allowed | isn't under a base URL",
      "https://2130706433/v1/invoices                      | url-not-allowed | non-standard form",
      "https://169.254.169.254/latest/meta-data/          | url-not-allowed | isn't under a base URL",
      "https://metadata.google.internal/computeMetadata/v1 | url-not-allowed | cloud metadata name"})
  void pathsOutsideTheConnectionAreRefusedBeforeAnyIo(String path, String rule, String text) {
    Classification c = classify("http_get", "{\"path\":\"" + path.replace("\\", "\\\\") + "\"}");

    assertThat(c.kind()).as(path).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).as(path).isEqualTo("stage.http." + rule);
    assertThat(c.reason()).as(path).contains(text);
  }

  @Test
  void aFullUrlUnderTheBaseIsAllowed() {
    assertThat(classify("http_get", "{\"path\":\"https://BILLING.stage.acme.dev/v1/invoices\"}").kind())
        .isEqualTo(Classification.Kind.READ);
  }

  @Test
  void theEnvironmentsAllowlistIsNeededToo() {
    Classification none = connector.classify(call("http_get", "{\"path\":\"/invoices\"}"), billing,
        policy(Env.STAGE));
    assertThat(none.ruleId()).isEqualTo("stage.http.url-not-allowed");
    assertThat(none.reason()).isEqualTo("https://billing.stage.acme.dev/v1/invoices isn't in the stage allowlist of "
        + "HTTP egress.");
    assertThat(none.hint()).contains("Policies › stage › HTTP egress");

    Classification narrower = connector.classify(call("http_get", "{\"path\":\"/invoices\"}"), billing,
        policy(Env.STAGE, "https://billing.stage.acme.dev/v2"));
    assertThat(narrower.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(connector.classify(call("http_get", "{\"path\":\"/invoices\"}"), billing,
        policy(Env.STAGE, "https://billing.stage.acme.dev/v1/invoices")).kind()).isEqualTo(Classification.Kind.READ);
  }

  @Test
  void headersTheGatewayOrTheHttpLayerSetAreRefused() {
    for (String h : new String[] {"Authorization", "Host", "Cookie", "X-Forwarded-For", "Proxy-Authorization",
        "Accept-Encoding", "Transfer-Encoding", "Connection", "Content-Length", "X-Real-IP", "Forwarded",
        // Servers that rewrite the path from a header (IIS, Symfony) or trust the caller's address from one.
        "X-Original-URL", "X-Rewrite-URL", "X-Override-URL", "X-Client-IP", "True-Client-IP", "CF-Connecting-IP",
        "X-Cluster-Client-IP", "X-Originating-IP", "X-Remote-IP", "X-Remote-Addr", "Client-IP", "X-Host"}) {
      Classification c = classify("http_get", "{\"path\":\"/invoices\",\"headers\":{\"" + h + "\":\"x\"}}");
      assertThat(c.ruleId()).as(h).isEqualTo("stage.http.header-not-allowed");
    }
    Classification ct = classify("http_request", "{\"method\":\"POST\",\"path\":\"/refunds\",\"headers\":"
        + "{\"Content-Type\":\"text/xml\"},\"json\":{}}");
    assertThat(ct.ruleId()).isEqualTo("stage.http.header-not-allowed");
    assertThat(ct.hint()).contains("contentType");

    ConnectionConfig apiKey = cfg("billing-api", Env.STAGE, """
        {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"apiKeyHeader","headerName":"X-Api-Key"},
         "secretHeaders":[{"name":"X-Webhook-Secret","value":{"$secret":"s1"},"methods":["POST"]}]}""");
    for (String h : new String[] {"x-api-key", "X-Webhook-Secret"}) {
      Classification c = connector.classify(call("http_get", "{\"path\":\"/x\",\"headers\":{\"" + h
          + "\":\"guess\"}}"), apiKey, stage);
      assertThat(c.ruleId()).as(h).isEqualTo("stage.http.header-not-allowed");
      assertThat(c.reason()).contains("credentials itself");
    }
    Classification crlf = classify("http_get", "{\"path\":\"/invoices\",\"headers\":{\"X-Note\":\"a\\r\\nX-Evil: 1\"}}");
    assertThat(crlf.ruleId()).isEqualTo("stage.http.header-not-allowed");
    assertThat(crlf.reason()).doesNotContain("X-Evil");
    Classification badName = classify("http_get", "{\"path\":\"/invoices\",\"headers\":{\"X Bad\":\"1\"}}");
    assertThat(badName.ruleId()).isEqualTo("stage.http.header-not-allowed");
  }

  @Test
  void theKeyOfApiKeyQueryIsTheGatewaysToSet() {
    ConnectionConfig query = cfg("billing-api", Env.STAGE, """
        {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"apiKeyQuery","queryName":"api_key"}}""");
    for (String args : new String[] {"{\"path\":\"/invoices?api_key=x\"}",
        "{\"path\":\"/invoices\",\"query\":{\"api_key\":\"x\"}}"}) {
      Classification c = connector.classify(call("http_get", args), query, stage);
      assertThat(c.ruleId()).as(args).isEqualTo("stage.http.query-not-allowed");
    }
  }

  @Test
  void bodiesFollowTheirRules() {
    assertThat(classify("http_request", "{\"method\":\"GET\",\"path\":\"/invoices\",\"json\":{}}").ruleId())
        .isEqualTo("stage.http.invalid-request");
    assertThat(classify("http_request", "{\"method\":\"POST\",\"path\":\"/refunds\",\"json\":{},\"body\":\"x\"}")
        .ruleId()).isEqualTo("stage.http.invalid-request");
    assertThat(classify("http_request", "{\"method\":\"POST\",\"path\":\"/refunds\",\"body\":\"x\","
        + "\"contentType\":\"text/plain\\r\\nX: y\"}").ruleId()).isEqualTo("stage.http.invalid-request");
    String big = "x".repeat(256 * 1024 + 1);
    Classification tooLarge = classify("http_request", "{\"method\":\"POST\",\"path\":\"/refunds\",\"body\":\""
        + big + "\"}");
    assertThat(tooLarge.ruleId()).isEqualTo("stage.http.body-too-large");
    assertThat(tooLarge.reason()).isEqualTo("The body has 262,145 bytes; stage allows 262,144 (256 KB) (HTTP egress "
        + "› Max request body).");
  }

  @Test
  void plainHttpNeedsTheConnectionsConsentAndNeverRunsOnProd() {
    ConnectionConfig plain = cfg("internal", Env.DEV, """
        {"baseUrls":["http://billing.internal.test"],"auth":{"type":"none"}}""");
    Classification refused = connector.classify(call("http_get", "{\"path\":\"/x\"}"), plain,
        policy(Env.DEV, "http://billing.internal.test"));
    assertThat(refused.ruleId()).isEqualTo("dev.http.plain-http");

    ConnectionConfig allowed = cfg("internal", Env.DEV, """
        {"baseUrls":["http://billing.internal.test"],"auth":{"type":"none"},"advanced":{"allowPlainHttp":true}}""");
    assertThat(connector.classify(call("http_get", "{\"path\":\"/x\"}"), allowed,
        policy(Env.DEV, "http://billing.internal.test")).kind()).isEqualTo(Classification.Kind.READ);

    ConnectionConfig onProd = cfg("internal", Env.PROD, """
        {"baseUrls":["http://billing.internal.test"],"auth":{"type":"none"},"advanced":{"allowPlainHttp":true}}""");
    assertThat(connector.classify(call("http_get", "{\"path\":\"/x\"}"), onProd,
        policy(Env.PROD, "http://billing.internal.test")).ruleId()).isEqualTo("prod.http.plain-http");
  }

  @Test
  void followingRedirectsNeedsTheWholeBaseUrlInTheAllowlist() {
    ConnectionConfig follows = cfg("billing-api", Env.STAGE, """
        {"baseUrls":["https://billing.stage.acme.dev"],"auth":{"type":"none"},
         "advanced":{"followRedirects":"same-origin"}}""");
    Classification c = connector.classify(call("http_get", "{\"path\":\"/v1/invoices\"}"), follows,
        policy(Env.STAGE, "https://billing.stage.acme.dev/v1"));
    assertThat(c.ruleId()).isEqualTo("stage.http.redirects-outside-allowlist");
    assertThat(connector.classify(call("http_get", "{\"path\":\"/v1/invoices\"}"), follows,
        policy(Env.STAGE, "https://billing.stage.acme.dev")).kind()).isEqualTo(Classification.Kind.READ);
  }

  @Test
  void aBrokenConfigIsADenyNotACrash() {
    ConnectionConfig broken = cfg("billing-api", Env.DEV, "{\"baseUrls\":[],\"auth\":{\"type\":\"bearer\"}}");
    Classification c = connector.classify(call("http_get", "{\"path\":\"/x\"}"), broken, policy(Env.DEV));
    assertThat(c.ruleId()).isEqualTo("dev.http.invalid-config");
  }
}
