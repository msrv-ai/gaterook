package com.gaterook.connectors.firebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Typed values both ways, field paths and the encoding of names, without a server. */
class FirestoreValuesTest {

  private static final String DOCS = "projects/acme/databases/(default)/documents";

  private static JsonNode typed(String json) throws Refusal {
    return FirestoreValues.typed(TestCtx.JSON.readTree(json), DOCS, "value");
  }

  @Test
  void plainJsonBecomesTypedValues() throws Exception {
    assertThat(typed("\"a\"").toString()).isEqualTo("{\"stringValue\":\"a\"}");
    assertThat(typed("42").toString()).isEqualTo("{\"integerValue\":\"42\"}");
    assertThat(typed("-9223372036854775808").toString()).isEqualTo("{\"integerValue\":\"-9223372036854775808\"}");
    assertThat(typed("1.5").toString()).isEqualTo("{\"doubleValue\":1.5}");
    assertThat(typed("1.0").toString()).isEqualTo("{\"doubleValue\":1.0}");
    assertThat(typed("true").toString()).isEqualTo("{\"booleanValue\":true}");
    assertThat(typed("null").toString()).isEqualTo("{\"nullValue\":null}");
    assertThat(typed("[1, \"a\"]").toString()).isEqualTo("{\"arrayValue\":{\"values\":[{\"integerValue\":\"1\"},"
        + "{\"stringValue\":\"a\"}]}}");
    assertThat(typed("{\"a\": {\"b\": 1}, \"when\": {\"timestampValue\": \"2026-01-02T03:04:05+01:00\"}}")
        .toString()).isEqualTo("{\"mapValue\":{\"fields\":{\"a\":{\"mapValue\":{\"fields\":{\"b\":{\"integerValue\":"
            + "\"1\"}}}},\"when\":{\"timestampValue\":\"2026-01-02T03:04:05+01:00\"}}}}");
    // An object with one key that isn't a type is a map.
    assertThat(typed("{\"status\": \"open\"}").toString()).isEqualTo("{\"mapValue\":{\"fields\":{\"status\":"
        + "{\"stringValue\":\"open\"}}}}");
    assertThat(typed("{\"integerValue\": 7}").toString()).as("normalized to its digits")
        .isEqualTo("{\"integerValue\":\"7\"}");
    assertThat(typed("{\"referenceValue\": \"users/alice\"}").path("referenceValue").asString())
        .isEqualTo(DOCS + "/users/alice");
    assertThat(typed("{\"referenceValue\": \"" + DOCS + "/users/alice\"}").path("referenceValue").asString())
        .isEqualTo(DOCS + "/users/alice");
    assertThat(typed("{\"geoPointValue\": {\"latitude\": 1, \"longitude\": 2}}").toString())
        .isEqualTo("{\"geoPointValue\":{\"latitude\":1.0,\"longitude\":2.0}}");
    assertThat(typed("{\"doubleValue\": \"-Infinity\"}").toString()).isEqualTo("{\"doubleValue\":\"-Infinity\"}");
    assertThat(typed("{\"bytesValue\": \"AP8Q\"}").toString()).isEqualTo("{\"bytesValue\":\"AP8Q\"}");
    assertThat(typed("{\"mapValue\": {}}").toString()).isEqualTo("{\"mapValue\":{\"fields\":{}}}");
  }

  @Test
  void whatFirestoreWouldNotStoreIsRefused() {
    for (String bad : List.of("9223372036854775808", "[[1]]", "{\"arrayValue\": {\"values\": [{\"arrayValue\": "
            + "{}}]}}", "{\"stringValue\": 5}", "{\"integerValue\": \"1e3\"}", "{\"integerValue\": 1.5}",
        "{\"doubleValue\": \"nan\"}", "{\"timestampValue\": \"yesterday\"}", "{\"timestampValue\": "
            + "\"2026-01-02T03:04:05\"}", "{\"bytesValue\": \"not base64!\"}", "{\"geoPointValue\": {\"latitude\": "
            + "91, \"longitude\": 0}}", "{\"geoPointValue\": {\"latitude\": 1}}", "{\"booleanValue\": \"true\"}",
        "{\"nullValue\": 0}", "{\"mapValue\": {\"fields\": {\"a\": 1}}}", "{\"arrayValue\": {\"values\": [\"a\"]}}",
        "{\"mapValue\": {\"other\": {}}}", "{\"referenceValue\": \"users\"}", "{\"referenceValue\": \"users/../x\"}",
        "{\"referenceValue\": \"projects/other/databases/(default)/documents/users/x\"}", "{\"__name__\": 1}",
        "{\"__x__\": 1}", "{\"\": 1}", "{\"a\\u0000\": 1}", "{\"a\\u202e\": 1}")) {
      assertThatThrownBy(() -> typed(bad)).as(bad).isInstanceOf(Refusal.class);
    }
    StringBuilder deep = new StringBuilder("1");
    for (int i = 0; i < 21; i++) {
      deep.insert(0, "{\"a\": ").append("}");
    }
    assertThatThrownBy(() -> typed(deep.toString())).hasMessageContaining("deeper than Firestore's 20 levels");
    assertThatThrownBy(() -> FirestoreValues.typedFields(TestCtx.JSON.readTree("{\"a\": \"plain\"}"), DOCS,
        "fields")).as("an approved write holds typed values only").isInstanceOf(Refusal.class);
  }

