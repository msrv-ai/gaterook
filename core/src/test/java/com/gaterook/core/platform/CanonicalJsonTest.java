package com.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The canonical text feeds MACs and digests, so a number written the way it always was must keep its bytes; and no
 * number, whatever its exponent, may make the text long or the call fail.
 */
class CanonicalJsonTest {

  private static final JsonMapper DOUBLES = JsonMapper.builder().build();
  private static final JsonMapper DECIMALS = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  /** What the text of a document has always been, as doubles and as exact decimals read it. */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "0                                   | 0",
      "-0                                  | 0",
      "0.0                                 | 0",
      "-0.0                                | 0",
      "7                                   | 7",
      "-42                                 | -42",
      "9223372036854775807                 | 9223372036854775807",
      "123456789012345678901234567890      | 123456789012345678901234567890",
      "5.0                                 | 5",
      "129.00                              | 129",
      "1.5                                 | 1.5",
      "-0.25                               | -0.25",
      "0.1                                 | 0.1",
      "1e3                                 | 1000",
      "1E+3                                | 1000",
      "2.5e2                               | 250",
      "1e-7                                | 0.0000001",
      "-1.25e-5                            | -0.0000125",
      "100.250                             | 100.25",
      "1e22                                | 10000000000000000000000",
      "0.30000000000000004                 | 0.30000000000000004",
      "3.141592653589793                   | 3.141592653589793",
  })
  void ordinaryNumbersKeepTheirBytes(String number, String expected) {
    assertThat(CanonicalJson.text(DOUBLES.readTree("[" + number + "]"))).isEqualTo("[" + expected + "]");
    assertThat(CanonicalJson.text(DECIMALS.readTree("[" + number + "]"))).isEqualTo("[" + expected + "]");
  }

  @Test
  void theLargestAndSmallestDoublesAreWrittenInFull() {
    assertThat(CanonicalJson.text(DOUBLES.readTree("[1.7976931348623157E308]")))
        .isEqualTo("[17976931348623157" + "0".repeat(292) + "]");
    assertThat(CanonicalJson.text(DOUBLES.readTree("[-1.7976931348623157E308]")))
        .isEqualTo("[-17976931348623157" + "0".repeat(292) + "]");
    assertThat(CanonicalJson.text(DOUBLES.readTree("[4.9E-324]"))).isEqualTo("[0." + "0".repeat(323) + "49]");
    assertThat(CanonicalJson.text(DECIMALS.readTree("[4.9E-324]"))).isEqualTo("[0." + "0".repeat(323) + "49]");
    assertThat(CanonicalJson.text(JsonNodeFactory.instance.numberNode(Double.MIN_NORMAL)))
        .isEqualTo("0." + "0".repeat(307) + "22250738585072014");
    assertThat(CanonicalJson.text(JsonNodeFactory.instance.numberNode(1.5f))).isEqualTo("1.5");
  }

  @Test
  void aLongDecimalIsWrittenInFullWhateverItsLength() {
    String digits = "3".repeat(900);
    assertThat(CanonicalJson.text(DECIMALS.readTree("[0." + digits + "]"))).isEqualTo("[0." + digits + "]");
    assertThat(CanonicalJson.text(DECIMALS.readTree("[" + digits + ".25]"))).isEqualTo("[" + digits + ".25]");
  }

  @Test
  void theSameValueHasTheSameBytesWhateverItsSpelling() {
    assertThat(CanonicalJson.text(DECIMALS.readTree("[1.0E+1000, 10e999, 0.1e1001, 1000e997]")))
        .isEqualTo("[1E+1000,1E+1000,1E+1000,1E+1000]");
    assertThat(CanonicalJson.text(DECIMALS.readTree("[1e-1000, 0.1e-999, 10e-1001]")))
        .isEqualTo("[1E-1000,1E-1000,1E-1000]");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "1e1000000000                         | 1E+1000000000",
      "1e-1000000000                        | 1E-1000000000",
      "-1.5e2147483647                      | -1.5E+2147483647",
      "100e2147483647                       | 1E+2147483649",
      "1e-2147483647                        | 1E-2147483647",
      "10e-2147483647                       | 1E-2147483646",
      "1.25e403                             | 1.25E+403",
      "1e-402                               | 1E-402",
  })
  void anExponentNeverDecidesHowLongTheTextIs(String number, String expected) {
    assertThat(CanonicalJson.text(DECIMALS.readTree("[" + number + "]"))).isEqualTo("[" + expected + "]");
  }

  @Test
  void theBoundIsExactlyTheZerosTheExponentAdds() {
    assertThat(CanonicalJson.text(DECIMALS.readTree("[1e400]"))).isEqualTo("[1" + "0".repeat(400) + "]");
    assertThat(CanonicalJson.text(DECIMALS.readTree("[1e401]"))).isEqualTo("[1E+401]");
    assertThat(CanonicalJson.text(DECIMALS.readTree("[1e-401]"))).isEqualTo("[0." + "0".repeat(400) + "1]");
    assertThat(CanonicalJson.text(DECIMALS.readTree("[1e-402]"))).isEqualTo("[1E-402]");
  }

  @Test
  void doublesBeyondTheRangeOfADoubleAreAStringNotAFailure() {
    // The default mapper reads 1e999 as an infinite double; stored as JSON it comes back as the string "Infinity".
    JsonNode infinite = DOUBLES.readTree("{\"a\":1e999,\"b\":-1e999,\"c\":1e-999}");
    assertThat(CanonicalJson.text(infinite)).isEqualTo("{\"a\":\"Infinity\",\"b\":\"-Infinity\",\"c\":0}");
    assertThat(CanonicalJson.text(infinite)).isEqualTo(CanonicalJson.text(DOUBLES.readTree(
        DOUBLES.writeValueAsString(infinite))));
    assertThat(CanonicalJson.text(JsonNodeFactory.instance.numberNode(Double.NaN))).isEqualTo("\"NaN\"");
    assertThat(CanonicalJson.text(JsonNodeFactory.instance.numberNode(Float.POSITIVE_INFINITY)))
        .isEqualTo("\"Infinity\"");
    assertThat(CanonicalJson.sha256Hex(infinite)).hasSize(64);
  }

  @Test
  void nothingAllocatesInProportionToTheExponent() {
    // Under the 384 MB heap of the core, 1e-1000000000 once ended the process, and 1e20000000 took the better part of
    // a minute: the whole document is a few dozen characters now.
    JsonNode hostile = DECIMALS.readTree("{\"a\":1e-1000000000,\"b\":1e1000000000,\"c\":[1e20000000,1e-20000000,"
        + "1e2147483647,1e-2147483647,100e2147483647]}");

    String text = CanonicalJson.text(hostile);

    assertThat(text).hasSizeLessThan(200);
    assertThat(CanonicalJson.bytes(hostile)).hasSizeLessThan(200);
    assertThat(CanonicalJson.sha256Hex(hostile)).hasSize(64);
  }

  @Test
  void integersAreUnchanged() {
    assertThat(CanonicalJson.text(JsonNodeFactory.instance.numberNode(new BigInteger("-" + "9".repeat(300)))))
        .isEqualTo("-" + "9".repeat(300));
    assertThat(CanonicalJson.text(JsonNodeFactory.instance.numberNode(Integer.MIN_VALUE)))
        .isEqualTo("-2147483648");
    assertThat(CanonicalJson.text(JsonNodeFactory.instance.numberNode(new BigDecimal("12345678901234567890"))))
        .isEqualTo("12345678901234567890");
  }
}
