package com.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code cloudflare_cache_purge}: one kind of target, all in the zone, sent as approved. */
class CachePurgeTest extends CloudflareTestBase {

  @BeforeEach
  void zone() {
    cf.zones(ACCOUNT, ZONE, "example.com", ZONE2, "example.org");
    cf.ok("POST", "/zones/" + ZONE + "/purge_cache", "{\"id\":\"" + ZONE + "\"}");
  }

  @Test
  void purgesFilesOfTheZoneAsApproved() throws Exception {
    ToolCall call = call(CloudflareTools.CACHE_PURGE, "{\"zone\":\"example.com\",\"files\":["
        + "\"https://www.example.com/css/site.css\",\"https://example.com/app.js?v=2\"]}");
    PreparedAction p = prepare(call, cfg(null));
    assertThat(p.preview().text()).startsWith("POST " + cf.base() + "/zones/" + ZONE + "/purge_cache\n");
    assertThat(p.preview().summary()).isEqualTo("Purge 2 files from the cache of example.com");
    assertThat(p.preview().warnings()).isEmpty();
    assertThat(cf.changes()).isEmpty();
    approve(call, cfg(null), p);
    assertThat(FakeCloudflare.body(cf.sent("POST", "/zones/" + ZONE + "/purge_cache").getFirst()))
        .isEqualTo(json("{\"files\":[\"https://www.example.com/css/site.css\",\"https://example.com/app.js?v=2\"]}"));
  }

  @Test
  void purgingEverythingIsWarnedAbout() throws Exception {
    ToolCall call = call(CloudflareTools.CACHE_PURGE, "{\"zone\":\"example.com\",\"everything\":true}");
    PreparedAction p = prepare(call, cfg(null));
    assertThat(p.preview().warnings()).singleElement().asString().contains("every request goes to the origin");
    approve(call, cfg(null), p);
    assertThat(FakeCloudflare.body(cf.sent("POST", "/zones/" + ZONE + "/purge_cache").getFirst()))
        .isEqualTo(json("{\"purge_everything\":true}"));
  }

  @Test
  void tagsPrefixesAndHostsGoAsGiven() throws Exception {
    for (String[] c : new String[][] {
        {"\"tags\":[\"product-42\",\"listing\"]", "{\"tags\":[\"product-42\",\"listing\"]}"},
        {"\"prefixes\":[\"www.example.com/blog/\"]", "{\"prefixes\":[\"www.example.com/blog/\"]}"},
        {"\"hosts\":[\"Images.Example.com\"]", "{\"hosts\":[\"images.example.com\"]}"}}) {
      ToolCall call = call(CloudflareTools.CACHE_PURGE, "{\"zone\":\"example.com\"," + c[0] + "}");
      approve(call, cfg(null), prepare(call, cfg(null)));
      assertThat(FakeCloudflare.body(cf.sent("POST", "/zones/" + ZONE + "/purge_cache").getLast()))
          .isEqualTo(json(c[1]));
    }
  }

  @Test
  void aUrlOfAnotherZoneIsRefusedOnceAZoneGivenByIdIsKnown() throws Exception {
    PreparedAction p = prepare(call(CloudflareTools.CACHE_PURGE, "{\"zone\":\"" + ZONE + "\",\"files\":["
        + "\"https://cdn.example.org/app.js\"]}"), cfg(null));
    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(p.message()).contains("isn't in zone example.com");
    assertThat(cf.changes()).isEmpty();
  }

  @Test
  void aPurgeThatWasntApprovedNeverRuns() {
    assertThatThrownBy(() -> connector.execute(call(CloudflareTools.CACHE_PURGE, "{\"zone\":\"example.com\","
        + "\"everything\":true}"), cfg(null), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("runs only after a person approved it");
    assertThat(cf.changes()).isEmpty();
  }

  @Test
  void anApprovalOfAnotherZoneDoesntCarryOver() throws Exception {
    ToolCall approved = call(CloudflareTools.CACHE_PURGE, "{\"zone\":\"example.com\",\"everything\":true}");
    PreparedAction p = prepare(approved, cfg(null));
    ToolCall other = call(CloudflareTools.CACHE_PURGE, "{\"zone\":\"example.org\",\"everything\":true}");
    assertThatThrownBy(() -> connector.execute(other, cfg(null), secrets, TestCtx.of().approved(p.preview().payload(),
        p.preparedState())))
        .hasMessageContaining("doesn't match the request that was approved");
    assertThat(cf.changes()).isEmpty();
  }
}
