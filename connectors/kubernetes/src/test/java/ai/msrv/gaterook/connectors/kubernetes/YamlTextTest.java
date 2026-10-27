package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The YAML of kubectl (sigs.k8s.io/yaml → go-yaml v2): the goldens are what {@code kubectl get -o yaml} prints; the
 * real comparison with kubectl runs in {@code KubeApiServerIT}.
 */
class YamlTextTest {

  @Test
  void writesAnObjectTheWayKubectlDoes() {
    JsonNode deployment = json("""
        {"apiVersion":"apps/v1","kind":"Deployment",
         "metadata":{"name":"api","namespace":"app-dev","labels":{"app":"api","version":"2"},
                     "annotations":{"note":"line one\\nline two"}},
         "spec":{"replicas":3,"template":{"spec":{"containers":[{"name":"web","image":"nginx:1.27",
           "args":["--port=8080",""],"env":[{"name":"FLAG","value":"true"},{"name":"EMPTY","value":""}],
           "ports":[{"containerPort":80,"protocol":"TCP"}],"resources":{},"volumeMounts":[]}]}}}}
        """);

    assertThat(YamlText.write(deployment)).isEqualTo("""
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          annotations:
            note: |-
              line one
              line two
          labels:
            app: api
            version: "2"
          name: api
          namespace: app-dev
        spec:
          replicas: 3
          template:
            spec:
              containers:
              - args:
                - --port=8080
                - ""
                env:
                - name: FLAG
                  value: "true"
                - name: EMPTY
                  value: ""
                image: nginx:1.27
                name: web
                ports:
                - containerPort: 80
                  protocol: TCP
                resources: {}
                volumeMounts: []
        """);
  }

