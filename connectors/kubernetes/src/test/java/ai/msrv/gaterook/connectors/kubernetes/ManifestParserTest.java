package ai.msrv.gaterook.connectors.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connectors.kubernetes.ManifestParser.ManifestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Manifests and kubeconfigs are read with everything that makes YAML dangerous turned off (report 08 §3.4). */
class ManifestParserTest {

  @Test
  void readsYamlAndJsonDocuments() throws Exception {
    List<ObjectNode> objects = ManifestParser.parse("""
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: api
          labels: {app: api, enabled: on}
        spec:
          replicas: 3
        ---
        ---
        {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "cfg"}, "data": {"n": "1"}}
        """);

    assertThat(objects).hasSize(2);
    assertThat(objects.get(0).at("/spec/replicas").asInt()).isEqualTo(3);
    // YAML 1.2 core schema: "on" stays a string, as JSON and kubectl read it.
    assertThat(objects.get(0).at("/metadata/labels/enabled").isString()).isTrue();
    assertThat(objects.get(1).at("/data/n").asString()).isEqualTo("1");
  }

  @Test
  void aliasesOfAnyKindAreRefused() {
    String laughs = """
        apiVersion: v1
        kind: ConfigMap
        metadata: {name: lol}
        data:
          a: &a ["lol","lol","lol","lol","lol","lol","lol","lol","lol"]
          b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]
          c: [*b,*b,*b,*b,*b,*b,*b,*b,*b]
        """;
    assertThatThrownBy(() -> ManifestParser.parse(laughs)).isInstanceOf(ManifestException.class)
        .hasMessageContaining("anchor (&name)").hasMessageContaining("line 5");
    assertThatThrownBy(() -> ManifestParser.parse("a: &x 1\nb: *x\n")).hasMessageContaining("anchor");
    assertThatThrownBy(() -> ManifestParser.parse("b: *x\n")).isInstanceOf(ManifestException.class);
  }

  @Test
  void duplicateKeysAreRefusedWithoutQuotingTheText() {
    String twice = """
        apiVersion: v1
        kind: ConfigMap
        metadata: {name: cfg}
        data:
          password: cnry-first-value
          password: cnry-second-value
        """;

    assertThatThrownBy(() -> ManifestParser.parse(twice)).isInstanceOfSatisfying(ManifestException.class, e -> {
      assertThat(e.getMessage()).contains("duplicate key").contains("line 6");
      assertThat(e.getMessage()).doesNotContain("cnry-");
    });
  }

  @Test
  void customTagsAreRefused() {
    assertThatThrownBy(() -> ManifestParser.parse("apiVersion: v1\nkind: !!java.lang.Runtime x\n"))
        .isInstanceOf(ManifestException.class).hasMessageContaining("custom tag");
    assertThatThrownBy(() -> ManifestParser.parse("apiVersion: v1\nkind: !Ref x\n"))
        .isInstanceOf(ManifestException.class).hasMessageContaining("custom tag");
  }

  @Test
  void aSyntaxErrorSaysWhereButNotWhat() {
    assertThatThrownBy(() -> ManifestParser.parse("apiVersion: v1\nkind: [cnry-unclosed\n"))
        .isInstanceOfSatisfying(ManifestException.class, e -> {
          assertThat(e.getMessage()).startsWith("The YAML can't be parsed").contains("line");
          assertThat(e.getMessage()).doesNotContain("cnry-");
        });
  }

  @Test
  void eachDocumentMustBeOneNamedObject() {
    assertThatThrownBy(() -> ManifestParser.parse("- a\n- b\n")).hasMessageContaining("isn't a Kubernetes object");
    assertThatThrownBy(() -> ManifestParser.parse("kind: ConfigMap\nmetadata: {name: x}\n"))
        .hasMessageContaining("needs apiVersion and kind");
    assertThatThrownBy(() -> ManifestParser.parse("apiVersion: v1\nkind: List\nitems: []\n"))
        .hasMessageContaining("separate documents");
    assertThatThrownBy(() -> ManifestParser.parse("apiVersion: v1\nkind: Pod\nmetadata: {generateName: p-}\n"))
        .hasMessageContaining("generateName");
    assertThatThrownBy(() -> ManifestParser.parse("apiVersion: v1\nkind: Pod\nmetadata: {name: Bad_Name}\n"))
        .hasMessageContaining("metadata.name");
    assertThatThrownBy(() -> ManifestParser.parse(
        "apiVersion: v1\nkind: Pod\nmetadata: {name: p, resourceVersion: \"12\"}\n"))
        .hasMessageContaining("not an object read from the cluster");
    assertThatThrownBy(() -> ManifestParser.parse(
        "apiVersion: v1\nkind: Pod\nmetadata: {name: p, namespace: ../x}\n"))
        .hasMessageContaining("invalid metadata.namespace");
    assertThatThrownBy(() -> ManifestParser.parse(
        "apiVersion: v1\nkind: Pod\nmetadata: {name: p}\n---\napiVersion: v1\nkind: Pod\nmetadata: {name: p}\n"))
        .hasMessageContaining("Documents 1 and 2 are the same object");
    assertThatThrownBy(() -> ManifestParser.parse("  \n")).hasMessageContaining("empty");
    assertThatThrownBy(() -> ManifestParser.parse("---\n---\n")).hasMessageContaining("no object");
  }

  @Test
  void theLastAppliedAnnotationIsRefusedBecauseNoDiffShowsIt() throws Exception {
    String manifest = """
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: cfg
          annotations:
            kubectl.kubernetes.io/last-applied-configuration: '{"data":{"keep":"x","limits":"dropped next apply"}}'
        data: {keep: x}
        """;
    // What the approver would read and what holds the approved apply to its dry run both leave the annotation out:
    // an apply that only sets it would look like no change at all.
    ObjectNode object = (ObjectNode) YamlTrees.documents(manifest, 10_000).get(0);
    ObjectNode without = object.deepCopy();
    ((ObjectNode) without.get("metadata")).remove("annotations");
    assertThat(ObjectNormalizer.forDiff(object)).isEqualTo(ObjectNormalizer.forDiff(without));
    assertThat(ObjectNormalizer.forHash(object)).isEqualTo(ObjectNormalizer.forHash(without));

    assertThatThrownBy(() -> ManifestParser.parse(manifest)).isInstanceOfSatisfying(ManifestException.class,
        e -> assertThat(e.getMessage()).contains("ConfigMap/cfg").contains(ObjectNormalizer.LAST_APPLIED));
  }

  @Test
  void atMostTwentyObjectsPerCall() {
    StringBuilder m = new StringBuilder();
    for (int i = 0; i < 21; i++) {
      m.append("apiVersion: v1\nkind: ConfigMap\nmetadata: {name: c").append(i).append("}\n---\n");
    }
    assertThatThrownBy(() -> ManifestParser.parse(m.toString())).hasMessageContaining("21 objects");
  }

  @Test
  void numbersJsonCantHoldAreRefused() {
    assertThatThrownBy(() -> ManifestParser.parse("apiVersion: v1\nkind: ConfigMap\nx: .nan\n"))
        .hasMessageContaining("JSON can't hold");
  }

  @Test
  void deepNestingIsRefused() {
    String deep = "a: ".repeat(1) + "[".repeat(120) + "]".repeat(120) + "\n";
    assertThatThrownBy(() -> ManifestParser.parse(deep)).isInstanceOf(ManifestException.class);
  }
}
