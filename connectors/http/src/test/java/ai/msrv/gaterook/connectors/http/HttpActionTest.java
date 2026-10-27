package ai.msrv.gaterook.connectors.http;

import static ai.msrv.gaterook.connectors.http.Fixtures.call;
import static ai.msrv.gaterook.connectors.http.Fixtures.cfg;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * What the approver reads (report 10 §3.5, kit {@code elements/approval-request.html} "Exact action · HTTP"): the
 * request byte for byte with the credentials as {@code ‹masked›}, the headers OkHttp adds named apart, and the
 * canonical payload that runs after Approve.
 */
class HttpActionTest {

  private final HttpConnector connector = new HttpConnector();
  private final ConnectionConfig billing = cfg("billing-api", Env.STAGE, """
      {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"bearer"}}""");

  private static final String REFUND = """
      {"connection":"billing-api","method":"POST","path":"/refunds","headers":{"Idempotency-Key":"refund-48213"},
       "json":{"order_id":48213,"amount":1999,"currency":"EUR","reason":"duplicate charge on order 48213"},
       "note":"The customer was charged twice for order 48213."}""";

  @Test
  void theRefundOfTheKitIsShownAsItIsSent() {
    ActionPreview p = connector.describeAction(call("http_request", REFUND), billing);

    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(p.language()).isEqualTo("http");
    assertThat(p.text()).isEqualTo("""
        POST https://billing.stage.acme.dev/v1/refunds
        Content-Type: application/json
        Idempotency-Key: refund-48213
        Authorization: ‹masked›

        {
          "order_id": 48213,
          "amount": 1999,
          "currency": "EUR",
          "reason": "duplicate charge on order 48213"
        }""");
    assertThat(p.target()).isEqualTo("billing-api · billing.stage.acme.dev");
    assertThat(p.summary()).isEqualTo("POST /v1/refunds · billing-api (stage)");
    assertThat(p.impact()).containsExactly(
        new ActionPreview.Field("Host", "billing.stage.acme.dev, in the stage allowlist"),
        new ActionPreview.Field("Retries", "Safe: idempotency key set"));
    byte[] sent = Base64.getDecoder().decode(p.payload().path("body").asString());
    assertThat(p.fields()).containsExactly(new ActionPreview.Field("Also sent by the gateway", "Host, "
        + "Content-Length: " + sent.length + ", Connection: Keep-Alive, User-Agent: GateRook/" + BuiltIn.VERSION
        + ", Accept-Encoding: gzip"));
    assertThat(p.auditFacets()).containsEntry("http.method", "POST").containsEntry("http.host",
        "billing.stage.acme.dev");

    JsonNode payload = p.payload();
    assertThat(payload.path("v").asInt()).isEqualTo(1);
    assertThat(payload.path("method").asString()).isEqualTo("POST");
    assertThat(payload.path("url").asString()).isEqualTo("https://billing.stage.acme.dev/v1/refunds");
    assertThat(payload.path("headers").toString()).isEqualTo("[[\"Idempotency-Key\",\"refund-48213\"]]");
    assertThat(payload.path("contentType").asString()).isEqualTo("application/json");
    assertThat(payload.path("auth").asString()).isEqualTo("bearer");
    byte[] body = Base64.getDecoder().decode(payload.path("body").asString());
    assertThat(p.text()).endsWith(new String(body, StandardCharsets.UTF_8));
    assertThat(body).hasSize(109);
    // No secret anywhere: the token is never read to describe a request.
    assertThat(payload.toString()).doesNotContain("Bearer");
  }

  @Test
  void theSameCallDescribesTheSameRequest() {
    ActionPreview a = connector.describeAction(call("http_request", REFUND), billing);
    ActionPreview b = connector.describeAction(call("http_request", REFUND), billing);

    assertThat(a.text()).isEqualTo(b.text());
    assertThat(a.payload()).isEqualTo(b.payload());
  }

