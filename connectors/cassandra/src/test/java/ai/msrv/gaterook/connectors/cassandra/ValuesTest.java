package ai.msrv.gaterook.connectors.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import com.datastax.oss.driver.internal.core.type.UserDefinedTypeBuilder;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CQL values and JSON, both ways, by the type the server declared. */
class ValuesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final CodecRegistry CODECS = CodecRegistry.DEFAULT;
  private static final UserDefinedType ADDRESS = new UserDefinedTypeBuilder("shop", "address")
      .withField("street", DataTypes.TEXT).withField("city", DataTypes.TEXT).build();

  private static Object in(DataType type, String json) throws Values.BadValue {
    return Values.java(type, JSON.readTree(json), "values[0]", CODECS, 0);
  }

  private static String out(DataType type, Object value) {
    return Values.json(type, value, new Values.Budget(8_192), 8_192, 0).toString();
  }

  @Test
  void scalarsBindFromTheirJsonForms() throws Exception {
    assertThat(in(DataTypes.TEXT, "\"Grüße\"")).isEqualTo("Grüße");
    assertThat(in(DataTypes.ASCII, "\"plain\"")).isEqualTo("plain");
    assertThat(in(DataTypes.INT, "42")).isEqualTo(42);
    assertThat(in(DataTypes.INT, "\"-42\"")).isEqualTo(-42);
    assertThat(in(DataTypes.SMALLINT, "-7")).isEqualTo((short) -7);
    assertThat(in(DataTypes.TINYINT, "3")).isEqualTo((byte) 3);
    assertThat(in(DataTypes.BIGINT, "\"9007199254740993\"")).isEqualTo(9_007_199_254_740_993L);
    assertThat(in(DataTypes.COUNTER, "5")).isEqualTo(5L);
    assertThat(in(DataTypes.VARINT, "\"123456789012345678901234567890\""))
        .isEqualTo(new BigInteger("123456789012345678901234567890"));
    assertThat(in(DataTypes.VARINT, "12345678901234567890123")).isEqualTo(new BigInteger("12345678901234567890123"));
    assertThat(in(DataTypes.DECIMAL, "\"12.30\"")).isEqualTo(new BigDecimal("12.30"));
    assertThat(((BigDecimal) in(DataTypes.DECIMAL, "1.5")).compareTo(new BigDecimal("1.5"))).isZero();
    assertThat(in(DataTypes.DOUBLE, "0.5")).isEqualTo(0.5);
    assertThat(in(DataTypes.DOUBLE, "\"NaN\"")).isEqualTo(Double.NaN);
    assertThat(in(DataTypes.FLOAT, "1.25")).isEqualTo(1.25f);
    assertThat(in(DataTypes.BOOLEAN, "true")).isEqualTo(true);
    assertThat(in(DataTypes.TIMESTAMP, "\"2026-01-02T03:04:05.678Z\""))
        .isEqualTo(Instant.parse("2026-01-02T03:04:05.678Z"));
    assertThat(in(DataTypes.TIMESTAMP, "\"2026-01-02T05:04:05+02:00\"")).isEqualTo(Instant.parse(
        "2026-01-02T03:04:05Z"));
    assertThat(in(DataTypes.TIMESTAMP, "1700000000000")).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
    assertThat(in(DataTypes.DATE, "\"2026-11-11\"")).isEqualTo(LocalDate.of(2026, 11, 11));
    assertThat(in(DataTypes.TIME, "\"13:45:30.123456789\"")).isEqualTo(LocalTime.of(13, 45, 30, 123_456_789));
    assertThat(in(DataTypes.UUID, "\"123e4567-e89b-12d3-a456-426614174000\""))
        .isEqualTo(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    assertThat(in(DataTypes.TIMEUUID, "\"50554d6e-29bb-11e5-b345-feff819cdc9f\"")).isInstanceOf(UUID.class);
    assertThat(in(DataTypes.BLOB, "\"0x0102ff\"")).isEqualTo(ByteBuffer.wrap(new byte[] {1, 2, (byte) 0xff}));
    assertThat(in(DataTypes.INET, "\"10.0.4.12\"")).isEqualTo(InetAddress.ofLiteral("10.0.4.12"));
    assertThat(in(DataTypes.INET, "\"2001:db8::1\"")).isEqualTo(InetAddress.ofLiteral("2001:db8::1"));
    assertThat(in(DataTypes.DURATION, "\"1h30m\"")).isEqualTo(CqlDuration.from("1h30m"));
    assertThat(in(DataTypes.TEXT, "null")).isNull();
  }

  @Test
  void collectionsTuplesUdtsAndVectorsBindFromArraysAndObjects() throws Exception {
    assertThat(in(DataTypes.listOf(DataTypes.TEXT), "[\"a\", \"b\"]")).isEqualTo(List.of("a", "b"));
    assertThat(in(DataTypes.setOf(DataTypes.INT), "[3, 1]")).isEqualTo(new LinkedHashSet<>(List.of(3, 1)));
    Map<Object, Object> counts = new LinkedHashMap<>();
    counts.put("x", 1);
    assertThat(in(DataTypes.mapOf(DataTypes.TEXT, DataTypes.INT), "{\"x\": 1}")).isEqualTo(counts);
    Map<Object, Object> byId = new LinkedHashMap<>();
    byId.put(7, "seven");
    assertThat(in(DataTypes.mapOf(DataTypes.INT, DataTypes.TEXT), "{\"7\": \"seven\"}")).isEqualTo(byId);
    assertThat(in(DataTypes.mapOf(DataTypes.INT, DataTypes.TEXT), "[[7, \"seven\"]]")).isEqualTo(byId);
    TupleType pair = DataTypes.tupleOf(DataTypes.INT, DataTypes.TEXT);
    TupleValue t = (TupleValue) in(pair, "[7, \"seven\"]");
    assertThat(t.getInt(0)).isEqualTo(7);
    assertThat(t.getString(1)).isEqualTo("seven");
    UdtValue u = (UdtValue) in(ADDRESS, "{\"street\": \"1 Main St\", \"city\": null}");
    assertThat(u.getString("street")).isEqualTo("1 Main St");
    assertThat(u.isNull("city")).isTrue();
    @SuppressWarnings("unchecked")
    CqlVector<Float> v = (CqlVector<Float>) in(DataTypes.vectorOf(DataTypes.FLOAT, 3), "[0.5, 1.5, -2]");
    assertThat(v.get(2)).isEqualTo(-2.0f);
    assertThat(in(DataTypes.listOf(DataTypes.frozenListOf(DataTypes.INT)), "[[1], [2, 3]]"))
        .isEqualTo(List.of(List.of(1), List.of(2, 3)));
  }

  @Test
  void valuesThatDontFitTheirTypeSayWhereAndWhy() {
    Object[][] bad = {
        {DataTypes.INT, "\"one\"", "values[0] needs an integer for int"},
        {DataTypes.INT, "2147483648", "outside the range of int"},
        {DataTypes.TINYINT, "128", "outside the range of tinyint"},
        {DataTypes.BIGINT, "1.5", "needs an integer"},
        {DataTypes.BIGINT, "\"99999999999999999999\"", "outside the range"},
        {DataTypes.VARINT, "1e3", "needs an integer"},
        {DataTypes.VARINT, "\"12a\"", "needs an integer"},
        {DataTypes.DECIMAL, "\"twelve\"", "isn't a decimal"},
        {DataTypes.DECIMAL, "true", "needs a number"},
        {DataTypes.DOUBLE, "\"fast\"", "needs a number"},
        {DataTypes.FLOAT, "1e300", "too large for a float"},
        {DataTypes.BOOLEAN, "\"yes\"", "true or false"},
        {DataTypes.TEXT, "5", "needs a string for text"},
        {DataTypes.ASCII, "\"é\"", "outside ASCII"},
        {DataTypes.TIMESTAMP, "\"yesterday\"", "needs a timestamp"},
        {DataTypes.DATE, "\"11/11/2026\"", "needs a date"},
        {DataTypes.TIME, "\"noon\"", "needs a time"},
        {DataTypes.UUID, "\"1-1-1-1-1\"", "needs a UUID"},
        {DataTypes.TIMEUUID, "\"123e4567-e89b-42d3-a456-426614174000\"", "version 1"},
        {DataTypes.BLOB, "\"0x123\"", "even number of hex digits"},
        {DataTypes.BLOB, "\"AQID\"", "0x"},
        {DataTypes.INET, "\"db.example.com\"", "names aren't looked up"},
        {DataTypes.INET, "\"10.0.0.300\"", "IP address"},
        {DataTypes.DURATION, "\"forever\"", "needs a duration"},
        {DataTypes.listOf(DataTypes.INT), "{\"a\": 1}", "needs an array"},
        {DataTypes.listOf(DataTypes.INT), "[1, null]", "values[0][1] is null"},
        {DataTypes.mapOf(DataTypes.TEXT, DataTypes.INT), "[[\"a\"]]", "needs [key, value]"},
        {DataTypes.mapOf(DataTypes.UUID, DataTypes.INT), "{\"not-a-uuid\": 1}", "values[0][\"not-a-uuid\"]"},
        {DataTypes.tupleOf(DataTypes.INT, DataTypes.TEXT), "[1]", "needs 2 components"},
        {DataTypes.vectorOf(DataTypes.FLOAT, 3), "[1, 2]", "needs 3 numbers"},
        {ADDRESS, "{\"zip\": \"x\"}", "values[0].zip isn't a field"},
        {ADDRESS, "[\"x\"]", "needs an object"},
        {DataTypes.custom("org.example.Weird"), "\"x\"", "custom type"}};
    for (Object[] b : bad) {
      assertThatThrownBy(() -> in((DataType) b[0], (String) b[1])).as(b[1] + " for " + b[0])
          .isInstanceOf(Values.BadValue.class).hasMessageContaining((String) b[2]);
    }
  }

  @Test
  void hugeNumbersStayCheap() throws Exception {
    // A decimal keeps its exponent: no ten-to-the-billion is ever computed.
    BigDecimal tiny = (BigDecimal) in(DataTypes.DECIMAL, "\"1E-999999999\"");
    assertThat(tiny.scale()).isEqualTo(999_999_999);
    assertThat(out(DataTypes.DECIMAL, tiny)).isEqualTo("\"1E-999999999\"");
    assertThat(out(DataTypes.DECIMAL, new BigDecimal("12.30"))).isEqualTo("\"12.30\"");
    assertThatThrownBy(() -> in(DataTypes.VARINT, "\"1" + "0".repeat(5_000) + "\"")).isInstanceOf(
        Values.BadValue.class);
  }

  @Test
  void resultsKeepCassandrasTypes() {
    assertThat(out(DataTypes.INT, 42)).isEqualTo("42");
    assertThat(out(DataTypes.BIGINT, 9_007_199_254_740_992L)).isEqualTo("9007199254740992");
    assertThat(out(DataTypes.BIGINT, 9_007_199_254_740_993L)).isEqualTo("\"9007199254740993\"");
    assertThat(out(DataTypes.VARINT, new BigInteger("-12"))).isEqualTo("-12");
    assertThat(out(DataTypes.VARINT, new BigInteger("123456789012345678901234567890")))
        .isEqualTo("\"123456789012345678901234567890\"");
    assertThat(out(DataTypes.DOUBLE, Double.POSITIVE_INFINITY)).isEqualTo("\"Infinity\"");
    assertThat(out(DataTypes.FLOAT, 1.25f)).isEqualTo("1.25");
    assertThat(out(DataTypes.TIMESTAMP, Instant.parse("2026-01-02T03:04:05.678Z")))
        .isEqualTo("\"2026-01-02T03:04:05.678Z\"");
    assertThat(out(DataTypes.DATE, LocalDate.of(2026, 11, 11))).isEqualTo("\"2026-11-11\"");
    assertThat(out(DataTypes.INET, InetAddress.ofLiteral("10.0.4.12"))).isEqualTo("\"10.0.4.12\"");
    assertThat(out(DataTypes.DURATION, CqlDuration.from("1h30m"))).isEqualTo("\"1h30m\"");
    assertThat(out(DataTypes.mapOf(DataTypes.INT, DataTypes.TEXT), Map.of(7, "seven")))
        .isEqualTo("{\"7\":\"seven\"}");
    assertThat(out(DataTypes.mapOf(DataTypes.frozenListOf(DataTypes.INT), DataTypes.TEXT), Map.of(List.of(1, 2),
        "pair"))).isEqualTo("[{\"key\":[1,2],\"value\":\"pair\"}]");
    TupleValue t = DataTypes.tupleOf(DataTypes.INT, DataTypes.TEXT).newValue(7, "seven");
    assertThat(out(t.getType(), t)).isEqualTo("[7,\"seven\"]");
    UdtValue u = ADDRESS.newValue().setString("street", "1 Main St");
    assertThat(out(ADDRESS, u)).isEqualTo("{\"street\":\"1 Main St\",\"city\":null}");
    assertThat(out(DataTypes.vectorOf(DataTypes.FLOAT, 2), CqlVector.newInstance(0.5f, -1f))).isEqualTo("[0.5,-1.0]");
  }

  @Test
  void cellsAreCutToTheCellSize() {
    assertThat(Values.text(ByteBuffer.wrap("Grüße".getBytes(StandardCharsets.UTF_8)), 10).asString())
        .isEqualTo("Grüße");
    String long_ = "x".repeat(50);
    assertThat(Values.text(ByteBuffer.wrap(long_.getBytes(StandardCharsets.UTF_8)), 10).asString())
        .isEqualTo("xxxxxxxxxx…‹50 bytes›");
    // A large text cell is decoded only as far as the cell shows.
    String huge = "y".repeat(1_000_000);
    assertThat(Values.text(ByteBuffer.wrap(huge.getBytes(StandardCharsets.UTF_8)), 20).asString())
        .isEqualTo("y".repeat(20) + "…‹1000000 bytes›");
    byte[] bytes = new byte[300];
    bytes[0] = (byte) 0xab;
    String blob = Values.blob(ByteBuffer.wrap(bytes)).asString();
    assertThat(blob).startsWith("0xab00").endsWith("…‹300 bytes›").hasSize(2 + 512 + "…‹300 bytes›".length());
    assertThat(Values.cut("😀".repeat(10), 3).asString()).startsWith("😀").contains("…‹20 characters›");
    // A collection stops where its budget ends, and says what it left out.
    List<String> items = java.util.Collections.nCopies(100, "abcdefghij");
    JsonNode list = Values.json(DataTypes.listOf(DataTypes.TEXT), items, new Values.Budget(60), 60, 0);
    assertThat(list.size()).isLessThan(10);
    assertThat(list.get(list.size() - 1).asString()).matches("…‹\\d+ more items›");
    assertThat(Values.cql(DataTypes.mapOf(DataTypes.TEXT, DataTypes.frozenListOf(DataTypes.INT))))
        .isEqualTo("map<text, frozen<list<int>>>");
  }
}
