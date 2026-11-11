package ai.msrv.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_dns_change}: the exact request with the record before and after, sent only as approved, and only
 * while the record is still the one the approval showed.
 */
class DnsChangeTest extends CloudflareTestBase {

  private static final String WWW = "{\"id\":\"" + RECORD + "\",\"type\":\"A\",\"name\":\"www.example.com\","
      + "\"content\":\"192.0.2.10\",\"proxied\":true,\"ttl\":1,\"modified_on\":\"2026-11-10T08:00:00Z\"}";

  private final AtomicReference<String> current = new AtomicReference<>(WWW);

  @BeforeEach
  void zone() {
    cf.zones(ACCOUNT, ZONE, "example.com");
    cf.on("GET", "/zones/" + ZONE + "/dns_records/" + RECORD, r -> FakeCloudflare.envelope(200, current.get(), null));
  }

  @Test
  void createShowsTheExactRequestAndTheRecordsAlreadyAtThatName() throws Exception {
    cf.ok("GET", "/zones/" + ZONE + "/dns_records", "[{\"id\":\"aa\",\"type\":\"AAAA\",\"name\":\"api.example.com\","
        + "\"content\":\"2001:db8::1\",\"ttl\":300}]");
    ToolCall call = call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"create\",\"type\":\"A\","
        + "\"name\":\"api\",\"content\":\"192.0.2.20\",\"ttl\":300,\"proxied\":false,\"comment\":\"canary\"}");
    PreparedAction p = prepare(call, cfg(null));
    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    ActionPreview preview = p.preview();
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(preview.text()).startsWith("POST " + cf.base() + "/zones/" + ZONE + "/dns_records\n"
        + "Authorization: Bearer ‹masked›\nContent-Type: application/json\n\n");
    assertThat(preview.text()).contains("\"name\" : \"api.example.com\"").doesNotContain(FakeCloudflare.TOKEN);
    assertThat(preview.summary()).isEqualTo("Create A api.example.com in example.com");
    assertThat(preview.impact()).extracting(ActionPreview.Field::label).containsExactly("After",
        "Existing at this name");
    assertThat(preview.impact().get(1).value()).contains("AAAA api.example.com → 2001:db8::1");
    assertThat(preview.auditFacets()).containsEntry("cloudflare.zone", "example.com")
        .containsEntry("cloudflare.action", "dns.create").containsEntry("cloudflare.account", ACCOUNT);
    assertThat(preview.payload().path("method").asString()).isEqualTo("POST");
    assertThat(preview.payload().path("body").path("name").asString()).isEqualTo("api.example.com");
    assertThat(cf.changes()).as("nothing changed before the approval").isEmpty();