  @Test
  void retriesSayWhetherTheMethodIsSafeToRepeat() {
    assertThat(retries("{\"method\":\"POST\",\"path\":\"/refunds\",\"json\":{}}"))
        .isEqualTo("Not safe to retry: the gateway sends it once");
    assertThat(retries("{\"method\":\"PUT\",\"path\":\"/refunds/re_1\",\"json\":{}}")).isEqualTo("Idempotent method");
    assertThat(retries("{\"method\":\"DELETE\",\"path\":\"/refunds/re_1\"}")).isEqualTo("Idempotent method");
  }

  private String retries(String args) {
    return connector.describeAction(call("http_request", args), billing).impact().stream()
        .filter(f -> f.label().equals("Retries")).findFirst().orElseThrow().value();
  }

  @Test
  void aKeyInTheQueryIsMaskedAndWarnedAbout() {
    ConnectionConfig query = cfg("billing-api", Env.DEV, """
        {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"apiKeyQuery","queryName":"api_key"}}""");
    ActionPreview p = connector.describeAction(call("http_request", "{\"method\":\"GET\",\"path\":\"/invoices\","
        + "\"query\":{\"limit\":10,\"status\":[\"open\",\"paid\"]}}"), query);

    assertThat(p.text()).isEqualTo("GET https://billing.stage.acme.dev/v1/invoices?limit=10&status=open&status=paid"
        + "&api_key=‹masked›");
    assertThat(p.warnings()).contains("The key goes in the URL (?api_key=…): servers and proxies may log it.");
    assertThat(p.payload().path("url").asString()).doesNotContain("api_key");
    assertThat(p.payload().path("auth").asString()).isEqualTo("apiKeyQuery:api_key");
  }

  @Test
  void secretHeadersAppearOnlyForTheirMethods() {
    ConnectionConfig hooks = cfg("billing-api", Env.STAGE, """
        {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"bearer"},
         "secretHeaders":[{"name":"X-Webhook-Secret","value":{"$secret":"s"},"methods":["POST"]}],
         "defaultHeaders":{"Accept":"application/json"}}""");
    ActionPreview post = connector.describeAction(call("http_request", "{\"method\":\"POST\",\"path\":\"/hooks\","
        + "\"form\":{\"event\":\"refund.created\",\"id\":7}}"), hooks);
    assertThat(post.text()).isEqualTo("""
        POST https://billing.stage.acme.dev/v1/hooks
        Content-Type: application/x-www-form-urlencoded
        Accept: application/json
        Authorization: ‹masked›
        X-Webhook-Secret: ‹masked›

        event=refund.created&id=7""");
    assertThat(post.payload().path("secretHeaders").toString()).isEqualTo("[\"X-Webhook-Secret\"]");

    ActionPreview get = connector.describeAction(call("http_request", "{\"method\":\"GET\",\"path\":\"/hooks\"}"),
        hooks);
    assertThat(get.text()).doesNotContain("X-Webhook-Secret");
  }

  @Test
  void aLongBodyWarnsThatChannelsCantApproveIt() {
    String reason = "r".repeat(3_100);
    ActionPreview p = connector.describeAction(call("http_request", "{\"method\":\"POST\",\"path\":\"/refunds\","
        + "\"json\":{\"reason\":\"" + reason + "\"}}"), billing);
    assertThat(p.warnings()).anyMatch(w -> w.startsWith("The body is longer than 3,000 characters"));
  }

  @Test
  void prepareNeedsTheCredentialsAndShowsTheTimeout() throws Exception {
    Fixtures.Context ctx = Fixtures.Context.of(null);
    PreparedAction missing = connector.prepare(call("http_request", REFUND), billing, new Fixtures.Secrets(), ctx);
    assertThat(missing.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(missing.message()).contains("billing-api has no token set");

    PreparedAction ok = connector.prepare(call("http_request", REFUND), billing,
        Fixtures.Secrets.of(HttpSettings.TOKEN, "tok-1"), ctx);
    assertThat(ok.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(ok.preview().impact()).contains(new ActionPreview.Field("Timeout", "10 s"));
    assertThat(ok.preview().text()).doesNotContain("tok-1");
  }
}
