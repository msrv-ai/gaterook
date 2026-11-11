package ai.msrv.gaterook.connectors.neo4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.internal.InternalNode;
import org.neo4j.driver.internal.InternalPath;
import org.neo4j.driver.internal.InternalRelationship;
import org.neo4j.driver.internal.value.NodeValue;
import org.neo4j.driver.internal.value.PathValue;
import org.neo4j.driver.internal.value.RelationshipValue;
import tools.jackson.databind.JsonNode;

/** Neo4j's values as an agent reads them: exact numbers where JavaScript keeps them, graphs as plain JSON. */
class Neo4jJsonTest {

  private static JsonNode json(Value v) {
    return Neo4jJson.value(v, 8_192, new Neo4jJson.Budget(1_000_000));
  }

  static InternalNode ada() {
    return new InternalNode(1, "4:graph:1", List.of("Person", "Engineer"), Map.of("name", Values.value("Ada"),
        "born", Values.value(LocalDate.of(1815, 12, 10)), "big", Values.value(1L << 60)));
  }

  static InternalNode bob() {
    return new InternalNode(2, "4:graph:2", List.of("Person"), Map.of("name", Values.value("Bob")));
  }

  static InternalRelationship knows() {
    return new InternalRelationship(7, "5:graph:7", 1, "4:graph:1", 2, "4:graph:2", "KNOWS",
        Map.of("since", Values.value(2020)));
  }

  @Test
  void numbersAreExactWhereJavaScriptKeepsThem() {
    assertThat(json(Values.value(42)).isNumber()).isTrue();
    assertThat(json(Values.value(9_007_199_254_740_992L)).asLong()).isEqualTo(9_007_199_254_740_992L);
    assertThat(json(Values.value(9_007_199_254_740_993L)).asString()).isEqualTo("9007199254740993");
    assertThat(json(Values.value(-(1L << 60))).asString()).isEqualTo("-1152921504606846976");
    assertThat(json(Values.value(Long.MIN_VALUE)).asString()).isEqualTo(Long.toString(Long.MIN_VALUE));
    assertThat(json(Values.value(1.5)).asDouble()).isEqualTo(1.5);
    assertThat(json(Values.value(Double.NaN)).asString()).isEqualTo("NaN");
    assertThat(json(Values.value(Double.POSITIVE_INFINITY)).asString()).isEqualTo("Infinity");
    assertThat(json(Values.value(Double.NEGATIVE_INFINITY)).asString()).isEqualTo("-Infinity");
    assertThat(json(Values.value(true)).asBoolean()).isTrue();
    assertThat(json(Values.NULL).isNull()).isTrue();
  }

  @Test
  void stringsAndBytesAreBounded() {
    assertThat(Neo4jJson.value(Values.value("x".repeat(20)), 8, new Neo4jJson.Budget(1_000)).asString())
        .isEqualTo("xxxxxxxx…‹20 characters›");
    assertThat(Neo4jJson.value(Values.value("ab😀cd"), 3, new Neo4jJson.Budget(1_000)).asString())
        .startsWith("ab…");
    byte[] bytes = new byte[300];
    bytes[0] = 0x0a;
    assertThat(json(Values.value(bytes)).asString()).startsWith("\\x0a00").endsWith("…‹300 bytes›");
    assertThat(json(Values.value(new byte[] {1, 2})).asString()).isEqualTo("\\x0102");
  }

  @Test
  void nodesRelationshipsAndPaths() {
    JsonNode n = json(new NodeValue(ada()));
    assertThat(n.path("elementId").asString()).isEqualTo("4:graph:1");
    assertThat(n.path("labels").toString()).isEqualTo("[\"Person\",\"Engineer\"]");
    assertThat(n.at("/properties/name").asString()).isEqualTo("Ada");
    assertThat(n.at("/properties/born").asString()).isEqualTo("1815-12-10");
    assertThat(n.at("/properties/big").asString()).isEqualTo("1152921504606846976");
    JsonNode r = json(new RelationshipValue(knows()));
    assertThat(r.path("type").asString()).isEqualTo("KNOWS");
    assertThat(r.path("startElementId").asString()).isEqualTo("4:graph:1");
    assertThat(r.path("endElementId").asString()).isEqualTo("4:graph:2");
    assertThat(r.at("/properties/since").asInt()).isEqualTo(2020);
    JsonNode p = json(new PathValue(new InternalPath(ada(), knows(), bob())));
    assertThat(p.path("length").asInt()).isEqualTo(1);
    assertThat(p.path("nodes").size()).isEqualTo(2);
    assertThat(p.at("/nodes/1/properties/name").asString()).isEqualTo("Bob");
    assertThat(p.at("/relationships/0/type").asString()).isEqualTo("KNOWS");
    assertThat(Neo4jJson.type(new NodeValue(ada()))).isEqualTo("NODE");
    assertThat(Neo4jJson.type(Values.value(List.of(1, 2)))).isEqualTo("LIST");
  }

