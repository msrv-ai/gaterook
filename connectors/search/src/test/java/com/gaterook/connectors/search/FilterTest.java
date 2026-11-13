package com.gaterook.connectors.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The one filter language, read and written in each store's syntax: no value of an agent becomes syntax. */
class FilterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Filter.Node parse(String json) throws Filter.Invalid {
    return Filter.parse(JSON.readTree(json));
  }

  private static final String RICH = "{\"genre\": \"drama\", \"year\": {\"$gte\": 2020, \"$lt\": 2025}, "
      + "\"$or\": [{\"lang\": {\"$in\": [\"en\", \"de\"]}}, {\"score\": {\"$gt\": 4.5}}], "
      + "\"draft\": {\"$ne\": true}, \"tag\": {\"$nin\": [\"x\"]}}";

  @Test
  void mongoFormIsReadIntoOneTree() throws Exception {
    Filter.Node node = parse(RICH);
    assertThat(node).isInstanceOf(Filter.And.class);
    assertThat(Filter.fields(node)).containsExactly("genre", "year", "lang", "score", "draft", "tag");
    assertThat(Filter.mongo(node, true).toString()).isEqualTo("{\"$and\":[{\"genre\":{\"$eq\":\"drama\"}},"
        + "{\"year\":{\"$gte\":2020}},{\"year\":{\"$lt\":2025}},{\"$or\":[{\"lang\":{\"$in\":[\"en\",\"de\"]}},"
        + "{\"score\":{\"$gt\":4.5}}]},{\"draft\":{\"$ne\":true}},{\"tag\":{\"$nin\":[\"x\"]}}]}");
    assertThat(parse("{}")).isNull();
    assertThat(Filter.parse(null)).isNull();
    assertThat(parse("{\"a\": 1}")).isEqualTo(new Filter.Cmp("a", Filter.Op.EQ, new Filter.Int(1)));
  }

  @Test
  void whatIsntTheLanguageIsRefused() {
    for (String bad : new String[] {"[1]", "{\"$where\": \"1\"}", "{\"a\": {\"$regex\": \"x\"}}", "{\"a\": null}",
        "{\"a\": [1, 2]}", "{\"a\": {\"$in\": []}}", "{\"a\": {\"$in\": [1, \"x\"]}}", "{\"a\": {\"$gt\": true}}",
        "{\"a\": {\"$exists\": 1}}", "{\"$or\": []}", "{\"$and\": [{}]}", "{\"a\": {}}", "{\"\": 1}",
        "{\"a\\u0000\": 1}", "{\"a\": \"x\\ny\"}"}) {
      assertThatThrownBy(() -> parse(bad)).as(bad).isInstanceOf(Filter.Invalid.class);
    }
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      deep.append("{\"$and\": [");
    }
    deep.append("{\"a\": 1}");
    for (int i = 0; i < 8; i++) {
      deep.append("]}");
    }
    assertThatThrownBy(() -> parse(deep.toString())).hasMessageContaining("nests at most");
  }

  @Test
  void qdrantGetsMustShouldAndMustNot() throws Exception {
    JsonNode q = QdrantBackend.filter(parse(RICH));
    assertThat(q.toString()).isEqualTo("{\"must\":[{\"key\":\"genre\",\"match\":{\"value\":\"drama\"}},"
        + "{\"key\":\"year\",\"range\":{\"gte\":2020}},{\"key\":\"year\",\"range\":{\"lt\":2025}},"
        + "{\"should\":[{\"key\":\"lang\",\"match\":{\"any\":[\"en\",\"de\"]}},{\"key\":\"score\",\"range\":"
        + "{\"gt\":4.5}}]},{\"must_not\":[{\"key\":\"draft\",\"match\":{\"value\":true}}]},"
        + "{\"key\":\"tag\",\"match\":{\"except\":[\"x\"]}}]}");
    assertThat(QdrantBackend.filter(parse("{\"note\": {\"$exists\": false}}")).toString())
        .isEqualTo("{\"must\":[{\"is_empty\":{\"key\":\"note\"}}]}");
  }

  @Test
  void milvusGetsAnExpressionWithQuotedText() throws Exception {
    assertThat(MilvusBackend.expression(parse(RICH))).isEqualTo("(genre == \"drama\") and (year >= 2020) and "
        + "(year < 2025) and ((lang in [\"en\", \"de\"]) or (score > 4.5)) and (draft != true) and "
        + "(tag not in [\"x\"])");
    assertThat(MilvusBackend.expression(parse("{\"city\": \"Ber\\\"lin\\\\\"}")))
        .isEqualTo("city == \"Ber\\\"lin\\\\\"");
  }

  @Test
  void meilisearchGetsItsFilterSyntax() throws Exception {
    assertThat(MeilisearchBackend.filter(parse(RICH))).isEqualTo("(genre = \"drama\") AND (year >= 2020) AND "
        + "(year < 2025) AND ((lang IN [\"en\", \"de\"]) OR (score > 4.5)) AND (draft != true) AND "
        + "(tag NOT IN [\"x\"])");
    assertThat(MeilisearchBackend.filter(parse("{\"a\": {\"$exists\": true}, \"b\": \"x\\\" OR 1=1\"}")))
        .isEqualTo("(a EXISTS) AND (b = \"x\\\" OR 1=1\")");
  }

  @Test
  void typesenseGetsBackticksAndNeverABacktickValue() throws Exception {
    assertThat(TypesenseBackend.filter(parse(RICH))).isEqualTo("(genre:=`drama`) && (year:>=2020) && (year:<2025) "
        + "&& ((lang:=[`en`,`de`]) || (score:>4.5)) && (draft:!=true) && (tag:!=[`x`])");
    TypesenseBackend ts = new TypesenseBackend();
    assertThatThrownBy(() -> ts.checkFilter(parse("{\"a\": \"x` || id:*\"}"))).hasMessageContaining("backtick");
    assertThatThrownBy(() -> ts.checkFilter(parse("{\"a\": {\"$exists\": true}}"))).hasMessageContaining("$exists");
  }

  @Test
  void algoliaGetsFacetAndNumericFiltersAndItsLimits() throws Exception {
    assertThat(AlgoliaBackend.filters(parse("{\"genre\": \"drama\", \"year\": {\"$gte\": 2020}, \"$or\": [{\"lang\": "
        + "\"en\"}, {\"lang\": \"de\"}], \"draft\": {\"$ne\": true}, \"tag\": {\"$nin\": [\"x\", \"y\"]}}")))
        .isEqualTo("genre:\"drama\" AND year >= 2020 AND (lang:\"en\" OR lang:\"de\") AND NOT draft:true AND "
            + "NOT tag:\"x\" AND NOT tag:\"y\"");
    AlgoliaBackend algolia = new AlgoliaBackend();
    assertThatThrownBy(() -> algolia.checkFilter(parse("{\"$or\": [{\"a\": 1, \"b\": 2}, {\"c\": 3}]}")))
        .hasMessageContaining("$and inside an $or");
    assertThatThrownBy(() -> algolia.checkFilter(parse("{\"a\": {\"$gt\": \"m\"}}")))
        .hasMessageContaining("compares numbers");
    assertThatThrownBy(() -> algolia.checkFilter(parse("{\"a\": {\"$exists\": true}}")))
        .hasMessageContaining("exists");
  }

  @Test
  void azureGetsODataWithDoubledQuotes() throws Exception {
    assertThat(AzureSearchBackend.odata(parse(RICH), null)).isEqualTo("(genre eq 'drama') and (year ge 2020) and "
        + "(year lt 2025) and ((search.in(lang, 'en|de', '|')) or (score gt 4.5)) and (draft ne true) and "
        + "(not search.in(tag, 'x', '|'))");
    assertThat(AzureSearchBackend.odata(parse("{\"owner\": \"O'Brien\", \"tags\": {\"$in\": [\"a|b\", \"c\"]}}"),
        null)).isEqualTo("(owner eq 'O''Brien') and (search.in(tags, 'a|b,c', ','))");
    assertThat(AzureSearchBackend.odata(parse("{\"n\": {\"$in\": [1, 2]}, \"x\": {\"$exists\": false}}"), null))
        .isEqualTo("((n eq 1 or n eq 2)) and (x eq null)");
  }

  @Test
  void weaviateGetsATypedWhere() throws Exception {
    WeaviateBackend.Schema schema = WeaviateBackend.schemaOf("Article", JSON.readTree("{\"class\": \"Article\", "
        + "\"properties\": [{\"name\": \"title\", \"dataType\": [\"text\"]}, {\"name\": \"year\", \"dataType\": "
        + "[\"int\"]}, {\"name\": \"score\", \"dataType\": [\"number\"]}, {\"name\": \"published\", \"dataType\": "
        + "[\"boolean\"]}, {\"name\": \"tags\", \"dataType\": [\"text[]\"]}]}"));
    assertThat(WeaviateBackend.where(parse("{\"title\": \"A \\\"B\\\"\", \"year\": {\"$gte\": 2020}, \"score\": "
        + "{\"$gt\": 4}, \"tags\": {\"$nin\": [\"x\"]}, \"$or\": [{\"published\": true}, {\"x\": {\"$exists\": "
        + "false}}]}"), schema)).isEqualTo("{operator: And, operands: [{path: [\"title\"], operator: Equal, "
        + "valueText: \"A \\\"B\\\"\"}, {path: [\"year\"], operator: GreaterThanEqual, valueInt: 2020}, "
        + "{path: [\"score\"], operator: GreaterThan, valueNumber: 4.0}, {operator: Not, operands: [{path: "
        + "[\"tags\"], operator: ContainsAny, valueText: [\"x\"]}]}, {operator: Or, operands: [{path: "
        + "[\"published\"], operator: Equal, valueBoolean: true}, {path: [\"x\"], operator: IsNull, "
        + "valueBoolean: true}]}]}");
    assertThatThrownBy(() -> WeaviateBackend.where(parse("{\"year\": 2.5}"), schema))
        .hasMessageContaining("integers");
  }

  @Test
  void fieldNamesFollowEachStoresRules() {
    assertThatThrownBy(() -> new MilvusBackend().checkFilter(parse("{\"a b\": 1}"))).isInstanceOf(
        Filter.Invalid.class);
    assertThatThrownBy(() -> new WeaviateBackend().checkFilter(parse("{\"a-b\": 1}"))).isInstanceOf(
        Filter.Invalid.class);
    assertThatThrownBy(() -> new AzureSearchBackend().checkFilter(parse("{\"a eq 1 or x\": 1}"))).isInstanceOf(
        Filter.Invalid.class);
    assertThatThrownBy(() -> new ChromaBackend().checkFilter(parse("{\"a\": {\"$exists\": true}}")))
        .hasMessageContaining("$exists");
  }
}
