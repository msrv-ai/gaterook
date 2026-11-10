package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/**
 * The target of a REST request is one text that is classified, shown, stored, audited, signed and sent. The HTTP
 * client writes any character outside printable ASCII as {@code ?} (or one raw byte), so a target that holds such a
 * character would run as a different request than the one a person approved.
 */
class OpenSearchRequestTest {
  private static final String BASE = "http://127.0.0.1:9200";
  private static final ConnectionConfig CONNECTION = Configs.of("search-full", Env.DEV,
      "{\"backend\":\"opensearch\",\"url\":\"" + BASE + "\",\"auth\":{\"mode\":\"none\"}}");
  private static final Settings.OpenSearch FULL = (Settings.OpenSearch) Settings.parse(CONNECTION);
  private static final Limits LIMITS = TestCtx.dev();

  static Stream<Arguments> charactersBeyondAscii() {
    return Stream.of(
        Arguments.of("a hyphen look-alike", "/safe\u2010index/_doc/1", "/safe%E2%80%90index/_doc/1"),
        Arguments.of("a question mark look-alike", "/idx\uFF1Fx/_doc/1", "/idx%EF%BC%9Fx/_doc/1"),
        Arguments.of("a slash look-alike", "/idx\uFF0Fx/_doc/1", "/idx%EF%BC%8Fx/_doc/1"),
        Arguments.of("a percent look-alike", "/idx\uFF05x/_doc/1", "/idx%EF%BC%85x/_doc/1"),
        Arguments.of("a Latin-1 letter", "/people/_doc/m\u00FCller", "/people/_doc/m%C3%BCller"),
        Arguments.of("a CJK ideograph", "/people/_doc/\u65E5\u672C", "/people/_doc/%E6%97%A5%E6%9C%AC"),
        Arguments.of("a supplementary code point", "/emoji/_doc/\uD83D\uDE00", "/emoji/_doc/%F0%9F%98%80"),
        Arguments.of("a zero-width space", "/idx/_doc/a\u200Bb", "/idx/_doc/a%E2%80%8Bb"),
        Arguments.of("a bidi override", "/idx/_doc/a\u202Eb", "/idx/_doc/a%E2%80%AEb"),
        Arguments.of("the typed query string", "/idx/_search?q=caf\u00E9", "/idx/_search?q=caf%C3%A9"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("charactersBeyondAscii")
  void aCharacterBeyondAsciiIsEscapedOnceAndShownStoredAndSentAsOneText(String what, String typed, String escaped)
      throws Exception {
    ToolCall call = request("opensearch_request", "DELETE", typed);

    OpenSearchRequest planned = OpenSearchRequest.plan(call, FULL, LIMITS);

    assertThat(planned.path()).isEqualTo(escaped).matches("[\\x21-\\x7E]+");
    ActionPreview preview = planned.preview(FULL, CONNECTION);
    assertThat(preview.text()).startsWith("DELETE " + BASE + escaped + "\n");
    assertThat(preview.summary()).isEqualTo("DELETE " + escaped);
    assertThat(preview.payload().path("path").asString()).isEqualTo(escaped);
    assertThat(OpenSearchRequest.approved(preview.payload(), call, FULL, LIMITS)).isEqualTo(planned);
    URI uri = URI.create(BASE + planned.path());
    assertThat(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).isEqualTo(escaped);
  }

  @Test
  void anEscapeTheAgentTypedAndTheCharacterItStandsForAreOneTarget() throws Exception {
    OpenSearchRequest typed = OpenSearchRequest.plan(request("opensearch_request", "GET", "/people/_doc/m%C3%BCller"),
        FULL, LIMITS);
    OpenSearchRequest raw = OpenSearchRequest.plan(request("opensearch_request", "GET", "/people/_doc/m\u00FCller"),
        FULL, LIMITS);

    assertThat(typed).isEqualTo(raw);
    assertThat(raw.path()).isEqualTo("/people/_doc/m%C3%BCller");
  }

  @Test
  void queryParametersAreEscapedOnceInAsciiWhateverTheirText() throws Exception {
    ObjectNode args = TestCtx.JSON.createObjectNode().put("method", "GET").put("path", "/idx/_search");
    ObjectNode query = args.putObject("query");
    query.put("q", "caf\u00E9/\u65E5\u672C");
    query.putArray("k\u00E9y").add("a b").add(1).add(true);

    OpenSearchRequest planned = OpenSearchRequest.plan(call("opensearch_request", args), FULL, LIMITS);

    assertThat(planned.path()).isEqualTo("/idx/_search?q=caf%C3%A9%2F%E6%97%A5%E6%9C%AC"
        + "&k%C3%A9y=a%20b&k%C3%A9y=1&k%C3%A9y=true");
  }

  @ParameterizedTest
  @ValueSource(strings = {"\uD800", "a\uDC00b", "\uDBFF"})
  void anUnpairedSurrogateIsNotTextAnyRequestPartCanCarry(String text) {
    assertThatThrownBy(() -> OpenSearchRequest.plan(request("opensearch_request", "GET", "/idx/_doc/" + text),
        FULL, LIMITS)).isInstanceOf(ConnectorException.class);
    ObjectNode value = TestCtx.JSON.createObjectNode().put("method", "GET").put("path", "/idx/_search");
    value.putObject("query").put("q", text);
    assertThatThrownBy(() -> OpenSearchRequest.plan(call("opensearch_request", value), FULL, LIMITS))
        .isInstanceOf(ConnectorException.class);
    ObjectNode name = TestCtx.JSON.createObjectNode().put("method", "GET").put("path", "/idx/_search");
    name.putObject("query").put(text + "key", "x");
    assertThatThrownBy(() -> OpenSearchRequest.plan(call("opensearch_request", name), FULL, LIMITS))
        .isInstanceOf(ConnectorException.class);
    ObjectNode string = TestCtx.JSON.createObjectNode().put("method", "POST").put("path", "/idx/_search")
        .put("body", "{\"query\":{\"term\":{\"user\":\"" + text + "\"}}}");
    assertThatThrownBy(() -> OpenSearchRequest.plan(call("opensearch_request", string), FULL, LIMITS))
        .isInstanceOf(ConnectorException.class);
    ObjectNode object = TestCtx.JSON.createObjectNode().put("method", "POST").put("path", "/idx/_search");
    object.putObject("body").putObject("query").putObject("term").put("user", text);
    assertThatThrownBy(() -> OpenSearchRequest.plan(call("opensearch_request", object), FULL, LIMITS))
        .isInstanceOf(ConnectorException.class);
  }

  @Test
  void aTargetThatIsTooLongOnceEscapedIsRefused() {
    String tooLong = "path, with its query and escaped, is longer than 32768 characters.";
    // 6,000 characters of text, but 54,000 once each is written as three UTF-8 escapes.
    assertThatThrownBy(() -> OpenSearchRequest.plan(request("opensearch_request", "GET",
        "/" + "\u2010".repeat(6_000)), FULL, LIMITS)).isInstanceOf(ConnectorException.class).hasMessage(tooLong);
    // Too long as typed: the same words hold, since escaping and a query only add to it.
    assertThatThrownBy(() -> OpenSearchRequest.plan(request("opensearch_request", "GET",
        "/" + "a".repeat(40_000)), FULL, LIMITS)).isInstanceOf(ConnectorException.class).hasMessage(tooLong);
  }

  @Test
  void aPayloadStoredWithTheRawCharacterIsSentEscaped() throws Exception {
    ToolCall call = request("opensearch_request", "DELETE", "/placeholder");
    ObjectNode stored = OpenSearchRequest.plan(call, FULL, LIMITS).payload(FULL);
    stored.put("path", "/safe\u2010index");

    OpenSearchRequest approved = OpenSearchRequest.approved(stored, call, FULL, LIMITS);

    assertThat(approved.path()).isEqualTo("/safe%E2%80%90index");
  }

  @Test
  void escapedNonAsciiTextInAKnownReadStaysARead() throws Exception {
    for (String path : new String[] {"/people/_doc/m\u00FCller", "/people/_doc/m%C3%BCller",
        "/people/_doc/\u65E5\u672C", "/people/_doc/\uD83D\uDE00", "/idx\u2010x/_doc/1"}) {
      OpenSearchRequest planned = OpenSearchRequest.plan(request("opensearch_query", "GET", path), FULL, LIMITS);
      assertThat(planned.path()).matches("[\\x21-\\x7E]+");
    }
    OpenSearchRequest search = OpenSearchRequest.plan(request("opensearch_query", "POST", "/idx\u2010x/_search"),
        FULL, LIMITS);
    assertThat(search.path()).isEqualTo("/idx%E2%80%90x/_search");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/people/_doc/m%2Fller", "/people/_doc/m%C3", "/people/_doc/%C0%AF",
      "/people/_doc/%ED%A0%80", "/people/_doc/%C2%85", "/people/_doc/%C2%A0", "/people/_doc/%E2%80%A8",
      "/people/_doc/m%C3%BCller%2e", "/people/_doc/%41", "/people/_doc/%C3A9"})
  void anyOtherEscapeStillLeavesTheKnownReadList(String path) {
    assertThatThrownBy(() -> OpenSearchRequest.plan(request("opensearch_query", "GET", path), FULL, LIMITS))
        .isInstanceOfSatisfying(ConnectorException.class,
            error -> assertThat(error.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/idx/_doc/a b", "/idx/_doc/a\u0000b", "/idx/_doc/a\u0085b", "/idx/_doc/a\u00A0b",
      "/idx/_doc/a\u2028b", "/idx/_doc/a\"b", "/idx/_doc/a{b}", "/idx/_doc/a\\b", "/idx/_doc/a#b"})
  void textThatWasRefusedStaysRefused(String path) {
    assertThatThrownBy(() -> OpenSearchRequest.plan(request("opensearch_request", "GET", path), FULL, LIMITS))
        .isInstanceOf(ConnectorException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {".", "..", "\u2010", "m\u00FCller", "a/b", "a b"})
  void aDiscoveryIndexSelectorNeverNeedsMoreThanIndexSyntax(String index) {
    ObjectNode args = TestCtx.JSON.createObjectNode().put("index", index);
    assertThatThrownBy(() -> OpenSearchRequest.plan(call("opensearch_indices", args), FULL, LIMITS))
        .isInstanceOf(ConnectorException.class);
  }

  @Test
  void aDiscoveryTargetIsAsciiAndEscapedOnce() throws Exception {
    ObjectNode args = TestCtx.JSON.createObjectNode().put("index", "logs-*,other?:remote");

    OpenSearchRequest planned = OpenSearchRequest.plan(call("opensearch_indices", args), FULL, LIMITS);

    assertThat(planned.path()).isEqualTo("/_resolve/index/logs-*%2Cother%3F%3Aremote?expand_wildcards=open");
  }

  private static ToolCall request(String tool, String method, String path) {
    return call(tool, TestCtx.JSON.createObjectNode().put("method", method).put("path", path));
  }

  private static ToolCall call(String tool, ObjectNode arguments) {
    return new ToolCall("tc_logs_test", tool, "app-logs", arguments, null);
  }
}
