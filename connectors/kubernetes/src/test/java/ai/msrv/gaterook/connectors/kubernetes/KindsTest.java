package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

/** Kinds resolve like kubectl's arguments (report 08 §3.1), from the built-in table or the discovery snapshot. */
class KindsTest {

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({"pods, pods/core", "po, pods/core", "Pod, pods/core", "POD, pods/core", "deploy, deployments.apps",
      "Deployment, deployments.apps", "deployments.apps, deployments.apps", "deployments.v1.apps, deployments.apps",
      "events, events/core", "ev, events/core", "events.events.k8s.io, events.events.k8s.io", "hpa, "
      + "horizontalpodautoscalers.autoscaling", "crd, customresourcedefinitions.apiextensions.k8s.io",
      "netpol, networkpolicies.networking.k8s.io"})
  void resolvesTheBuiltInKinds(String text, String expected) {
    KindInfo k = Kinds.builtIn().resolve(text).orElseThrow();

    assertThat(k.group().isEmpty() ? k.plural() + "/core" : k.rbacName()).isEqualTo(expected);
  }

  @Test
  void unknownAndSubresourceNamesDoNotResolve() {
    assertThat(Kinds.builtIn().resolve("widgets")).isEmpty();
    assertThat(Kinds.builtIn().resolve("")).isEmpty();
    assertThat(Kinds.builtIn().resolve("deployments.v2.apps")).isEmpty();
    assertThat(Kinds.builtIn().resolve(".apps")).isEmpty();
  }

  @Test
  void manifestKindsResolveByApiVersion() {
    Kinds builtIn = Kinds.builtIn();

    assertThat(builtIn.byApiVersion("apps/v1", "Deployment").orElseThrow().rbacName()).isEqualTo("deployments.apps");
    // The table knows the preferred version only; the API server judges another one.
    assertThat(builtIn.byApiVersion("autoscaling/v1", "HorizontalPodAutoscaler").orElseThrow().apiVersion())
        .isEqualTo("autoscaling/v1");
    assertThat(builtIn.byApiVersion("example.com/v1", "Widget")).isEmpty();
  }

  @Test
  void theDiscoverySnapshotAddsCustomResourcesAndIsCompact() {
    ObjectNode aggregated = json("""
        {"kind":"APIGroupDiscoveryList","items":[
          {"metadata":{"name":"cert-manager.io"},"versions":[
            {"version":"v1","resources":[
              {"resource":"certificates","responseKind":{"kind":"Certificate"},"scope":"Namespaced",
               "singularResource":"certificate","shortNames":["cert","certs"],"verbs":["get","list","patch"]},
              {"resource":"certificates/status","responseKind":{"kind":"Certificate"},"scope":"Namespaced",
               "verbs":["get"]},
              {"resource":"challenges","responseKind":{"kind":"Challenge"},"scope":"Namespaced","verbs":["create"]}]}]},
          {"metadata":{"name":""},"versions":[{"version":"v1","resources":[
              {"resource":"pods","responseKind":{"kind":"Pod"},"scope":"Namespaced","singularResource":"pod",
               "shortNames":["po"],"verbs":["get","list"]}]}]}]}
        """);

    List<KindInfo> kinds = Kinds.fromAggregated(aggregated);
    ObjectNode metadata = K8sTestKit.NODES.objectNode();
    metadata.set("discovery", Kinds.snapshot(kinds));
    Kinds fromMetadata = Kinds.of(metadata);

    assertThat(kinds).extracting(KindInfo::rbacName).containsExactly("certificates.cert-manager.io", "pods");
    assertThat(fromMetadata.discovered()).isTrue();
    assertThat(fromMetadata.resolve("cert").orElseThrow().kind()).isEqualTo("Certificate");
    assertThat(fromMetadata.resolve("certificates.cert-manager.io").orElseThrow().namespaced()).isTrue();
    assertThat(fromMetadata.byApiVersion("cert-manager.io/v2", "Certificate")).isEmpty();
    assertThat(Kinds.snapshot(kinds).toString()).isEqualTo(Kinds.snapshot(Kinds.fromAggregated(aggregated))
        .toString());
  }

  @Test
  void legacyDiscoveryLeavesSubresourcesOut() {
    List<KindInfo> kinds = Kinds.fromResourceList(json("""
        {"groupVersion":"apps/v1","resources":[
          {"name":"deployments","singularName":"deployment","namespaced":true,"kind":"Deployment",
           "verbs":["get","list"],"shortNames":["deploy"]},
          {"name":"deployments/scale","namespaced":true,"kind":"Scale","verbs":["get"]}]}
        """));

    assertThat(kinds).extracting(KindInfo::rbacName).containsExactly("deployments.apps");
  }

  @Test
  void pathsArePercentEncoded() {
    KindInfo pods = Kinds.builtIn().resolve("pods").orElseThrow();

    assertThat(pods.path("app-dev", "api-1")).isEqualTo("api/v1/namespaces/app-dev/pods/api-1");
    assertThat(pods.path("app-dev", "a/b?c")).isEqualTo("api/v1/namespaces/app-dev/pods/a%2Fb%3Fc");
    KindInfo nodes = Kinds.builtIn().resolve("nodes").orElseThrow();
    assertThat(nodes.path("ignored", null)).isEqualTo("api/v1/nodes");
  }
}
