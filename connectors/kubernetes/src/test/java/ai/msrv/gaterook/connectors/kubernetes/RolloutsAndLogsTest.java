package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.json;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Rollout status and impact (report 08 §3.1, §3.6), the log ring buffer (§3.2) and kubectl's ages. */
class RolloutsAndLogsTest {

  @Test
  void aDeploymentRolloutReadsLikeKubectlRolloutStatus() {
    ObjectNode d = json("""
        {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"api","generation":5},
         "spec":{"replicas":3},
         "status":{"observedGeneration":5,"replicas":4,"updatedReplicas":3,"readyReplicas":3,"availableReplicas":3}}
        """);

    assertThat(Rollouts.status(d).path("summary").asString()).isEqualTo("1 old replicas pending termination");
    d.withObject("/status").put("replicas", 3);
    assertThat(Rollouts.status(d).path("summary").asString()).isEqualTo("3/3 complete");
    assertThat(Rollouts.status(d).path("complete").asBoolean()).isTrue();
    d.withObject("/metadata").put("generation", 6);
    assertThat(Rollouts.status(d).path("summary").asString())
        .isEqualTo("waiting for the spec update to be observed");
    d.withObject("/metadata").put("generation", 5);
    d.withObject("/status").put("updatedReplicas", 1);
    assertThat(Rollouts.status(d).path("summary").asString()).isEqualTo("1 of 3 new replicas updated");
    d.withObject("/status").putArray("conditions").addObject().put("type", "Progressing").put("status", "False")
        .put("reason", "ProgressDeadlineExceeded");
    assertThat(Rollouts.status(d).path("summary").asString()).isEqualTo("exceeded its progress deadline");
    assertThat(Rollouts.status(json("{\"apiVersion\":\"v1\",\"kind\":\"Pod\"}"))).isNull();
  }

  @Test
  void aStatefulSetAndADaemonSetRollout() {
    JsonNode sts = json("""
        {"apiVersion":"apps/v1","kind":"StatefulSet","metadata":{"generation":2},
         "spec":{"replicas":3,"updateStrategy":{"type":"RollingUpdate","rollingUpdate":{"partition":1}}},
         "status":{"observedGeneration":2,"readyReplicas":3,"updatedReplicas":2}}
        """);
    assertThat(Rollouts.status(sts).path("summary").asString()).isEqualTo("partitioned rollout complete (2 new pods)");

    JsonNode ds = json("""
        {"apiVersion":"apps/v1","kind":"DaemonSet","metadata":{"generation":1},"spec":{},
         "status":{"observedGeneration":1,"desiredNumberScheduled":4,"updatedNumberScheduled":4,"numberAvailable":3}}
        """);
    assertThat(Rollouts.status(ds).path("summary").asString()).isEqualTo("3 of 4 updated pods available");
  }

  /** maxSurge rounds up, maxUnavailable down, like the deployment controller. */
  @ParameterizedTest(name = "{0} of {2} replicas, up={3} → {4}")
  @CsvSource({"25%, 25%, 4, true, 1", "25%, 25%, 3, true, 1", "25%, 25%, 3, false, 0", "2, 25%, 10, true, 2",
      "50%, 0, 5, true, 3", "'', 25%, 8, false, 2"})
  void fencepost(String value, String dflt, int replicas, boolean up, int expected) {
    JsonNode v = value.isEmpty() ? K8sTestKit.NODES.missingNode() : value.endsWith("%")
        ? K8sTestKit.NODES.stringNode(value) : K8sTestKit.NODES.numberNode(Integer.parseInt(value));

    assertThat(Rollouts.fencepost(v, dflt, replicas, up)).isEqualTo(expected);
  }

