package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.FakeApiServer.ok;
import static ai.msrv.gaterook.connectors.kubernetes.FakeApiServer.status;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.ctx;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.secrets;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help for the Kubernetes form: contexts of a kubeconfig file with its current context filled in, the namespace a
 * context sets, a pasted kubeconfig's contexts, and the cluster's namespaces once the draft signs in.
 */
class K8sSuggestionsTest {

  private static final String TOKEN = "cnry-suggest-token-41";

  private FakeApiServer api;
  private KubernetesConnector connector;

  @TempDir
  Path dir;

  @BeforeEach
  void start() throws IOException {
    api = new FakeApiServer();
    connector = new KubernetesConnector();
  }

  @AfterEach
  void stop() {
    connector.close();
    api.close();
  }

  private Path kubeconfig(String current) throws IOException {
    // The credential command exists, as gcloud installs it: it must still never run for a suggestion.
    Path plugin = dir.resolve("gke-gcloud-auth-plugin");
    Files.writeString(plugin, "#!/bin/sh\necho ran > " + dir.resolve("ran") + "\n");
    plugin.toFile().setExecutable(true);
    Path file = dir.resolve("config");
    Files.writeString(file, """
        apiVersion: v1
        kind: Config
        current-context: %s
        clusters:
        - name: dev
          cluster:
            server: %s
        - name: prod
          cluster:
            server: https://k8s.prod.example.test:6443
        contexts:
        - name: kind-dev
          context: {cluster: dev, user: dev, namespace: app-dev}
        - name: gke-prod
          context: {cluster: prod, user: gke}
        users:
        - name: dev
          user: {token: %s}
        - name: gke
          user:
            exec:
              apiVersion: client.authentication.k8s.io/v1beta1
              command: %s
        """.formatted(current, api.url(), TOKEN, plugin));
    return file;
  }

  private static ConnectionConfig draft(String json) {
    return K8sTestKit.cfg("draft-1", Env.DEV, json, null);
  }

  @Test
  void aKubeconfigFileOffersItsContextsAndFillsInTheCurrentOne() throws Exception {
    Path file = kubeconfig("kind-dev");

    Suggestions s = connector.suggest(draft("{\"auth\":\"kubeconfig-file\",\"kubeconfigPath\":\"" + file + "\"}"),
        secrets(Map.of()), ctx(Env.DEV), Suggestions.Scope.LOCAL);

    Suggestions.Field context = s.fields().get("/context");
    assertThat(context.value()).isEqualTo("kind-dev");
    assertThat(context.options()).extracting(Option::value).containsExactly("kind-dev", "gke-prod");
    assertThat(context.options().get(0).label()).isEqualTo("kind-dev (current)");
    assertThat(context.options().get(1).detail()).isEqualTo("k8s.prod.example.test:6443");
    // The current context sets a namespace: Default namespace takes it.
    assertThat(s.fields().get("/namespace").value()).isEqualTo("app-dev");
    assertThat(s.fields().get("/kubeconfigPath")).isNotNull();
    // Nothing went to the cluster for local suggestions.
    assertThat(api.requests).isEmpty();
  }