    cf.ok("POST", "/zones/" + ZONE + "/dns_records", "{\"id\":\"" + RECORD + "\",\"type\":\"A\",\"name\":"
        + "\"api.example.com\",\"content\":\"192.0.2.20\",\"ttl\":300,\"proxied\":false}");
    ToolResult r = approve(call, cfg(null), p);
    assertThat(r.isError()).isFalse();
    RecordedRequest post = cf.sent("POST", "/zones/" + ZONE + "/dns_records").getFirst();
    assertThat(FakeCloudflare.body(post)).isEqualTo(json("{\"type\":\"A\",\"name\":\"api.example.com\",\"content\":"
        + "\"192.0.2.20\",\"ttl\":300,\"proxied\":false,\"comment\":\"canary\"}"));
    assertThat(post.getHeaders().get("Content-Type")).startsWith("application/json");
    assertThat(s(r).at("/item/content").asString()).isEqualTo("192.0.2.20");
  }

  @Test
  void aCnameNextToOtherRecordsIsWarnedAbout() throws Exception {
    cf.ok("GET", "/zones/" + ZONE + "/dns_records", "[" + WWW + "]");
    PreparedAction p = prepare(call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"create\","
        + "\"type\":\"CNAME\",\"name\":\"www\",\"content\":\"lb.example.net\"}"), cfg(null));
    assertThat(p.preview().warnings()).anySatisfy(w -> assertThat(w).contains("A CNAME can't share its name"));
  }

  @Test
  void updateShowsBeforeAndAfterAndRunsOnlyIfTheRecordIsUnchanged() throws Exception {
    ToolCall call = call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"update\",\"record\":\""
        + RECORD + "\",\"content\":\"192.0.2.11\",\"proxied\":false}");
    PreparedAction p = prepare(call, cfg(null));
    assertThat(p.preview().text()).startsWith("PATCH " + cf.base() + "/zones/" + ZONE + "/dns_records/" + RECORD);
    assertThat(p.preview().impact().get(0).value()).isEqualTo("A www.example.com → 192.0.2.10 · proxied · TTL auto");
    assertThat(p.preview().impact().get(1).value()).isEqualTo("A www.example.com → 192.0.2.11 · DNS only · TTL auto");
    assertThat(p.preview().warnings()).anySatisfy(w -> assertThat(w).contains("Turns off Cloudflare's proxy"));
    assertThat(p.preparedState().path("modified_on").asString()).isEqualTo("2026-11-10T08:00:00Z");

    // Someone changed the record after the approval: nothing is sent.
    current.set(WWW.replace("2026-11-10T08:00:00Z", "2026-11-11T09:30:00Z"));
    assertThatThrownBy(() -> approve(call, cfg(null), p))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("changed after the approval");
    assertThat(cf.changes()).isEmpty();

    current.set(WWW);
    cf.ok("PATCH", "/zones/" + ZONE + "/dns_records/" + RECORD, WWW.replace("192.0.2.10", "192.0.2.11"));
    approve(call, cfg(null), p);
    RecordedRequest patch = cf.sent("PATCH", "/zones/" + ZONE + "/dns_records/" + RECORD).getFirst();
    assertThat(FakeCloudflare.body(patch)).isEqualTo(json("{\"content\":\"192.0.2.11\",\"proxied\":false}"));
  }

  @Test
  void deleteSendsNoBody() throws Exception {
    ToolCall call = call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"delete\",\"record\":\""
        + RECORD + "\"}");
    PreparedAction p = prepare(call, cfg(null));
    assertThat(p.preview().text()).isEqualTo("DELETE " + cf.base() + "/zones/" + ZONE + "/dns_records/" + RECORD
        + "\nAuthorization: Bearer ‹masked›\n");
    assertThat(p.preview().impact()).extracting(ActionPreview.Field::value).contains("deleted");
    cf.ok("DELETE", "/zones/" + ZONE + "/dns_records/" + RECORD, "{\"id\":\"" + RECORD + "\"}");
    approve(call, cfg(null), p);
    assertThat(cf.sent("DELETE", "/zones/" + ZONE + "/dns_records/" + RECORD)).hasSize(1);
  }

  @Test
  void aChangeNeverRunsWithoutApprovalOrWithAnotherRequest() throws Exception {
    ToolCall call = call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"update\",\"record\":\""
        + RECORD + "\",\"ttl\":600}");
    assertThatThrownBy(() -> connector.execute(call, cfg(null), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("runs only after a person approved it");
    PreparedAction p = prepare(call, cfg(null));
    ObjectNode tampered = (ObjectNode) p.preview().payload().deepCopy();
    ((ObjectNode) tampered.get("body")).put("ttl", 60);
    assertThatThrownBy(() -> connector.execute(call, cfg(null), secrets, TestCtx.of().approved(tampered,
        p.preparedState())))
        .hasMessageContaining("doesn't match the request that was approved");
    ToolCall other = call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"update\",\"record\":\""
        + RECORD + "\",\"ttl\":60}");
    assertThatThrownBy(() -> connector.execute(other, cfg(null), secrets, TestCtx.of().approved(p.preview()
        .payload(), p.preparedState())))
        .hasMessageContaining("doesn't match the request that was approved");
    assertThatThrownBy(() -> connector.execute(call, cfg(null), secrets, TestCtx.of().approved(p.preview()
        .payload(), null)))
        .hasMessageContaining("no record of what this change was approved against");
    assertThat(cf.changes()).isEmpty();
  }

  @Test
  void anUpdateThatGivesContentToADataRecordIsRejectedAtPrepare() throws Exception {
    current.set("{\"id\":\"" + RECORD + "\",\"type\":\"CAA\",\"name\":\"example.com\",\"data\":{\"flags\":0,\"tag\":"
        + "\"issue\",\"value\":\"letsencrypt.org\"},\"modified_on\":\"2026-11-10T08:00:00Z\"}");
    PreparedAction p = prepare(call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"update\","
        + "\"record\":\"" + RECORD + "\",\"content\":\"0 issue pki.goog\"}"), cfg(null));
    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(p.message()).contains("A CAA record takes data, not content.");
  }

  @Test
  void aDataRecordSendsItsFields() throws Exception {
    cf.ok("GET", "/zones/" + ZONE + "/dns_records", "[]");
    PreparedAction p = prepare(call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"create\","
        + "\"type\":\"SRV\",\"name\":\"_sip._tcp\",\"data\":{\"priority\":10,\"weight\":5,\"port\":5060,"
        + "\"target\":\"sip.example.com\"}}"), cfg(null));
    JsonNode body = p.preview().payload().path("body");
    assertThat(body.path("name").asString()).isEqualTo("_sip._tcp.example.com");
    assertThat(body.path("data").path("port").asInt()).isEqualTo(5060);
    assertThat(p.preview().impact().get(0).value()).contains("SRV _sip._tcp.example.com");
  }

  @Test
  void aRecordThatIsGoneRejectsThePreparation() {
    cf.fail("GET", "/zones/" + ZONE + "/dns_records/" + RECORD, 404, 81044, "Record does not exist.");
    assertThatThrownBy(() -> prepare(call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":"
        + "\"delete\",\"record\":\"" + RECORD + "\"}"), cfg(null)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("not found (404): 81044 Record does not exist.");
  }

  @Test
  void aLostAnswerOfAChangeIsAnUnknownOutcome() throws Exception {
    ToolCall call = call(CloudflareTools.DNS_CHANGE, "{\"zone\":\"example.com\",\"action\":\"delete\",\"record\":\""
        + RECORD + "\"}");
    PreparedAction p = prepare(call, cfg(null));
    cf.on("DELETE", "/zones/" + ZONE + "/dns_records/" + RECORD, r -> new mockwebserver3.MockResponse.Builder()
        .onResponseStart(mockwebserver3.SocketEffect.ShutdownConnection.INSTANCE).build());
    assertThatThrownBy(() -> approve(call, cfg(null), p))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN))
        .hasMessageContaining("may have been applied");
  }
}
