package com.gaterook.connectors.snowflake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Cells of the SQL API typed by their column: exact numbers, ISO times, compact JSON, bounded text and hex. */
class ValuesTest {

  private static JsonNode cell(String raw, String type, int scale) {
    return Values.cell(raw == null ? JsonNodeFactory.instance.nullNode() : JsonNodeFactory.instance.stringNode(raw),
        new Values.Column("C", type, 38, scale, 0, null, null, null), 100);
  }

  private static JsonNode cell(String raw, String type) {
    return cell(raw, type, 0);
  }

  @Test
  void numbersAreJsonNumbersOnlyWhereTheyStayExact() {
    assertThat(cell("9007199254740992", "FIXED").isIntegralNumber()).isTrue();
    assertThat(cell("-9007199254740992", "FIXED").asLong()).isEqualTo(-9_007_199_254_740_992L);
    assertThat(cell("9007199254740993", "FIXED").asString()).isEqualTo("9007199254740993");
    assertThat(cell("0", "FIXED").asLong()).isZero();
    assertThat(cell("10.00", "FIXED", 2).asString()).isEqualTo("10.00");
    assertThat(cell("1E+3", "DECFLOAT").asLong()).isEqualTo(1000);
    assertThat(cell("0.1", "DECFLOAT").asString()).isEqualTo("0.1");
    assertThat(cell("2.5e-3", "REAL").asDouble()).isEqualTo(0.0025);
    assertThat(cell("inf", "REAL").asString()).isEqualTo("inf");
    assertThat(cell("-Infinity", "REAL").asString()).isEqualTo("-Infinity");
    assertThat(cell("not a number", "FIXED").asString()).isEqualTo("not a number");
  }

  @Test
  void timesAreIsoText() {
    assertThat(cell("0", "DATE").asString()).isEqualTo("1970-01-01");
    assertThat(cell("-1", "DATE").asString()).isEqualTo("1969-12-31");
    assertThat(cell("0.000000001", "TIME").asString()).isEqualTo("00:00:00.000000001");
    assertThat(cell("-0.500000000", "TIMESTAMP_NTZ").asString()).isEqualTo("1969-12-31T23:59:59.5");
    assertThat(cell("1616173619.000000000 1440", "TIMESTAMP_TZ").asString()).isEqualTo("2021-03-19T17:06:59Z");
    assertThat(cell("1616173619.000000000 2040", "TIMESTAMP_TZ").asString())
        .isEqualTo("2021-03-20T03:06:59+10:00");
    assertThat(cell("yesterday", "DATE").asString()).isEqualTo("yesterday");
  }

  @Test
  void booleansJsonAndBytes() {
    assertThat(cell("1", "BOOLEAN").asBoolean()).isTrue();
    assertThat(cell("FALSE", "BOOLEAN").asBoolean()).isFalse();
    assertThat(cell("{\n  \"k\": \"a b\\\" c\",\n  \"n\": null\n}", "OBJECT").asString())
        .isEqualTo("{\"k\":\"a b\\\" c\",\"n\":null}");
    assertThat(cell("\"just text\"", "VARIANT").asString()).isEqualTo("\"just text\"");
    assertThat(cell("{\"type\": \"Point\", \"coordinates\": [1, 2]}", "GEOGRAPHY").asString())
        .isEqualTo("{\"type\":\"Point\",\"coordinates\":[1,2]}");
    String big = "AB".repeat(300);
    assertThat(cell(big, "BINARY").asString()).isEqualTo("\\x" + "ab".repeat(256) + "…‹300 bytes›");
    assertThat(cell("XYZ", "BINARY").asString()).isEqualTo("XYZ");
    assertThat(cell(null, "TEXT").isNull()).isTrue();
  }

  @Test
  void longTextIsCutOutsideSurrogatePairsAndSaysHowMuch() {
    String emoji = "a".repeat(99) + "😀" + "b";
    JsonNode cut = cell(emoji, "TEXT");
    assertThat(cut.asString()).startsWith("a".repeat(99) + "…‹truncated 3 chars›");
    assertThat(Values.text("short", 10).asString()).isEqualTo("short");
  }

  @Test
  void columnsCarryTheirSqlTypeAndOrigin() {
    List<Values.Column> columns = Values.columns(FakeSnowflake.JSON.readTree("[{\"name\": \"ID\", \"type\": \"fixed\", "
        + "\"precision\": 10, \"scale\": 2, \"database\": \"ANALYTICS\", \"schema\": \"PUBLIC\", "
        + "\"table\": \"ORDERS\"}, {\"name\": \"E\", \"type\": \"text\", \"length\": 0, \"database\": \"\", "
        + "\"schema\": \"\", \"table\": \"\"}]"));
    assertThat(columns.get(0).sqlType()).isEqualTo("NUMBER(10,2)");
    assertThat(columns.get(0).tool().sourceSchema()).isEqualTo("PUBLIC");
    assertThat(columns.get(0).tool().sourceTable()).isEqualTo("ORDERS");
    assertThat(columns.get(1).sqlType()).isEqualTo("VARCHAR");
    assertThat(columns.get(1).tool().sourceTable()).isNull();
  }
}
