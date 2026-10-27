package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.call;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.ctx;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.secrets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The connector against the k3s of the Compose services ({@code scripts/test-services.sh up k8s}): a cluster with a
 * kubelet, which the bare kube-apiserver of {@link KubeApiServerIT} doesn't have, so {@code k8s_logs} reads what a
 * running container printed. A pod prints a numbered line every 200 ms in the namespace {@code gw-logs}, made afresh
 * for each run. Skipped without the services or without k3s.
 */
class K3sKubeletIT {

  private static final String K3S = "gw-test-k3s";
  private static String kubeconfig;

  private KubernetesConnector connector;

  @BeforeAll
  static void ticker() {
    assumeTrue(TestServices.enabled(), "the Compose services are off (-Pservices, test-services/README.md)");
    Path file = TestServices.file("run/k3s/kubeconfig.yaml");
    assumeTrue(Files.isRegularFile(file), "k3s isn't up: scripts/test-services.sh up k8s");
    try {
      kubeconfig = Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    TestServices.exec(K3S, "kubectl", "delete", "namespace", "gw-logs", "--ignore-not-found", "--wait=true");
    TestServices.exec(K3S, "kubectl", "create", "namespace", "gw-logs");
    TestServices.exec(K3S, "kubectl", "-n", "gw-logs", "run", "ticker", "--image=busybox:1.37", "--restart=Never",
        "--", "sh", "-c", "i=0; while true; do i=$((i+1)); echo \"tick $i\"; sleep 0.2; done");
    TestServices.exec(K3S, "kubectl", "-n", "gw-logs", "wait", "--for=condition=Ready", "pod/ticker",
        "--timeout=180s");
  }

  @BeforeEach
  void connector() {
    connector = new KubernetesConnector();
  }

  @AfterEach
  void close() {
    connector.close();
  }

  private static ConnectionConfig cfg() {
    return K8sTestKit.cfg("c-k3s", Env.DEV, "{\"auth\":\"kubeconfig\",\"namespace\":\"gw-logs\"}", null);
  }

  private static Map<String, String> admin() {
    return Map.of(K8sSettings.KUBECONFIG, kubeconfig);
  }

  @Test
  void testConnectionReachesTheCluster() {
    TestResult t = connector.test(cfg(), secrets(admin()), ctx(Env.DEV));

    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).startsWith("v1.37.");
  }

  @Test
  void logsAreTheLinesTheRunningContainerPrinted() throws Exception {
    Thread.sleep(1_500); // a few more ticks than the pod had when it got ready

    ToolResult r = connector.execute(call("k8s_logs", "{\"pod\":\"ticker\",\"tailLines\":5}"), cfg(),
        secrets(admin()), ctx(Env.DEV));

    List<String> lines = r.content().get(0).text().lines().toList();
    assertThat(lines.getFirst()).isEqualTo("pod/ticker (ticker) in gw-logs on dev-cluster · 5 lines");
    assertThat(lines.subList(1, lines.size())).hasSize(5).allMatch(l -> l.matches("tick \\d+"));
    assertThat(r.structuredContent().path("container").asString()).isEqualTo("ticker");
    assertThat(r.structuredContent().path("lines").asLong()).isEqualTo(5);
    assertThat(r.structuredContent().path("truncated").asBoolean()).isFalse();
  }

  @Test
  void theRunningPodIsReadyOnItsNode() throws Exception {
    ToolResult pod = connector.execute(call("k8s_get", "{\"kind\":\"po\",\"name\":\"ticker\"}"), cfg(),
        secrets(admin()), ctx(Env.DEV));

    assertThat(pod.content().get(0).text()).startsWith("pod/ticker in gw-logs on dev-cluster (dev) · Running · 1/1 "
        + "ready");
  }
}
