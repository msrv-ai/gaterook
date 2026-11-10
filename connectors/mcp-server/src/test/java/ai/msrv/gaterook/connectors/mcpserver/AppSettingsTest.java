package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an endpoint passes on of an app resource's {@code _meta.ui} (MCP Apps, ARCHITECTURE.md §5.4): the lists of its
 * content security policy with origins of the web only, and its border preference. What the server adds besides,
 * device permissions and a dedicated origin included, reaches no AI app.
 */
class AppSettingsTest {

  @Test
  void anAppKeepsItsPolicyListsAndBorderAndNothingElse() {
    ObjectNode ui = Fixtures.object("""
        {"csp":{"connectDomains":["https://api.charts.example","wss://live.charts.example:8443"],
                "resourceDomains":["https://*.cdn.example/"],"frameDomains":[],"baseUriDomains":["http://localhost:3000"],
                "workerDomains":["https://workers.example"]},
         "permissions":{"camera":{},"microphone":{},"geolocation":{},"clipboardWrite":{}},
         "domain":"a904794854a047f6.example","prefersBorder":false,"extra":{"anything":1}}""");

    assertThat(Federation.appSettings(ui).toString()).isEqualTo("{\"csp\":{\"connectDomains\":"
        + "[\"https://api.charts.example\",\"wss://live.charts.example:8443\"],\"resourceDomains\":"
        + "[\"https://*.cdn.example/\"],\"frameDomains\":[],\"baseUriDomains\":[\"http://localhost:3000\"]},"
        + "\"prefersBorder\":false}");
  }

  /** Entries a policy could read as something other than one origin of the web, and entries of another kind. */
  @ParameterizedTest
  @ValueSource(strings = {"*", "https://*", "https:", "'self'", "'unsafe-eval'", "data:", "blob:",
      "javascript:void(0)", "ftp://files.example", "https://charts.example/app.js", "https://user@charts.example",
      "https://charts.example other.example", "https://charts.example,https://other.example", " https://charts.example",
      "https://charts..example", "https://-charts.example", "https://charts.example:", "https://charts.example:123456"})
  void anEntryThatIsNotOneOriginOfTheWebIsLeftOut(String entry) {
    ObjectNode ui = Fixtures.object("{\"csp\":{\"connectDomains\":[]}}");
    ((ArrayNode) ui.at("/csp/connectDomains")).add(entry).add("https://charts.example");

    assertThat(Federation.appSettings(ui).at("/csp/connectDomains").toString())
        .isEqualTo("[\"https://charts.example\"]");
  }

  @Test
  void valuesOfAnotherKindAreLeftOutAndTooManyOriginsAreCut() {
    ObjectNode ui = Fixtures.object("""
        {"csp":{"connectDomains":"https://charts.example","resourceDomains":[7,true,null,{"x":1},["https://a.example"]]},
         "prefersBorder":"yes"}""");
    ArrayNode many = ((ObjectNode) ui.get("csp")).putArray("frameDomains");
    for (int i = 0; i < Federation.MAX_ORIGINS + 10; i++) {
      many.add("https://frame" + i + ".example");
    }
    ObjectNode kept = Federation.appSettings(ui);

    assertThat(kept.at("/csp").has("connectDomains")).as("not a list").isFalse();
    assertThat(kept.at("/csp/resourceDomains").isEmpty()).isTrue();
    assertThat(kept.at("/csp/frameDomains")).hasSize(Federation.MAX_ORIGINS);
    assertThat(kept.at("/csp/frameDomains/0").asString()).isEqualTo("https://frame0.example");
    assertThat(kept.has("prefersBorder")).isFalse();
    String labels = String.join(".", java.util.Collections.nCopies(5, "a".repeat(60)));
    assertThat(Federation.origin("https://" + labels.substring(0, 240) + ".example")).isTrue();
    assertThat(Federation.origin("https://" + labels + ".example")).as("over the length an origin may have")
        .isFalse();
  }

  @Test
  void settingsWithNothingLeftAreNone() {
    assertThat(Federation.appSettings(Fixtures.object("{\"permissions\":{\"camera\":{}},\"domain\":\"x.example\"}")))
        .isNull();
    assertThat(Federation.appSettings(Fixtures.object("{}"))).isNull();
    assertThat(Federation.appSettings(Fixtures.object("{\"ui\":\"text\"}").get("ui"))).isNull();
  }

  @Test
  void aReadAppCarriesOnlyTheKeptSettingsUnderItsConnectionsUri() {
    ObjectNode read = Fixtures.object("""
        {"contents":[{"uri":"ui://charts/view.html","mimeType":"text/html;profile=mcp-app","text":"<p>chart</p>",
          "_meta":{"ui":{"csp":{"connectDomains":["https://charts.example","*"]},"permissions":{"camera":{}},
                         "domain":"charts.example","prefersBorder":true},"other/key":"value"}},
         {"uri":"notes://plain","mimeType":"text/plain","text":"no app","_meta":{"ui":{"domain":"x.example"}}}]}""");

    ToolResult result = Federation.contents("charts-mcp", read);

    ToolResult.Content app = result.content().get(0);
    assertThat(app.uri()).isEqualTo("ui://gaterook/connections/charts-mcp/charts/view.html");
    assertThat(app.meta().toString())
        .isEqualTo("{\"ui\":{\"csp\":{\"connectDomains\":[\"https://charts.example\"]},\"prefersBorder\":true}}");
    assertThat(result.content().get(1).meta()).as("nothing of its ui settings is kept").isNull();
  }
}
