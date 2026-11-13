package com.gaterook.connectors.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** What the agent gets of an answer whose JSON holds numbers that are written with a runaway exponent. */
class ResponseReaderTest {

  private static ToolResult read(String body) {
    ResponseReader.Answer answer = new ResponseReader.Answer(200, "OK",
        Map.of("Content-Type", List.of("application/json")), body.getBytes(StandardCharsets.UTF_8), false, 12, 0, null,
        null);
    return ResponseReader.read("GET", "https://api.test/v1/measures", answer, 512 * 1024);
  }

  @Test
  void ordinaryNumbersAreParsedAsWritten() {
    JsonNode s = read("{\"total\":129.00,\"items\":[1,2.5,1e3],\"tiny\":4.9e-324,\"big\":1.7976931348623157e308}")
        .structuredContent();

    assertThat(s.path("json").path("total").asString()).isEqualTo("129.00");
    assertThat(s.path("json").path("items").get(1).isNumber()).isTrue();
    assertThat(s.path("json").path("items").get(2).isNumber()).isTrue();
    assertThat(s.path("json").path("tiny").isNumber()).isTrue();
    assertThat(s.path("json").path("big").isNumber()).isTrue();
    assertThat(s.path("text").isNull()).isTrue();
  }

  @Test
  void aNumberWithARunawayExponentStaysTextWhileTheRestIsParsed() {
    ToolResult r = read("{\"a\":1e-1000000000,\"b\":[1e1000000000,129.00,{\"c\":-2.5e2147483647}],\"d\":true}");
    JsonNode s = r.structuredContent();

    assertThat(s.path("json").path("a").isString()).isTrue();
    assertThat(s.path("json").path("a").asString()).isEqualTo("1E-1000000000");
    assertThat(s.path("json").path("b").get(0).asString()).isEqualTo("1E+1000000000");
    assertThat(s.path("json").path("b").get(1).isNumber()).isTrue();
    assertThat(s.path("json").path("b").get(2).path("c").asString()).isEqualTo("-2.5E+2147483647");
    assertThat(s.path("json").path("d").asBoolean()).isTrue();
    assertThat(s.path("ok").asBoolean()).isTrue();
    assertThat(r.isError()).isFalse();
  }

  @Test
  void anExponentBeyondWhatADecimalHoldsIsNotAnErrorOfTheCall() {
    for (String body : List.of("{\"a\":1e-2147483648}", "[1e2147483648]", "{\"a\":[0.5,-1E99999999999]}")) {
      ToolResult r = read(body);
      JsonNode s = r.structuredContent();

      // The call went through and the answer is data: the body is shown as text, with the reason it isn't JSON here.
      assertThat(r.isError()).as(body).isFalse();
      assertThat(s.path("status").asInt()).as(body).isEqualTo(200);
      assertThat(s.path("json").isNull()).as(body).isTrue();
      assertThat(s.path("text").asString()).as(body).isEqualTo(body);
      assertThat(r.content().getFirst().text()).as(body).contains("exponent too large to read").contains(body);
    }
  }

  @Test
  void aDocumentThatIsNotJsonKeepsItsOwnNote() {
    ToolResult r = read("{\"a\":");

    assertThat(r.structuredContent().path("json").isNull()).isTrue();
    assertThat(r.content().getFirst().text()).contains("The body isn't valid JSON.");
  }
}
