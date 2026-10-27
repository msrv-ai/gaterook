package ai.msrv.gaterook.core.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StricterMergeTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode j(String text) {
    return JSON.readTree(text);
  }

  private static String merge(String op, String current, String candidate) {
    JsonNode out = StricterMerge.merge(op, current == null ? null : j(current), candidate == null ? null
        : j(candidate));
    return out == null ? null : out.toString();
  }

  @Test
  void numbersShrinkOrGrowByTheirOp() {
    assertThat(merge("min", "100", "20")).isEqualTo("20");
    assertThat(merge("min", "100", "2000")).isEqualTo("100");
    assertThat(merge("min", "100", "99.5")).isEqualTo("99.5");
    assertThat(merge("max", "3", "5")).isEqualTo("5");
    assertThat(merge("max", "3", "1")).isEqualTo("3");
    assertThat(merge("min", "100", "\"5\"")).as("a string is no number").isEqualTo("100");
  }

  @Test
  void booleansProtectOrPermitByTheirOp() {
    assertThat(merge("or", "true", "false")).as("a protection stays on").isEqualTo("true");
    assertThat(merge("or", "false", "true")).isEqualTo("true");
    assertThat(merge("and", "true", "false")).as("a permission can be taken away").isEqualTo("false");
    assertThat(merge("and", "false", "true")).as("but never given").isEqualTo("false");
  }

  @Test
  void listsIntersectOrUnite() {
    assertThat(merge("intersect", "[\"a\",\"b\"]", "[\"b\",\"c\"]")).isEqualTo("[\"b\"]");
    assertThat(merge("intersect", "[]", "[\"b\"]")).as("empty = everything allowed").isEqualTo("[\"b\"]");
    assertThat(merge("intersect", "[\"a\"]", "[]")).isEqualTo("[\"a\"]");
    assertThat(merge("intersect", "[\"a\"]", "[\"z\"]")).as("disjoint: nothing allowed, not everything")
        .isEqualTo("[\"\\u0000none\"]");
    assertThat(merge("union", "[\"a\",\"b\"]", "[\"b\",\"c\"]")).isEqualTo("[\"a\",\"b\",\"c\"]");
  }

  @Test
  void aKeyWithoutStricterCannotBeNarrowed() {
    assertThat(merge(null, "true", "false")).isEqualTo("true");
    assertThat(merge("const", "1", "0")).isEqualTo("1");
    assertThat(merge(null, null, "false")).as("nor introduced").isNull();
  }

  @Test
  void aMissingCandidateKeepsTheCurrentValue() {
    assertThat(merge("min", "100", null)).isEqualTo("100");
    assertThat(merge("min", "100", "null")).isEqualTo("100");
    assertThat(merge("min", null, "5")).as("a restriction may be added where there was none").isEqualTo("5");
    assertThat(merge("and", null, "true")).as("a permission may not").isNull();
  }

  @Test
  void atLeastAsStrictComparesTheMergedValue() {
    assertThat(StricterMerge.atLeastAsStrict("min", j("100"), j("50"))).isTrue();
    assertThat(StricterMerge.atLeastAsStrict("min", j("100"), j("500"))).isFalse();
    assertThat(StricterMerge.atLeastAsStrict("or", j("true"), j("false"))).isFalse();
    assertThat(StricterMerge.atLeastAsStrict("intersect", j("[\"a\",\"b\"]"), j("[\"a\"]"))).isTrue();
  }
}
