package com.gaterook.e2e.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.connector.api.ToolDefinition.Nature;
import com.gaterook.core.policy.PairView;
import com.gaterook.core.policy.PolicyEngine;
import com.gaterook.core.policy.PolicySchemas;
import com.gaterook.core.policy.PolicyService;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every list a built-in policy section narrows by intersection says what an empty list allows ({@code x-empty}), as
 * its connector reads it; the core's merge follows that meaning, so an endpoint limit or a tool override never reaches
 * past the environment's list: HTTP egress stays closed, a Kubernetes resource and a cloud service stay inside the
 * connector's default set, and the SSH sign-in methods stay none. A list that restricts nothing when empty (the
 * Kubernetes namespaces: every namespace of the cluster) only gets shorter: a narrower list never gets names back, and
 * an empty override leaves the environment's names as they are.
 */
class PolicyListMeaningsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Map<String, ConnectorManifest> manifests;
  private static PolicySchemas schemas;

  @BeforeAll
  static void load() {
    manifests = BuiltInManifests.load();
    schemas = PolicySchemas.forManifests(List.copyOf(manifests.values()), JSON);
  }

  /** The environment's defaults plus what {@link #explicit} sets, as the real service resolves them. */
  private static final class Policies extends PolicyService {

    final ObjectNode explicit = JSON.createObjectNode();

    Policies() {
      super(null, null, null, null, null, null, null, null, JSON, null, null);
    }

    @Override
    public PolicySchemas schemas() {
      return schemas;
    }

    @Override
    public ObjectNode effective(Env env) {
      ObjectNode out = JSON.createObjectNode();
      for (String section : schemas.names()) {
        ObjectNode values = schemas.defaults(section, env);
        explicit.path(section).properties().forEach(f -> values.set(f.getKey(), f.getValue()));
        out.set(section, values);
      }
      return out;
    }

    @Override
    public boolean verified(Env env) {
      return true;
    }
  }

  @Test
  void everyIntersectedListOfABuiltInSectionSaysWhatAnEmptyListAllows() {
    Map<String, String> meanings = new TreeMap<>();
    for (String section : schemas.names()) {
      schemas.schema(section).orElseThrow().path("properties").properties().forEach(p -> {
        if ("intersect".equals(p.getValue().path("x-stricter").asString(null))) {
          meanings.put(section + "." + p.getKey(), p.getValue().path("x-empty").asString("(missing)"));
        }
      });
    }
    // What each connector does with an empty list. A meaning changes here only together with that reading; the tests
    // below then say what an endpoint limit or a tool override of such a list may keep.
    assertThat(meanings).containsExactlyEntriesOf(new TreeMap<>(Map.of(
        "cloud.allowedServices", "connector-default",
        "cloud.regions", "all",
        "cloud.resources", "all",
        "http.allowedBaseUrls", "none",
        "k8s.namespaces", "all",
        "k8s.resources", "connector-default",
        "k8s.verbs", "all",
        "sql.allowlist", "all",
        "ssh.allowedCredentialMethods", "none")));
  }

  @Test
  void overridesOfAnEmptyEnvironmentListStayInsideWhatItAllows() {
    Policies policies = new Policies();
    ObjectNode ssh = policies.explicit.putObject("ssh");
    ssh.putArray("allowedCredentialMethods");
    policies.explicit.putObject("k8s").putArray("resources");
    PolicyEngine engine = new PolicyEngine(policies);
    JsonNode endpoint = JSON.readTree("""
        {"allowedBaseUrls":["https://api.example.com/"],"allowedCredentialMethods":["password"],
         "namespaces":["kube-system"],"resources":["secrets","pods"],"allowedServices":["secretsmanager","ec2"]}""");
    JsonNode tool = JSON.readTree("""
        {"resources":["secrets","pods","nodes"],"namespaces":["kube-system","payments"]}""");

    assertThat(section(engine, "http", endpoint, tool).path("allowedBaseUrls")).as("HTTP egress stays closed")
        .isEmpty();
    assertThat(section(engine, "ssh", endpoint, tool).path("allowedCredentialMethods"))
        .as("no sign-in method stays none").isEmpty();
    assertThat(strings(section(engine, "kubernetes", endpoint, null).path("namespaces")))
        .as("the Kubernetes namespaces list restricts nothing when empty: the endpoint's name narrows the cluster")
        .containsExactly("kube-system");
    assertThat(strings(section(engine, "kubernetes", endpoint, tool).path("namespaces")))
        .as("the tool override's other namespace doesn't come back").containsExactly("kube-system");
    assertThat(strings(section(engine, "kubernetes", endpoint, tool).path("resources")))
        .containsExactly("secrets", "pods", PolicyView.WITHIN_DEFAULT);
    assertThat(strings(section(engine, "aws", endpoint, tool).path("allowedServices")))
        .containsExactly("secretsmanager", "ec2", PolicyView.WITHIN_DEFAULT);
    assertThat(strings(section(engine, "aws", endpoint, tool).path("resources")))
        .as("the cloud resources list restricts nothing when empty: the override's names narrow it")
        .containsExactly("secrets", "pods");
  }

  @Test
  void overridesOfAnExplicitListKeepTheCommonNames() {
    Policies policies = new Policies();
    policies.explicit.putObject("http").putArray("allowedBaseUrls").add("https://api.example.com/")
        .add("https://other.example.com/");
    policies.explicit.putObject("k8s").putArray("namespaces").add("web").add("kube-system");
    PolicyEngine engine = new PolicyEngine(policies);
    JsonNode endpoint = JSON.readTree("""
        {"allowedBaseUrls":["https://api.example.com/","https://third.example.com/"],
         "namespaces":["kube-system","payments"]}""");

    assertThat(strings(section(engine, "http", endpoint, null).path("allowedBaseUrls")))
        .containsExactly("https://api.example.com/");
    assertThat(strings(section(engine, "kubernetes", endpoint, null).path("namespaces"))).containsExactly("kube-system");
    assertThat(strings(section(engine, "kubernetes", endpoint, JSON.readTree("{\"namespaces\":[\"web\"]}"))
        .path("namespaces"))).as("disjoint: nothing").containsExactly(PolicyView.NOTHING);
    assertThat(strings(section(engine, "kubernetes", JSON.readTree("{\"namespaces\":[]}"), null).path("namespaces")))
        .as("an empty override stands for every namespace: the environment's list stays as it is")
        .containsExactly("web", "kube-system");
  }

  private static JsonNode section(PolicyEngine engine, String connectorType, JsonNode endpoint, JsonNode tool) {
    ConnectorManifest manifest = manifests.get(connectorType);
    assertThat(manifest).as(connectorType).isNotNull();
    ToolDefinition read = ToolDefinition.builder("read").nature(Nature.READ).build();
    PairView pair = new PairView("ep_1", "ep", Env.DEV, false, "cn_1", "resource", Env.DEV, manifest, read,
        read.name(), Mode.READ_ONLY, endpoint, tool);
    return engine.view(pair, null).section();
  }

  private static List<String> strings(JsonNode list) {
    List<String> out = new java.util.ArrayList<>();
    list.forEach(v -> out.add(v.asString()));
    return out;
  }
}
