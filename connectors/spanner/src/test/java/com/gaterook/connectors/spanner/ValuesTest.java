package com.gaterook.connectors.spanner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Spanner's values as cells, and parameters as a request's params and paramTypes. */
class ValuesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode type(String json) {
    return JSON.readTree(json);
  }

  @Test
  void cellsKeepTheirTypes() {
    assertThat(Values.value(JSON.readTree("\"42\""), type("{\"code\":\"INT64\"}"), 100, 0).asLong()).isEqualTo(42);
    assertThat(Values.value(JSON.readTree("\"123456789012345678\""), type("{\"code\":\"INT64\"}"), 100, 0).isString())
        .isTrue();
    assertThat(Values.value(JSON.readTree("\"NaN\""), type("{\"code\":\"FLOAT64\"}"), 100, 0).asString())
        .isEqualTo("NaN");
    assertThat(Values.value(JSON.readTree("1.5"), type("{\"code\":\"FLOAT64\"}"), 100, 0).asDouble()).isEqualTo(1.5);
    assertThat(Values.value(JSON.readTree("\"AP8=\""), type("{\"code\":\"BYTES\"}"), 100, 0).asString())
        .isEqualTo("\\x00ff");
    JsonNode struct = Values.value(JSON.readTree("[\"Ada\",\"7\"]"), type("{\"code\":\"STRUCT\",\"structType\":"
        + "{\"fields\":[{\"name\":\"name\",\"type\":{\"code\":\"STRING\"}},{\"name\":\"\",\"type\":{\"code\":"
        + "\"INT64\"}}]}}"), 100, 0);
    assertThat(struct.toString()).isEqualTo("{\"name\":\"Ada\",\"_1\":7}");
    assertThat(Values.value(JSON.readTree("\"abcdef\""), type("{\"code\":\"STRING\"}"), 3, 0).asString())
        .isEqualTo("abc…‹6 characters›");
  }

  @Test
  void typeNamesFollowTheDialect() {
    JsonNode array = type("{\"code\":\"ARRAY\",\"arrayElementType\":{\"code\":\"INT64\"}}");
    assertThat(Values.typeName(array, false)).isEqualTo("ARRAY<INT64>");
    assertThat(Values.typeName(array, true)).isEqualTo("bigint[]");
    assertThat(Values.typeName(type("{\"code\":\"JSON\",\"typeAnnotation\":\"PG_JSONB\"}"), true)).isEqualTo("jsonb");
  }

  @Test
  void parametersAreCheckedAndEncoded() {
    assertThat(Values.problem(JSON.readTree("[{\"name\":\"d\",\"type\":\"DATE\",\"value\":\"2026-11-31x\"}]")))
        .isEqualTo("Parameter d (DATE) needs a date such as 2026-11-11.");
    assertThat(Values.problem(JSON.readTree("[{\"name\":\"$1\",\"type\":\"STRING\",\"value\":\"x\"}]")))
        .startsWith("A parameter needs a name");
    assertThat(Values.problem(JSON.readTree("[{\"name\":\"a\",\"type\":\"ARRAY\",\"value\":\"x\"}]")))
        .contains("BOOL, BYTES, DATE");
    String ok = "[{\"name\":\"id\",\"type\":\"INT64\",\"value\":7},{\"name\":\"amount\",\"type\":\"NUMERIC\","
        + "\"value\":\"1.10\"},{\"name\":\"paid\",\"type\":\"BOOL\",\"value\":true},{\"name\":\"note\",\"type\":"
        + "\"STRING\",\"value\":null}]";
    assertThat(Values.problem(JSON.readTree(ok))).isNull();
    ObjectNode request = JSON.createObjectNode();
    Values.encode(JSON.readTree(ok), request, true);
    assertThat(request.path("params").toString()).isEqualTo("{\"id\":\"7\",\"amount\":\"1.10\",\"paid\":true,"
        + "\"note\":null}");
    assertThat(request.at("/paramTypes/amount/typeAnnotation").asString()).isEqualTo("PG_NUMERIC");
    assertThat(request.at("/paramTypes/id/code").asString()).isEqualTo("INT64");
    assertThat(request.path("paramTypes").size()).isEqualTo(4);
  }
}
