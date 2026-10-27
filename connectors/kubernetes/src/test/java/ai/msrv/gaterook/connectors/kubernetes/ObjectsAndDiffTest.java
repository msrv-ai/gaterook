package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** What the agent and the approver see of an object, and the diff of an apply (report 08 §3.1, §3.4). */
class ObjectsAndDiffTest {

  private static final String SECRET = """
      {"apiVersion":"v1","kind":"Secret","type":"Opaque",
       "metadata":{"name":"db","namespace":"app-dev","uid":"u1","resourceVersion":"7",
         "annotations":{"kubectl.kubernetes.io/last-applied-configuration":
           "{\\"apiVersion\\":\\"v1\\",\\"kind\\":\\"Secret\\",\\"stringData\\":{\\"password\\":\\"cnry-plain\\"}}",
           "owner":"team-a"},
         "managedFields":[{"manager":"kubectl","operation":"Apply"}]},
       "data":{"password":"Y25yeS1wbGFpbg==","user":"YWRtaW4="},
       "stringData":{"token":"cnry-token"}}
      """;

  @Test
  void aSecretReachesTheAgentWithItsKeysButNoValues() {
    ObjectNode agent = ObjectNormalizer.forAgent(json(SECRET));

    assertThat(agent.toString()).doesNotContain("cnry-", "Y25ye", "YWRta", "managedFields",
        "last-applied-configuration");
    assertThat(agent.at("/data/password").asString()).isEqualTo("‹masked›");
    assertThat(agent.at("/data/user").asString()).isEqualTo("‹masked›");
    assertThat(agent.at("/stringData/token").asString()).isEqualTo("‹masked›");
    assertThat(agent.at("/metadata/annotations/owner").asString()).isEqualTo("team-a");
    assertThat(agent.at("/metadata/resourceVersion").asString()).isEqualTo("7");
  }

  @Test
  void lastAppliedIsRemovedFromEveryKind() {
    ObjectNode cm = json("""
        {"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"c","annotations":{
          "kubectl.kubernetes.io/last-applied-configuration":"{\\"data\\":{\\"k\\":\\"cnry-copy\\"}}"}},
         "data":{"k":"v"}}
        """);

    ObjectNode agent = ObjectNormalizer.forAgent(cm);

    assertThat(agent.toString()).doesNotContain("cnry-", "annotations");
    assertThat(agent.at("/data/k").asString()).isEqualTo("v");
    // A ConfigMap is not a Secret: its values stay.
    assertThat(ObjectNormalizer.isSecret(cm)).isFalse();
  }

  @Test
  void theHashIgnoresWhatTheServerSetsByItself() {
    ObjectNode a = json("""
        {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"api","resourceVersion":"10","generation":3,
         "uid":"u","creationTimestamp":"2026-10-01T00:00:00Z"},"spec":{"replicas":2},"status":{"replicas":2}}
        """);
    ObjectNode b = json("""
        {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"api","resourceVersion":"11","generation":4,
         "uid":"u","creationTimestamp":"2026-10-01T00:00:00Z"},"spec":{"replicas":2},"status":{"replicas":1}}
        """);
    ObjectNode c = b.deepCopy();
    c.withObject("/spec").put("replicas", 3);

    assertThat(K8sText.sha256(ObjectNormalizer.forHash(a))).isEqualTo(K8sText.sha256(ObjectNormalizer.forHash(b)));
    assertThat(K8sText.sha256(ObjectNormalizer.forHash(a))).isNotEqualTo(K8sText.sha256(ObjectNormalizer.forHash(c)));
    // The diff keeps them, as kubectl diff does.
    assertThat(ObjectNormalizer.forDiff(a).at("/metadata/resourceVersion").asString()).isEqualTo("10");
  }

  @Test
  void theDiffOfAChangeIsUnifiedWithThreeLinesOfContext() {
    ObjectNode live = json("""
        {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"api","namespace":"app-dev",
         "generation":4,"managedFields":[{"manager":"kubectl"}]},
         "spec":{"replicas":2,"template":{"spec":{"containers":[{"name":"api","image":"api:1.4.2"}]}}}}
        """);
    ObjectNode after = live.deepCopy();
    after.withObject("/metadata").put("generation", 5);
    after.withObject("/spec").put("replicas", 3);
    ((ObjectNode) after.at("/spec/template/spec/containers/0")).put("image", "api:1.4.3");

    YamlDiff d = YamlDiff.of("deployment/api", live, after);

    assertThat(d.text()).isEqualTo("""
        --- deployment/api (live)
        +++ deployment/api (after apply)
        @@ -1,13 +1,13 @@
         apiVersion: apps/v1
         kind: Deployment
         metadata:
        -  generation: 4
        +  generation: 5
           name: api
           namespace: app-dev
         spec:
        -  replicas: 2
        +  replicas: 3
           template:
             spec:
               containers:
        -      - image: api:1.4.2
        +      - image: api:1.4.3
                 name: api""");
    assertThat(d.added()).isEqualTo(3);
    assertThat(d.removed()).isEqualTo(3);
  }

