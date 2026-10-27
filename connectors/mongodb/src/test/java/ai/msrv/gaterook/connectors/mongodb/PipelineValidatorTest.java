package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The classification of {@code validator-cases.psv} (report 05 §3.3, §8): every stage of the allowlist, the refused
 * ones, {@code $out}/{@code $merge} and server-side JavaScript at any depth, namespaces with and without an allowlist,
 * arguments that don't parse. Through {@code classify} itself: the rule id, and never MUTATION.
 */
class PipelineValidatorTest {

  private static final MongoConnector CONNECTOR = new MongoConnector();

  @AfterAll
  static void close() {
    CONNECTOR.close();
  }

  static Stream<Arguments> cases() throws Exception {
    List<Arguments> out = new ArrayList<>();
    try (BufferedReader in = new BufferedReader(new InputStreamReader(
        PipelineValidatorTest.class.getClassLoader().getResourceAsStream("validator-cases.psv"),
        StandardCharsets.UTF_8))) {
      String line;
      int n = 0;
      while ((line = in.readLine()) != null) {
        n++;
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] parts = line.split(" \\| ");
        assertThat(parts).as("line " + n).hasSize(4);
        out.add(Arguments.of(n, parts[0].strip(), parts[1].strip(), parts[2].strip(), parts[3].strip()));
      }
    }
    return out.stream();
  }

  @ParameterizedTest(name = "line {0}: {1} → {4}")
  @MethodSource("cases")
  void classifies(int line, String tool, String arguments, String allowlist, String expected) {
    String list = allowlist.equals("-") ? "" : "\"advanced\":{\"namespaceAllowlist\":[\""
        + String.join("\",\"", allowlist.split(",")) + "\"]},";
    ConnectionConfig cfg = Fixtures.config(Env.DEV, "{" + list
        + "\"connection\":{\"uri\":\"mongodb://127.0.0.1:1/orders\"}}");

    Classification c = CONNECTOR.classify(Fixtures.call(tool, arguments), cfg, Fixtures.policy(Env.DEV));

    assertThat(c.kind()).as("never a mutation").isNotEqualTo(Classification.Kind.MUTATION);
    if (expected.equals("READ")) {
      assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    } else {
      assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(c.reason()).isEqualTo("dev.mongodb." + expected);
      assertThat(c.reason()).isNotBlank();
    }
  }

  @Test
  void theCorpusCoversEveryStageOfBothLists() throws Exception {
    List<String> lines = new ArrayList<>();
    cases().forEach(a -> lines.add((String) a.get()[2]));
    String all = String.join("\n", lines);
    for (String stage : PipelineValidator.ALLOWED) {
      assertThat(all).as(stage).contains("\"" + stage + "\"");
    }
    for (String stage : PipelineValidator.REFUSED.keySet()) {
      assertThat(all).as(stage).contains("\"" + stage + "\"");
    }
    assertThat(PipelineValidator.ALLOWED).hasSize(36);
    assertThat(PipelineValidator.REFUSED).hasSize(13);
  }

  @Test
  void theAgentLearnsWhyAndWhatInstead() {
    ConnectionConfig cfg = Fixtures.uri(Env.PROD, "mongodb://127.0.0.1:1/orders");

    Classification out = CONNECTOR.classify(Fixtures.call("mongo_aggregate",
        "{\"collection\":\"orders\",\"pipeline\":[{\"$facet\":{\"a\":[{\"$out\":\"x\"}]}}]}"), cfg,
        Fixtures.policy(Env.PROD));
    Classification js = CONNECTOR.classify(Fixtures.call("mongo_find",
        "{\"collection\":\"orders\",\"filter\":{\"$where\":\"1\"}}"), cfg, Fixtures.policy(Env.PROD));
    Classification op = CONNECTOR.classify(Fixtures.call("mongo_aggregate",
        "{\"collection\":\"orders\",\"pipeline\":[{\"$currentOp\":{}}]}"), cfg, Fixtures.policy(Env.PROD));
    Classification sys = CONNECTOR.classify(Fixtures.call("mongo_find",
        "{\"database\":\"admin\",\"collection\":\"system.users\"}"), cfg, Fixtures.policy(Env.PROD));

    assertThat(out.ruleId()).isEqualTo("prod.mongodb.write-stage");
    assertThat(out.reason()).isEqualTo("$out writes to a collection. mongo_aggregate is read-only.");
    assertThat(js.reason()).isEqualTo("Server-side JavaScript ($where) isn't allowed. Use $expr with aggregation "
        + "operators instead.");
    assertThat(op.reason()).isEqualTo("Stage $currentOp isn't allowed by the gateway: it shows other clients' "
        + "operations.");
    assertThat(sys.reason()).contains("admin is a system database");
  }
}