  @Test
  void theImpactOfARestartIsWhatTheApproverNeeds() {
    JsonNode d = json("""
        {"kind":"Deployment","metadata":{"name":"api","labels":{"argocd.argoproj.io/instance":"api"}},
         "spec":{"replicas":3,"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxSurge":1,"maxUnavailable":0}},
                 "template":{"spec":{"containers":[{"name":"api","image":"registry/api:1.4.2"}]}}}}
        """);

    Rollouts.Impact impact = Rollouts.restart(d, "app-dev");

    assertThat(impact.fields()).extracting(Field::label, Field::value).containsExactly(
        org.assertj.core.groups.Tuple.tuple("Workload", "deployment/api in app-dev"),
        org.assertj.core.groups.Tuple.tuple("Pods restarted", "3, up to 1 new at a time, at most 0 unavailable"),
        org.assertj.core.groups.Tuple.tuple("Expected downtime", "None (rolling, max unavailable 0)"),
        org.assertj.core.groups.Tuple.tuple("Image", "registry/api:1.4.2, unchanged"));
    assertThat(impact.warnings()).containsExactly("Managed by Argo CD. It may revert the change.");

    JsonNode recreate = json("{\"kind\":\"Deployment\",\"metadata\":{\"name\":\"w\"},"
        + "\"spec\":{\"replicas\":2,\"strategy\":{\"type\":\"Recreate\"}}}");
    assertThat(Rollouts.restart(recreate, "ns").fields()).extracting(Field::value).contains("Yes (Recreate)");
    JsonNode onDelete = json("{\"kind\":\"StatefulSet\",\"metadata\":{\"name\":\"db\"},"
        + "\"spec\":{\"updateStrategy\":{\"type\":\"OnDelete\"}}}");
    assertThat(Rollouts.restart(onDelete, "ns").warnings()).singleElement().asString().contains("OnDelete");
  }

  // ── logs ────────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theRingBufferKeepsTheNewestBytesFromALineStart() throws IOException {
    StringBuilder log = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      log.append("line ").append(i).append('\n');
    }
    byte[] bytes = log.toString().getBytes(StandardCharsets.UTF_8);

    LogTail.Tail t = LogTail.read(new ByteArrayInputStream(bytes), 100, () -> false);

    String text = new String(t.bytes(), StandardCharsets.UTF_8);
    assertThat(t.headCut()).isTrue();
    assertThat(t.total()).isEqualTo(bytes.length);
    assertThat(text).endsWith("line 999\n").startsWith("line ");
    assertThat(text.length()).isLessThanOrEqualTo(100);

    LogTail.Tail small = LogTail.read(new ByteArrayInputStream("a\nb\n".getBytes(StandardCharsets.UTF_8)), 100,
        () -> false);
    assertThat(new String(small.bytes(), StandardCharsets.UTF_8)).isEqualTo("a\nb\n");
    assertThat(small.headCut()).isFalse();
  }

  @Test
  void aStreamClosedAtTheDeadlineAnswersWithWhatArrived() throws IOException {
    boolean[] stopped = {false};
    InputStream in = new InputStream() {
      private int sent;

      @Override
      public int read() throws IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (sent++ < 3) {
          byte[] line = ("chunk " + sent + "\n").getBytes(StandardCharsets.UTF_8);
          System.arraycopy(line, 0, b, off, line.length);
          return line.length;
        }
        stopped[0] = true;
        throw new IOException("closed");
      }
    };

    LogTail.Tail t = LogTail.read(in, 1024, () -> stopped[0]);

    assertThat(new String(t.bytes(), StandardCharsets.UTF_8)).isEqualTo("chunk 1\nchunk 2\nchunk 3\n");
    assertThat(t.stopped()).isTrue();
  }

  @Test
  void labelSelectorsAreBuiltLikeKubectl() {
    JsonNode s = json("""
        {"matchLabels":{"app":"api","tier":"web"},"matchExpressions":[
          {"key":"env","operator":"In","values":["dev","stage"]},{"key":"canary","operator":"DoesNotExist"}]}
        """);

    assertThat(LogsTool.selector(s)).isEqualTo("app=api,tier=web,env in (dev,stage),!canary");
    assertThat(LogsTool.selector(json("{\"app\":\"legacy\"}"))).isEqualTo("app=legacy");
  }

  @ParameterizedTest(name = "{0} s → {1}")
  @CsvSource({"0, 0s", "45, 45s", "119, 119s", "200, 3m20s", "600, 10m", "2460, 41m", "18720, 5h12m", "72000, 20h",
      "273600, 3d4h", "3542400, 41d", "65664000, 2y30d", "315360000, 10y"})
  void agesLikeKubectl(long seconds, String expected) {
    assertThat(K8sText.age(Duration.ofSeconds(seconds))).isEqualTo(expected);
  }
}