  @Test
  void aNewObjectIsAllAdded() {
    ObjectNode after = json("{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"c\"}}");

    YamlDiff d = YamlDiff.of("configmap/c", null, after);

    assertThat(d.lines()).containsExactly("--- configmap/c (live)", "+++ configmap/c (after apply)",
        "@@ -0,0 +1,4 @@", "+apiVersion: v1", "+kind: ConfigMap", "+metadata:", "+  name: c");
  }

  @Test
  void hunksAreGnuDiffs() {
    List<String> twenty = java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> "l" + i).toList();
    List<String> changed = new java.util.ArrayList<>(twenty);
    changed.set(1, "X");
    changed.set(17, "Y");

    assertThat(YamlDiff.of("o", twenty, changed).lines()).containsExactly("--- o (live)", "+++ o (after apply)",
        "@@ -1,5 +1,5 @@", " l1", "-l2", "+X", " l3", " l4", " l5",
        "@@ -15,6 +15,6 @@", " l15", " l16", " l17", "-l18", "+Y", " l19", " l20");

    List<String> eight = List.of("a", "b", "c", "d", "e", "f", "g", "h");
    List<String> inserted = List.of("a", "b", "c", "d", "X", "e", "f", "g", "h");
    assertThat(YamlDiff.of("o", eight, inserted).lines()).containsSubsequence("@@ -2,6 +2,7 @@", " d", "+X", " e");
    assertThat(YamlDiff.of("o", List.of("a"), List.of("b")).lines()).contains("@@ -1 +1 @@");
    assertThat(YamlDiff.of("o", List.of("a", "b"), List.of()).lines()).contains("@@ -1,2 +0,0 @@");
  }

  @Test
  void nothingToChangeIsAnEmptyDiff() {
    ObjectNode o = json("{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"c\"}}");

    assertThat(YamlDiff.of("configmap/c", o, o.deepCopy()).changed()).isFalse();
  }

  @Test
  void podSpecsThatReachTheNodeAreNamed() {
    ObjectNode ds = json("""
        {"apiVersion":"apps/v1","kind":"DaemonSet","metadata":{"name":"agent"},"spec":{"template":{"spec":{
          "hostPID":true,"volumes":[{"name":"root","hostPath":{"path":"/"}}],
          "containers":[{"name":"a","image":"x","securityContext":{"privileged":true,
            "capabilities":{"add":["NET_BIND_SERVICE","CAP_SYS_ADMIN"]}}}]}}}}
        """);

    assertThat(PodSpecs.escapes(ds)).containsExactly("hostPID", "hostPath volume root (/)",
        "privileged container a", "capability CAP_SYS_ADMIN in container a");
    assertThat(PodSpecs.images(ds)).isEqualTo(List.of("a=x"));
    ObjectNode cron = json("""
        {"kind":"CronJob","spec":{"jobTemplate":{"spec":{"template":{"spec":{"hostNetwork":true}}}}}}
        """);
    assertThat(PodSpecs.escapes(cron)).containsExactly("hostNetwork");
    assertThat(PodSpecs.escapes(json("{\"kind\":\"ConfigMap\",\"spec\":{\"hostPID\":true}}"))).isEmpty();
    // The node's disk through a claim: the pod spec mounts a PVC, the volume behind it is the host's.
    assertThat(PodSpecs.escapes(json("""
        {"apiVersion":"v1","kind":"PersistentVolume","metadata":{"name":"root"},"spec":{"hostPath":{"path":"/"},
         "capacity":{"storage":"1Gi"},"accessModes":["ReadWriteOnce"]}}
        """))).containsExactly("hostPath persistent volume (/)");
    assertThat(PodSpecs.escapes(json("""
        {"kind":"Pod","spec":{"containers":[{"name":"c","securityContext":{"capabilities":{"add":["BPF","SYS_RAWIO"]}}}]}}
        """))).containsExactly("capability BPF in container c", "capability SYS_RAWIO in container c");
  }
}