  @Test
  void aContextThatRunsACredentialCommandSaysWhatToTurnOn() throws Exception {
    Path file = kubeconfig("gke-prod");

    Suggestions s = connector.suggest(draft("{\"auth\":\"kubeconfig-file\",\"kubeconfigPath\":\"" + file
        + "\",\"context\":\"gke-prod\"}"), secrets(Map.of()), ctx(Env.DEV), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/context").value()).isEqualTo("gke-prod");
    assertThat(s.notes()).anyMatch(n -> n.contains("gke-gcloud-auth-plugin")
        && n.contains("Run the credential command from kubeconfig"));
    // The command is never run for a suggestion: the namespaces wait for Test connection.
    assertThat(s.notes()).anyMatch(n -> n.startsWith("Namespaces appear after Test connection"));
    assertThat(s.fields()).doesNotContainKey("/namespace");
    assertThat(api.requests).isEmpty();
    assertThat(dir.resolve("ran")).doesNotExist();
  }

  @Test
  void aNewDraftSignsInWithThisComputersKubeconfigWhileNothingOfATokenIsTyped() throws Exception {
    Path home = kubeconfig("kind-dev");

    // The default mode with nothing typed: Sign in with takes the kubeconfig file, beside the servers it knows.
    Suggestions fresh = K8sSuggestions.suggest(draft("{\"auth\":\"token\"}"), secrets(Map.of()), ctx(Env.DEV),
        Suggestions.Scope.LOCAL, null, home);
    assertThat(fresh.fields().get("/auth").value()).isEqualTo("kubeconfig-file");
    assertThat(fresh.fields().get("/auth").source()).isEqualTo(K8sSuggestions.KUBECONFIG_SOURCE);
    assertThat(fresh.fields().get("/server").options()).extracting(Option::value)
        .contains("https://k8s.prod.example.test:6443");

    // An API server or a token typed says the person signs in with a token: no switch.
    assertThat(K8sSuggestions.suggest(draft("{\"auth\":\"token\",\"server\":\"https://k8s.example.test\"}"),
        secrets(Map.of()), ctx(Env.DEV), Suggestions.Scope.LOCAL, null, home).fields()).doesNotContainKey("/auth");
    assertThat(K8sSuggestions.suggest(draft("{\"auth\":\"token\"}"), secrets(Map.of(K8sSettings.TOKEN, TOKEN)),
        ctx(Env.DEV), Suggestions.Scope.LOCAL, null, home).fields()).doesNotContainKey("/auth");
    // No kubeconfig on this computer: nothing to switch to.
    assertThat(K8sSuggestions.suggest(draft("{\"auth\":\"token\"}"), secrets(Map.of()), ctx(Env.DEV),
        Suggestions.Scope.LOCAL, null, dir.resolve("none")).fields()).isEmpty();
    assertThat(api.requests).isEmpty();
  }

  @Test
  void aMissingFileIsANoteNotAnError() {
    Suggestions s = connector.suggest(draft("{\"auth\":\"kubeconfig-file\",\"kubeconfigPath\":\""
        + dir.resolve("nope") + "\"}"), secrets(Map.of()), ctx(Env.DEV), Suggestions.Scope.LOCAL);

    assertThat(s.notes()).anyMatch(n -> n.contains("doesn't exist on this computer"));
    assertThat(s.fields()).doesNotContainKey("/context");
  }

  @Test
  void aPastedKubeconfigOffersItsContextsWithoutFillingOneIn() throws Exception {
    String text = Files.readString(kubeconfig("kind-dev"));

    Suggestions s = connector.suggest(draft("{\"auth\":\"kubeconfig\"}"),
        secrets(Map.of(K8sSettings.KUBECONFIG, text)), ctx(Env.DEV), Suggestions.Scope.LOCAL);

    // Empty means its current context for a pasted kubeconfig, so nothing is filled in.
    assertThat(s.fields().get("/context").value()).isNull();
    assertThat(s.fields().get("/context").source()).isEqualTo("the pasted kubeconfig");
    assertThat(s.fields().get("/context").options()).hasSize(2);
  }

  @Test
  void signedInItListsTheNamespacesAndKeepsTheContextsOwn() throws Exception {
    Path file = kubeconfig("kind-dev");
    api.on("GET", "/api/v1/namespaces", r -> ok("""
        {"kind":"NamespaceList","metadata":{"continue":""},"items":[
          {"metadata":{"name":"default"},"status":{"phase":"Active"}},
          {"metadata":{"name":"app-dev"},"status":{"phase":"Active"}},
          {"metadata":{"name":"old"},"status":{"phase":"Terminating"}}]}"""));
    ConnectionConfig cfg = draft("{\"auth\":\"kubeconfig-file\",\"kubeconfigPath\":\"" + file
        + "\",\"context\":\"kind-dev\",\"caCert\":" + K8sApi.JSON.writeValueAsString(api.caPem) + "}");

    Suggestions s = connector.suggest(cfg, secrets(Map.of()), ctx(Env.DEV), Suggestions.Scope.SERVICE);

    Suggestions.Field ns = s.fields().get("/namespace");
    assertThat(ns.source()).isEqualTo("the cluster");
    assertThat(ns.options()).extracting(Option::value).containsExactly("default", "app-dev");
    assertThat(ns.value()).isEqualTo("app-dev");
    assertThat(s.notes()).anyMatch(n -> n.startsWith("Signed in to localhost:") && n.contains("2 namespaces"));
    assertThat(api.requests("GET", "/api/v1/namespaces")).singleElement()
        .satisfies(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN));
  }

  @Test
  void anAccountThatMayNotListNamespacesIsToldToTypeOne() {
    api.on("GET", "/api/v1/namespaces", r -> status(403, "Forbidden", "namespaces is forbidden"));
    ConnectionConfig cfg = K8sTestKit.cfg("draft-2", Env.DEV, api.tokenConfig("app-dev"), null);

    Suggestions s = connector.suggest(cfg, secrets(Map.of(K8sSettings.TOKEN, "t-1")), ctx(Env.DEV),
        Suggestions.Scope.SERVICE);

    assertThat(s.notes()).contains("The account can't list namespaces: type one it may use.");
    assertThat(s.fields()).doesNotContainKey("/namespace");
  }
}