  @Test
  void temporalAndSpatialValuesAreIso8601AndCoordinates() {
    assertThat(json(Values.value(LocalDate.of(2026, 1, 2))).asString()).isEqualTo("2026-01-02");
    assertThat(json(Values.value(LocalTime.of(3, 4))).asString()).isEqualTo("03:04:00");
    assertThat(json(Values.value(OffsetTime.of(3, 4, 5, 500_000_000, ZoneOffset.ofHours(2)))).asString())
        .isEqualTo("03:04:05.5+02:00");
    assertThat(json(Values.value(LocalDateTime.of(2026, 1, 2, 3, 4, 5))).asString()).isEqualTo("2026-01-02T03:04:05");
    assertThat(json(Values.value(ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneId.of("Europe/Berlin")))).asString())
        .isEqualTo("2026-01-02T03:04:05+01:00[Europe/Berlin]");
    assertThat(json(Values.value(ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(-5)))).asString())
        .isEqualTo("2026-01-02T03:04:05-05:00");
    assertThat(json(Values.isoDuration(14, 16, 45_005, 500_000_000)).asString()).startsWith("P14M16DT45005.5");
    JsonNode wgs = json(Values.point(4326, 12.5, 55.6));
    assertThat(wgs.path("crs").asString()).isEqualTo("wgs-84");
    assertThat(wgs.path("x").asDouble()).isEqualTo(12.5);
    assertThat(wgs.has("z")).isFalse();
    JsonNode cartesian = json(Values.point(9157, 1, 2, 3));
    assertThat(cartesian.path("crs").asString()).isEqualTo("cartesian-3d");
    assertThat(cartesian.path("z").asDouble()).isEqualTo(3.0);
    assertThat(json(Values.vector(new double[] {0.5, 1.5})).toString()).isEqualTo("[0.5,1.5]");
    assertThat(json(Values.vector(new long[] {1L << 60})).toString()).isEqualTo("[\"1152921504606846976\"]");
  }

  @Test
  void listsAndMapsStayWithinTheirBounds() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("a", List.of(1, 2, 3));
    m.put("b", Map.of("c", "d"));
    JsonNode map = json(Values.value(m));
    assertThat(map.toString()).isEqualTo("{\"a\":[1,2,3],\"b\":{\"c\":\"d\"}}");
    List<Object> many = new ArrayList<>();
    for (int i = 0; i < 1_500; i++) {
      many.add(i);
    }
    Neo4jJson.Budget budget = new Neo4jJson.Budget(1_000_000);
    JsonNode cut = Neo4jJson.value(Values.value(many), 100, budget);
    assertThat(cut.size()).isEqualTo(Neo4jJson.MAX_ITEMS + 1);
    assertThat(cut.get(Neo4jJson.MAX_ITEMS).asString()).isEqualTo("…‹500 more›");
    assertThat(budget.cut()).isTrue();
    Neo4jJson.Budget small = new Neo4jJson.Budget(30);
    JsonNode spent = Neo4jJson.value(Values.value(List.of("x".repeat(40), "y".repeat(40), "z")), 100, small);
    assertThat(spent.size()).isEqualTo(2);
    assertThat(spent.get(1).asString()).isEqualTo("…‹2 more›");
    Object nested = 1;
    for (int i = 0; i < 20; i++) {
      nested = List.of(nested);
    }
    assertThat(json(Values.value(nested)).toString()).contains("nested deeper than");
  }
}
