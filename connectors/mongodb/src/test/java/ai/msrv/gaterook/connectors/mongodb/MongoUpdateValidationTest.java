package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.mongodb.Ejson.BadArgument;
import java.util.List;
import java.util.Optional;
import org.bson.BsonInt64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/** Update classification, payload fidelity and validation before any server is contacted. */
class MongoUpdateValidationTest {

  private static final MongoConnector CONNECTOR = new MongoConnector();
  private static final ConnectionConfig CONFIG = Fixtures.uri(Env.DEV, "mongodb://127.0.0.1:1/orders");

  @AfterAll
  static void close() {
    CONNECTOR.close();
  }

  private static ObjectNode args(String update) {
    return Fixtures.json("{\"collection\":\"orders\",\"filter\":{},\"update\":" + update + "}");
  }

  private static Classification classify(String tool, ObjectNode args) {
    return CONNECTOR.classify(Fixtures.call(tool, args.toString()), CONFIG, Fixtures.policy(Env.DEV));
  }

  private static ObjectNode without(ObjectNode args, String field) {
    ObjectNode copy = args.deepCopy();
    copy.remove(field);
    return copy;
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void updatesWithExplicitFiltersAreMutations(String tool) {
    ObjectNode args = args("{\"$set\":{\"status\":\"paid\"},\"$inc\":{\"version\":1}}");

    assertThat(classify(tool, args).kind()).isEqualTo(Classification.Kind.MUTATION);
    MongoArgs.Update parsed = (MongoArgs.Update) MongoArgs.parse(tool, args, "orders");
    assertThat(parsed.many()).isEqualTo(tool.equals("mongo_update_many"));
    assertThat(parsed.upsert()).isFalse();
    assertThat(parsed.filter()).isEmpty();
    assertThat(parsed.canonical().has("limit")).isFalse();
  }

  @Test
  void everySupportedModifierAndPipelineStageClassifiesAsAMutation() {
    for (String operator : PipelineValidator.UPDATE_OPERATORS) {
      assertThat(classify(MongoTools.UPDATE, args("{\"" + operator + "\":{}}")).kind())
          .as(operator).isEqualTo(Classification.Kind.MUTATION);
    }
    for (String stage : PipelineValidator.UPDATE_STAGES) {
      String body = stage.equals("$unset") ? "\"obsolete\""
          : stage.equals("$replaceWith") ? "\"$nested\""
          : stage.equals("$replaceRoot") ? "{\"newRoot\":\"$nested\"}" : "{\"field\":1}";
      assertThat(classify(MongoTools.UPDATE_MANY, args("[{\"" + stage + "\":" + body + "}]")).kind())
          .as(stage).isEqualTo(Classification.Kind.MUTATION);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void missingOrMalformedArgumentsNeverBecomeBroadUpdates(String tool) {
    ObjectNode valid = args("{\"$set\":{\"status\":\"paid\"}}");
    List<ObjectNode> invalid = List.of(
        without(valid, "filter"),
        valid.deepCopy().putNull("filter"),
        without(valid, "update"),
        valid.deepCopy().putNull("update"),
        valid.deepCopy().put("upsert", "true"),
        valid.deepCopy().putNull("upsert"),
        args("{}"),
        args("[]"),
        args("5"),
        args("{\"$set\":{}}").put("arrayFilters", "[]"),
        args("{\"$set\":{}}").set("arrayFilters", Ejson.JSON.readTree("[1]")),
        args("[{\"$set\":{\"status\":\"paid\"}}]").set("arrayFilters", Ejson.JSON.readTree("[]")));
    for (ObjectNode input : invalid) {
      assertThat(classify(tool, input).ruleId()).as(input.toString()).isEqualTo("dev.mongodb.bad-argument");
    }
    for (String readOption : List.of("limit", "skip", "projection", "sort")) {
      assertThat(classify(tool, valid.deepCopy().putNull(readOption)).ruleId())
          .as(readOption).isEqualTo("dev.mongodb.bad-argument");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void replacementsCommandsAndUnsupportedStagesAreDenied(String tool) {
    for (String update : List.of("{\"status\":\"paid\"}", "{\"$unknown\":{}}",
        "{\"$set\":{},\"status\":\"paid\"}")) {
      assertThat(classify(tool, args(update)).ruleId()).as(update)
          .isEqualTo("dev.mongodb.update-operator-not-allowed");
    }
    assertThat(classify(tool, args("{\"$set\":5}")).ruleId()).isEqualTo("dev.mongodb.bad-update");
    for (String pipeline : List.of("[{\"$lookup\":{\"from\":\"secrets\"}}]", "[{\"$match\":{}}]")) {
      assertThat(classify(tool, args(pipeline)).ruleId()).as(pipeline).isEqualTo("dev.mongodb.stage-not-allowed");
    }
    for (String pipeline : List.of("[{}]", "[{\"$set\":{},\"$unset\":\"obsolete\"}]", "[1]")) {
      assertThat(classify(tool, args(pipeline)).ruleId()).as(pipeline).isEqualTo("dev.mongodb.bad-stage");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void serverSideCodeAndCollectionOutputStagesAreDeniedAtEveryDepth(String tool) {
    List<ObjectNode> code = List.of(
        args("{\"$set\":{}}").set("filter", Ejson.JSON.readTree("{\"$where\":\"sleep(1000)\"}")),
        args("[{\"$set\":{\"x\":{\"$function\":{\"body\":\"f\",\"args\":[],\"lang\":\"js\"}}}}]"),
        args("{\"$set\":{\"nested\":{\"$accumulator\":{}}}}"),
        args("{\"$set\":{\"items.$[i].x\":1}}").set("arrayFilters",
            Ejson.JSON.readTree("[{\"i\":{\"$where\":\"true\"}}]")));
    for (ObjectNode input : code) {
      assertThat(classify(tool, input).ruleId()).as(input.toString()).isEqualTo("dev.mongodb.server-js");
    }
    for (String update : List.of("[{\"$out\":\"other\"}]", "[{\"$merge\":\"other\"}]",
        "{\"$set\":{\"x\":{\"$literal\":{\"$out\":\"other\"}}}}")) {
      assertThat(classify(tool, args(update)).ruleId()).isEqualTo("dev.mongodb.write-stage");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void databaseSelectionAndNamespaceAllowlistApplyToUpdates(String tool) {
    ObjectNode input = args("{\"$set\":{\"status\":\"paid\"}}");
    ConnectionConfig scoped = Fixtures.config(Env.DEV, """
        {"connection":{"uri":"mongodb://127.0.0.1:1/orders","databases":["orders"]},
         "advanced":{"namespaceAllowlist":["orders.orders"]}}
        """);
    for (ObjectNode denied : List.of(input.deepCopy().put("database", "inventory"),
        input.deepCopy().put("collection", "customers"), input.deepCopy().put("collection", "system.users"))) {
      Classification c = CONNECTOR.classify(Fixtures.call(tool, denied.toString()), scoped, Fixtures.policy(Env.DEV));
      assertThat(c.ruleId()).as(denied.toString()).isEqualTo("dev.mongodb.namespace-not-allowed");
    }
  }

  @Test
  void canonicalPayloadPreservesBsonValuesOptionsAndUpdateCardinality() {
    ObjectNode input = args("{\"$inc\":{\"counter\":{\"$numberLong\":\"9007199254740993\"}}}")
        .put("filter", "{_id: ObjectId('507f1f77bcf86cd799439011'), at: ISODate('2026-10-22T00:00:00Z')}")
        .put("upsert", true);
    input.set("arrayFilters", Ejson.JSON.readTree("[{\"i.count\":{\"$gte\":{\"$numberLong\":\"5\"}}}]"));
    MongoArgs.Update original = (MongoArgs.Update) MongoArgs.parse(MongoTools.UPDATE_MANY, input, "orders");
    MongoArgs.Update restored = (MongoArgs.Update) MongoArgs.parse(MongoTools.UPDATE_MANY, original.canonical(), null);

    assertThat(restored).isEqualTo(original);
    assertThat(restored.filter().get("_id").isObjectId()).isTrue();
    assertThat(restored.filter().get("at").isDateTime()).isTrue();
    assertThat(restored.update().asDocument().getDocument("$inc").get("counter"))
        .isEqualTo(new BsonInt64(9007199254740993L));
    assertThat(restored.upsert()).isTrue();
    assertThat(restored.many()).isTrue();
    assertThatThrownBy(() -> MongoArgs.parse(MongoTools.UPDATE, original.canonical(), null))
        .isInstanceOf(BadArgument.class).hasMessageContaining("doesn't match mongo_update");

    MongoArgs.Update pipeline = (MongoArgs.Update) MongoArgs.parse(MongoTools.UPDATE,
        args("[{\"$set\":{\"total\":{\"$add\":[\"$total\",{\"$numberLong\":\"1\"}]}}}]"), "orders");
    assertThat(MongoArgs.parse(MongoTools.UPDATE, pipeline.canonical(), null)).isEqualTo(pipeline);
  }

  @Test
  void executorValidatesApprovedUpdatesBeforeOpeningAClient() {
    ObjectNode allowed = args("{\"$set\":{\"status\":\"paid\"}}");
    ObjectNode denied = args("[{\"$set\":{\"x\":{\"$function\":{}}}}]");
    assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.call(MongoTools.UPDATE, allowed.toString()), CONFIG,
        Fixtures.Secrets.none(), Fixtures.Context.approved(Env.DEV, denied)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));

    ObjectNode one = MongoArgs.parse(MongoTools.UPDATE, allowed, "orders").canonical();
    assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.call(MongoTools.UPDATE_MANY, allowed.toString()), CONFIG,
        Fixtures.Secrets.none(), Fixtures.Context.approved(Env.DEV, one)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
    assertThat(CONNECTOR.openClients()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"mongo_update", "mongo_update_many"})
  void executorRequiresAuthorizationAndAPreparedPayloadBeforeOpeningAClient(String tool) {
    ObjectNode valid = args("{\"$set\":{\"status\":\"paid\"}}");
    ExecContext approvedWithoutPayload = mock(ExecContext.class);
    when(approvedWithoutPayload.approved()).thenReturn(true);
    when(approvedWithoutPayload.approvedPayload()).thenReturn(Optional.empty());

    for (ExecContext context : List.of(Fixtures.Context.of(Env.DEV), approvedWithoutPayload)) {
      assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.call(tool, valid.toString()), CONFIG,
          Fixtures.Secrets.none(), context))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
            assertThat(e.getMessage()).contains("prepared payload authorized by approval or Bypass");
          });
    }
    assertThat(CONNECTOR.openClients()).isZero();
  }
}