  /** The value as JSON, and how go-yaml writes it after {@code v: } ({@code ⏎} is a line break). */
  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      "plain"                  | plain
      "a: b"                   | 'a: b'
      "it's"                   | it's
      "'quoted'"               | '''quoted'''
      "#comment"               | '#comment'
      "a #b"                   | 'a #b'
      "a#b"                    | a#b
      "- item"                 | '- item'
      "-item"                  | -item
      "*"                      | '*'
      "@user"                  | '@user'
      "%x"                     | '%x'
      "key:value"              | key:value
      "C:\\\\path"             | C:\\path
      "---"                    | '---'
      "<<"                     | <<
      "."                      | .
      "-"                      | '-'
      "?"                      | '?'
      "a\\"b"                  | a"b
      "\\"start"               | '"start'
      "1.2.3"                  | 1.2.3
      "1.5"                    | "1.5"
      ".5"                     | ".5"
      "1."                     | "1."
      "+1"                     | "+1"
      "0o17"                   | "0o17"
      "0x1F"                   | "0x1F"
      "0b101"                  | "0b101"
      "08"                     | "08"
      "1_000"                  | "1_000"
      "1e3"                    | "1e3"
      "12:30"                  | "12:30"
      "0xZZ"                   | 0xZZ
      "2024-01-15T10:00:00Z"   | "2024-01-15T10:00:00Z"
      "2024-1-5 3:04:05.123"   | "2024-1-5 3:04:05.123"
      "2024-01-15"             | "2024-01-15"
      "2024-01-15T10:00:00"    | 2024-01-15T10:00:00
      "2024-13-15"             | 2024-13-15
      "yes"                    | "yes"
      "Yes"                    | "Yes"
      "yES"                    | yES
      "y"                      | "y"
      "off"                    | "off"
      "null"                   | "null"
      "~"                      | "~"
      ".inf"                   | ".inf"
      "-.Inf"                  | "-.Inf"
      "Infinity"               | Infinity
      "tab\\there"             | "tab\\there"
      "trailing "              | 'trailing '
      " leading"               | ' leading'
      "emoji \\uD83D\\uDE00"   | "emoji \\U0001F600"
      "é ü 漢"                 | é ü 漢
      "bom\\uFEFF"             | "bom\\uFEFF"
      "bell\\u0007"            | "bell\\a"
      "nbsp\\u00A0x"           | nbsp\u00A0x
      "a \\nb"                 | "a \\nb"
      "line\\n"                | `|⏎  line`
      "two\\n\\n"              | `|+⏎  two⏎`
      " indented\\nnext"       | `|2-⏎   indented⏎  next`
      """)
  void quotesScalarsLikeGoYaml(String valueJson, String expected) {
    ObjectNode o = K8sTestKit.NODES.objectNode();
    o.set("v", K8sApi.JSON.readTree(valueJson));
    String want = "v: " + expected.replace("⏎", "\n").replace("\\u00A0", "\u00A0") + "\n";

    assertThat(YamlText.write(o)).isEqualTo(want);
  }

  @Test
  void foldsLongValuesAtTheFirstSpacePastColumn80() {
    String words = String.join(" ", java.util.Collections.nCopies(20, "word0123"));
    ObjectNode o = K8sTestKit.NODES.objectNode().put("description", words);
    o.putObject("nested").put("quoted", "#" + words);

    assertThat(YamlText.write(o)).isEqualTo("""
        description: word0123 word0123 word0123 word0123 word0123 word0123 word0123 word0123
          word0123 word0123 word0123 word0123 word0123 word0123 word0123 word0123 word0123
          word0123 word0123 word0123
        nested:
          quoted: '#word0123 word0123 word0123 word0123 word0123 word0123 word0123 word0123
            word0123 word0123 word0123 word0123 word0123 word0123 word0123 word0123 word0123
            word0123 word0123 word0123'
        """);
  }

  @Test
  void keysNeverFold() {
    String key = "k" + "x".repeat(100) + " y";
    ObjectNode o = K8sTestKit.NODES.objectNode().put(key, "v");

    assertThat(YamlText.write(o)).isEqualTo(key + ": v\n");
  }

  @Test
  void sortsKeysLikeGoYaml() {
    ObjectNode o = json("{\"b\":1,\"a10\":1,\"a9\":1,\"A\":1,\"_x\":1,\"1\":1,\"a09\":1}");

    List<String> keys = YamlText.write(o).lines().map(l -> l.substring(0, l.indexOf(':'))).toList();

    // A run of digits counts as a number, so "1" sorts after "_x" (0 for no digits), as in go-yaml.
    assertThat(keys).containsExactly("_x", "\"1\"", "A", "a9", "a09", "a10", "b");
  }

  @Test
  void writesFloatsLikeGo() {
    ObjectNode o = json("{\"a\":1.5,\"b\":1.0,\"c\":1e-7,\"d\":1e21,\"e\":123456789.0,\"f\":-0.25,\"g\":0.0001,"
        + "\"h\":100000.5,\"i\":12345678901234567890}");

    assertThat(YamlText.write(o)).isEqualTo("""
        a: 1.5
        b: 1
        c: 1e-07
        d: 1e+21
        e: 1.23456789e+08
        f: -0.25
        g: 0.0001
        h: 100000.5
        i: 12345678901234567890
        """);
  }

  /** Whatever the strings, the YAML reads back as the same tree (folding and quoting keep the value). */
  @Test
  void roundTripsRandomStrings() throws Exception {
    Random random = new Random(42);
    // No U+2028: YAML 1.1 (go-yaml) reads it as a line break, YAML 1.2 doesn't; the emitter writes it as text.
    String alphabet = " ab:#-'\"\\\t\n.0123456789eExX_+{}[],&*!|>%@`?~\u00e9\u00a0\uFEFF\u0007";
    for (int round = 0; round < 2000; round++) {
      ObjectNode o = K8sTestKit.NODES.objectNode();
      List<String> values = new ArrayList<>();
      for (int k = 0; k < 4; k++) {
        StringBuilder s = new StringBuilder();
        int n = random.nextInt(random.nextBoolean() ? 6 : 120);
        for (int i = 0; i < n; i++) {
          s.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        values.add(s.toString());
        o.put("k" + k, s.toString());
      }
      o.putArray("list").add(values.get(0)).add(values.get(1));
      String yaml = YamlText.write(o);
      JsonNode back = YamlTrees.documents(yaml, 1 << 20).get(0);
      assertThat(back).as("round %d:%n%s", round, yaml).isEqualTo(o);
    }
  }
}
