package ai.msrv.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The text of a number never depends on its exponent: numbers as ordinary as a double read as they always did, and the
 * rest, whatever their exponent, take as many characters as their digits.
 */
class JsonNumbersTest {

  private static final JsonMapper DECIMALS = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "0                                     | 0",
      "0.000                                 | 0",
      "0E+2147483647                         | 0",
      "0E-2147483647                         | 0",
      "5                                     | 5",
      "5.0                                   | 5",
      "129.00                                | 129",
      "1E+3                                  | 1000",
      "-1.5                                  | -1.5",
      "0.1                                   | 0.1",
      "-0.000001                             | -0.000001",
      "1E-7                                  | 0.0000001",
      "100.250                               | 100.25",
      "123456789012345678901234567890.5      | 123456789012345678901234567890.5",
      "0.000000000000000000000000000001234   | 0.000000000000000000000000000001234",
  })
  void ordinaryNumbersKeepTheirPlainText(String number, String expected) {
    BigDecimal value = new BigDecimal(number);

    assertThat(JsonNumbers.expandable(value)).isTrue();
    assertThat(JsonNumbers.plain(value)).isEqualTo(expected);
    assertThat(JsonNumbers.checkable(value)).isEqualTo(expected);
  }

  @Test
  void everyDoubleIsExpandableAndWrittenInFull() {
    for (double d : new double[] {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE,
        Double.MIN_NORMAL, Math.PI, 1e22, 1e-22, 123456789.123456789}) {
      BigDecimal value = BigDecimal.valueOf(d);

      assertThat(JsonNumbers.expandable(value)).as("%s", d).isTrue();
      assertThat(JsonNumbers.plain(value)).as("%s", d).isEqualTo(value.stripTrailingZeros().toPlainString())
          .doesNotContain("E");
    }
    assertThat(JsonNumbers.plain(BigDecimal.valueOf(Double.MIN_VALUE))).isEqualTo("0." + "0".repeat(323) + "49");
    assertThat(JsonNumbers.plain(BigDecimal.valueOf(Double.MAX_VALUE)))
        .isEqualTo("17976931348623157" + "0".repeat(292));
  }

  @Test
  void theBoundIsTheZerosTheExponentAddsToTheDigits() {
    assertThat(JsonNumbers.plain(new BigDecimal("1E+400"))).isEqualTo("1" + "0".repeat(400));
    assertThat(JsonNumbers.plain(new BigDecimal("1E+401"))).isEqualTo("1E+401");
    // 1.5E+401 is 15 followed by 400 zeros: the digits count, only the zeros the exponent adds are bounded.
    assertThat(JsonNumbers.plain(new BigDecimal("1.5E+401"))).isEqualTo("15" + "0".repeat(400));
    assertThat(JsonNumbers.plain(new BigDecimal("1.5E+402"))).isEqualTo("1.5E+402");
    assertThat(JsonNumbers.plain(new BigDecimal("1E-401"))).isEqualTo("0." + "0".repeat(400) + "1");
    assertThat(JsonNumbers.plain(new BigDecimal("1E-402"))).isEqualTo("1E-402");
    assertThat(JsonNumbers.plain(new BigDecimal("-1.25E-402"))).isEqualTo("-1.25E-402");
    // The digits are not padding: a long decimal with a small exponent is written out whatever its length.
    String digits = "7".repeat(900);
    assertThat(JsonNumbers.plain(new BigDecimal("0." + digits))).isEqualTo("0." + digits);
    assertThat(JsonNumbers.plain(new BigDecimal(digits + ".5"))).isEqualTo(digits + ".5");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "1E+1000000000                         | 1E+1000000000",
      "1E-1000000000                         | 1E-1000000000",
      "-1.5E+2147483647                      | -1.5E+2147483647",
      "1E-2147483647                         | 1E-2147483647",
      "10E-2147483647                        | 1E-2147483646",
      "100E+2147483647                       | 1E+2147483649",
      "1.00E+2147483647                      | 1E+2147483647",
      "123456789E+500                        | 1.23456789E+508",
      "-0.5E-1000                            | -5E-1001",
  })
  void numbersWithHugeExponentsAreShortAndAlwaysTheSameText(String number, String expected) {
    BigDecimal value = new BigDecimal(number);

    assertThat(JsonNumbers.expandable(value)).isFalse();
    assertThat(JsonNumbers.plain(value)).isEqualTo(expected);
  }

  @Test
  void everySpellingOfAHugeValueHasOneText() {
    for (String spelling : new String[] {"1e1000", "10e999", "0.1e1001", "1.0E+1000", "1000e997", "0.0010e1003"}) {
      assertThat(JsonNumbers.plain(new BigDecimal(spelling))).as(spelling).isEqualTo("1E+1000");
    }
  }

  @Test
  void whatIsCheckedKeepsTheDigitsAndBoundsTheZeros() {
    String bounded = JsonNumbers.checkable(new BigDecimal("8.4726193E+1000000000"));
    assertThat(bounded).isEqualTo("84726193" + "0".repeat(JsonNumbers.MAX_PADDING));

    String tiny = JsonNumbers.checkable(new BigDecimal("-8.4726193E-1000000000"));
    assertThat(tiny).isEqualTo("-0." + "0".repeat(JsonNumbers.MAX_PADDING) + "84726193");
  }

  @Test
  void textsAgreeWithTheLongStandingFormForEveryNumberThatWasExpandable() {
    Random random = new Random(20261110);
    for (int i = 0; i < 20_000; i++) {
      int digits = 1 + random.nextInt(40);
      StringBuilder unscaled = new StringBuilder();
      for (int d = 0; d < digits; d++) {
        // Zeros often, so that numbers have trailing zeros to strip.
        unscaled.append(random.nextInt(3) == 0 ? '0' : (char) ('1' + random.nextInt(9)));
      }
      int scale = random.nextInt(1000) - 500;
      BigDecimal value = new BigDecimal(new BigInteger(unscaled.toString()).multiply(
          random.nextBoolean() ? BigInteger.ONE : BigInteger.ONE.negate()), scale);
      BigDecimal stripped = value.stripTrailingZeros();
      long padding = stripped.scale() < 0 ? -(long) stripped.scale()
          : Math.max(0, stripped.scale() - stripped.precision());

      assertThat(JsonNumbers.expandable(value)).as(value.toString()).isEqualTo(padding <= JsonNumbers.MAX_PADDING);
      if (padding <= JsonNumbers.MAX_PADDING) {
        String legacy = stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
        assertThat(JsonNumbers.plain(value)).as(value.toString()).isEqualTo(legacy);
        assertThat(JsonNumbers.checkable(value)).as(value.toString()).isEqualTo(legacy);
      } else {
        assertThat(JsonNumbers.plain(value)).as(value.toString()).contains("E")
            .hasSizeLessThanOrEqualTo(digits + 16);
        assertThat(JsonNumbers.checkable(value)).as(value.toString()).hasSizeLessThanOrEqualTo(
            digits + JsonNumbers.MAX_PADDING + 4);
        // The same value, however it is spelled.
        assertThat(JsonNumbers.plain(stripped)).isEqualTo(JsonNumbers.plain(value));
      }
    }
  }

  @Test
  void aHugeNumberInAParsedDocumentBecomesItsText() {
    JsonNode tree = DECIMALS.readTree("""
        {"a":1e999999999,"b":[1.50,1e-1000000000,{"c":2e500,"d":"1e999999999"}],"e":129.00,"f":4.9e-324,"g":null}""");

    JsonNode kept = JsonNumbers.keepHugeAsText(tree);

    assertThat(kept).isSameAs(tree);
    assertThat(kept.path("a").isString()).isTrue();
    assertThat(kept.path("a").asString()).isEqualTo("1E+999999999");
    assertThat(kept.path("b").get(0).isBigDecimal()).isTrue();
    assertThat(kept.path("b").get(0).decimalValue()).isEqualByComparingTo("1.5");
    assertThat(kept.path("b").get(1).asString()).isEqualTo("1E-1000000000");
    assertThat(kept.path("b").get(1).isString()).isTrue();
    assertThat(kept.path("b").get(2).path("c").asString()).isEqualTo("2E+500");
    assertThat(kept.path("b").get(2).path("d").asString()).isEqualTo("1e999999999");
    assertThat(kept.path("e").isBigDecimal()).isTrue();
    assertThat(kept.path("e").asString()).isEqualTo("129.00");
    assertThat(kept.path("f").isBigDecimal()).isTrue();
    assertThat(kept.path("g").isNull()).isTrue();
  }

  @Test
  void aHugeNumberAtTheRootIsReplaced() {
    assertThat(JsonNumbers.keepHugeAsText(DECIMALS.readTree("1e999999999")).asString()).isEqualTo("1E+999999999");
    assertThat(JsonNumbers.keepHugeAsText(DECIMALS.readTree("1e99")).isBigDecimal()).isTrue();
    assertThat(JsonNumbers.keepHugeAsText(DECIMALS.readTree("\"text\"")).asString()).isEqualTo("text");
  }

  @Test
  void aDeeplyNestedDocumentIsWalkedWithoutRecursion() {
    StringBuilder json = new StringBuilder();
    int depth = 150;
    for (int i = 0; i < depth; i++) {
      json.append("[");
    }
    json.append("1e999999999");
    for (int i = 0; i < depth; i++) {
      json.append("]");
    }

    JsonNode kept = JsonNumbers.keepHugeAsText(DECIMALS.readTree(json.toString()));

    JsonNode node = kept;
    for (int i = 0; i < depth; i++) {
      node = node.get(0);
    }
    assertThat(node.asString()).isEqualTo("1E+999999999");
  }
}
