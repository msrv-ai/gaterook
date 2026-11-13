package com.gaterook.connectors.spanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** executeStreamingSql's answer, as Google's REST API and a gRPC gateway send it. */
class ResultStreamTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String META = "{\"metadata\":{\"rowType\":{\"fields\":[{\"name\":\"a\",\"type\":{\"code\":"
      + "\"STRING\"}},{\"name\":\"b\",\"type\":{\"code\":\"INT64\"}}]}},";

  private static List<String> rows(String body, int stopAfter) throws Exception {
    List<String> out = new ArrayList<>();
    ResultStream stream = new ResultStream(values -> {
      out.add(values.toString());
      return out.size() < stopAfter;
    });
    stream.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    return out;
  }

  @Test
  void readsRowsAcrossMessagesInBothForms() throws Exception {
    String array = "[" + META + "\"values\":[\"x\",\"1\",\"y\"]},\n{\"values\":[\"2\"]}]";
    assertThat(rows(array, 10)).containsExactly("[\"x\", \"1\"]", "[\"y\", \"2\"]");
    String gateway = "{\"result\":" + META + "\"values\":[\"x\",\"1\"]}}\n{\"result\":{\"values\":[\"y\",\"2\"]}}\n";
    assertThat(rows(gateway, 10)).hasSize(2);
  }

  @Test
  void stopsWhenTheRowsAreEnough() throws Exception {
    String array = "[" + META + "\"values\":[\"x\",\"1\",\"y\",\"2\",\"z\",\"3\"]}]";
    assertThat(rows(array, 1)).containsExactly("[\"x\", \"1\"]");
  }

  @Test
  void mergesSplitValuesTheWaySpannerDescribes() throws Exception {
    assertThat(ResultStream.merge(JSON.readTree("\"ab\""), JSON.readTree("\"cd\"")).asString()).isEqualTo("abcd");
    assertThat(ResultStream.merge(JSON.readTree("[\"a\",\"b\"]"), JSON.readTree("[\"c\",\"d\"]")).toString())
        .isEqualTo("[\"a\",\"bc\",\"d\"]");
    assertThat(ResultStream.merge(JSON.readTree("[\"a\",[\"b\",\"c\"]]"), JSON.readTree("[[\"d\"],\"e\"]"))
        .toString()).isEqualTo("[\"a\",[\"b\",\"cd\"],\"e\"]");
    assertThat(ResultStream.merge(JSON.readTree("[1,2]"), JSON.readTree("[3,4]")).toString()).isEqualTo("[1,2,3,4]");
    assertThatThrownBy(() -> ResultStream.merge(JSON.readTree("1"), JSON.readTree("2"))).isInstanceOf(
        IOException.class);
  }

  @Test
  void anErrorInsideTheStreamCarriesItsStatus() {
    String gateway = "{\"result\":" + META + "\"values\":[\"x\",\"1\"]}}\n{\"error\":{\"code\":9,\"message\":"
        + "\"Read timestamp is too old\"}}\n";
    assertThatThrownBy(() -> rows(gateway, 10)).isInstanceOfSatisfying(ResultStream.StreamError.class, e -> {
      assertThat(e.status()).isEqualTo(400);
      assertThat(e.error().path("message").asString()).isEqualTo("Read timestamp is too old");
    });
    String google = "[" + META + "\"values\":[]},{\"error\":{\"code\":403,\"message\":\"denied\",\"status\":"
        + "\"PERMISSION_DENIED\"}}]";
    assertThatThrownBy(() -> rows(google, 10)).isInstanceOfSatisfying(ResultStream.StreamError.class,
        e -> assertThat(e.status()).isEqualTo(403));
  }

  @Test
  void aStreamThatEndsInsideARowIsBroken() {
    assertThatThrownBy(() -> rows("[" + META + "\"values\":[\"x\"]}]", 10)).isInstanceOf(IOException.class)
        .hasMessageContaining("inside a row");
    assertThatThrownBy(() -> rows("", 10)).isInstanceOf(IOException.class);
  }
}