  @Test
  void typedValuesComeBackAsPlainJson() {
    assertThat(FirestoreValues.integerShown("9007199254740992").isIntegralNumber()).isTrue();
    assertThat(FirestoreValues.integerShown("-9007199254740992").isIntegralNumber()).isTrue();
    assertThat(FirestoreValues.integerShown("9007199254740993").asString()).isEqualTo("9007199254740993");
    assertThat(FirestoreValues.integerShown("not a number").asString()).isEqualTo("not a number");
    assertThat(FirestoreValues.text("ab😀cd", 3).asString()).as("never inside a surrogate pair")
        .isEqualTo("ab…‹6 characters›");
    JsonNode bytes = FirestoreValues.plain(TestCtx.JSON.readTree("{\"bytesValue\": \"" + "A".repeat(400) + "\"}"),
        DOCS, 100, 0);
    assertThat(bytes.asString()).startsWith("\\x000000").endsWith("…‹300 bytes›");
    assertThat(FirestoreValues.plain(TestCtx.JSON.readTree("{\"referenceValue\": \"projects/x/databases/y/documents"
        + "/a/b\"}"), DOCS, 100, 0).asString()).as("another database's reference stays whole")
        .isEqualTo("projects/x/databases/y/documents/a/b");
    assertThat(FirestoreValues.plain(TestCtx.JSON.readTree("{\"unknownValue\": 1}"), DOCS, 100, 0).isNull()).isTrue();
  }

  @Test
  void fieldPathsAreCanonical() throws Exception {
    assertThat(FieldPaths.canonical("address.city")).isEqualTo("address.city");
    assertThat(FieldPaths.canonical("first-name")).isEqualTo("`first-name`");
    assertThat(FieldPaths.canonical("`a.b`.c")).isEqualTo("`a.b`.c");
    assertThat(FieldPaths.canonical("`a\\`b`")).isEqualTo("`a\\`b`");
    assertThat(FieldPaths.canonical("`simple`")).isEqualTo("simple");
    assertThat(FieldPaths.canonical("__name__")).isEqualTo("__name__");
    assertThat(FieldPaths.segments("`a.b`.c")).containsExactly("a.b", "c");
    assertThat(FieldPaths.quote("we`ird\\")).isEqualTo("`we\\`ird\\\\`");
    assertThat(FieldPaths.quote("_ok9")).isEqualTo("_ok9");
    assertThat(FieldPaths.quote("9lives")).isEqualTo("`9lives`");
  }

  @Test
  void namesAreEncodedWholeAndCheckedStrictly() throws Exception {
    assertThat(Names.encode("a b-c_d.e~f")).isEqualTo("a%20b-c_d.e~f");
    assertThat(Names.encode("é/?#%:")).isEqualTo("%C3%A9%2F%3F%23%25%3A");
    assertThat(FirestorePath.parse("/users/alice").encoded()).isEqualTo("users/alice");
    assertThat(FirestorePath.parse("users/a b").name(DOCS)).isEqualTo(DOCS + "/users/a b");
    assertThat(RtdbPath.parse("/").url()).isEqualTo("/.json");
    assertThat(RtdbPath.parse("users/é").url()).isEqualTo("/users/%C3%A9.json");
    assertThat(Names.problem("ok", "", 10)).isNull();
    assertThat(Names.problem("x".repeat(11), "", 10)).contains("longer than 10 bytes");
    assertThat(Names.problem("a\uD800", "", 10)).contains("broken");
    assertThat(Names.problem("😀", "", 10)).as("a whole emoji is a character").isNull();
    assertThat(Names.problem("a b", "", 10)).contains("invisible");
    assertThat(Names.problem("a\u0085b", "", 10)).contains("control");
    assertThat(Names.shown("a‮b")).isEqualTo("a\\u202eb");
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 33; i++) {
      deep.append("/k");
    }
    assertThatThrownBy(() -> RtdbPath.parse(deep.toString())).hasMessageContaining("deeper than");
  }
}
