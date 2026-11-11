package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@link Discovery#build}: URLs only from a method of a Discovery document and checked values. A reserved expansion
 * never takes a colon (a custom method), a dot segment or an encoded character; a simple one is encoded whole; the
 * document's parameters, patterns and required ones decide what goes on the query string.
 */
class DiscoveryTest {

  static final String RUN = """
      {"name":"run","version":"v2","title":"Cloud Run Admin API","rootUrl":"https://run.googleapis.com/","servicePath":"",
       "resources":{"projects":{"resources":{"locations":{"resources":{"services":{"methods":{
         "list":{"id":"run.projects.locations.services.list","path":"v2/{+parent}/services","httpMethod":"GET",
           "parameters":{"parent":{"location":"path","required":true,"pattern":"^projects/[^/]+/locations/[^/]+$"},
             "pageSize":{"location":"query","type":"integer"},"pageToken":{"location":"query"},
             "showDeleted":{"location":"query","type":"boolean"}}},
         "get":{"id":"run.projects.locations.services.get","path":"v2/{+name}","httpMethod":"GET",
           "parameters":{"name":{"location":"path","required":true,"pattern":"^projects/[^/]+/locations/[^/]+/services/[^/]+$"}}},
         "patch":{"id":"run.projects.locations.services.patch","path":"v2/{+name}","httpMethod":"PATCH",
           "parameters":{"name":{"location":"path","required":true,"pattern":"^projects/[^/]+/locations/[^/]+/services/[^/]+$"},
             "updateMask":{"location":"query"}},
           "request":{"$ref":"GoogleCloudRunV2Service"},"response":{"$ref":"GoogleLongrunningOperation"}},
         "setIamPolicy":{"id":"run.projects.locations.services.setIamPolicy","path":"v2/{+resource}:setIamPolicy",
           "httpMethod":"POST","parameters":{"resource":{"location":"path","required":true}},"request":{"$ref":"X"}}
       }}}}}}}}""";

  static final String STORAGE = """
      {"name":"storage","version":"v1","title":"Cloud Storage JSON API","rootUrl":"https://storage.googleapis.com/",
       "servicePath":"storage/v1/","resources":{
         "objects":{"methods":{
           "list":{"id":"storage.objects.list","path":"b/{bucket}/o","httpMethod":"GET",
             "parameters":{"bucket":{"location":"path","required":true},"prefix":{"location":"query"}}},
           "get":{"id":"storage.objects.get","path":"b/{bucket}/o/{object}","httpMethod":"GET",
             "parameters":{"bucket":{"location":"path","required":true},"object":{"location":"path","required":true}}}}},
         "buckets":{"methods":{
           "list":{"id":"storage.buckets.list","path":"b","httpMethod":"GET",
             "parameters":{"project":{"location":"query","required":true},"labels":{"location":"query","repeated":true}}}}}}}""";

  static final String SQL = """
      {"name":"sqladmin","version":"v1","title":"Cloud SQL Admin API","rootUrl":"https://sqladmin.googleapis.com/","servicePath":"",
       "resources":{"instances":{"methods":{"list":{"id":"sql.instances.list","path":"v1/projects/{project}/instances",
         "httpMethod":"GET","parameters":{"project":{"location":"path","required":true}}}}}}}""";

  static final String ARTIFACTS = """
      {"name":"artifactregistry","version":"v1","title":"Artifact Registry API","rootUrl":"https://artifactregistry.googleapis.com/","servicePath":"",
       "methods":{"getVersion":{"id":"artifactregistry.getVersion","path":"v1/{+name}","httpMethod":"GET",
         "parameters":{"name":{"location":"path","required":true}}}}}""";

  private static Discovery.Method method(String doc, String api, String key) throws Exception {
    JsonNode tree = TestCtx.JSON.readTree(doc);
    return Discovery.parse(tree, api, tree.path("version").asString(), false).find(key);
  }

  private static JsonNode params(String json) {
    return TestCtx.JSON.readTree(json);
  }

  @Test
  void buildsTheUrlFromTheDocumentWithEveryValueChecked() throws Exception {
    Discovery.Method list = method(RUN, "run", "projects.locations.services.list");

    Discovery.Built b = Discovery.build(list, null, params("""
        {"parent":"projects/acme-stage/locations/europe-west1","pageSize":50,"showDeleted":true}"""));

    assertThat(b.uri()).hasToString("https://run.googleapis.com/v2/projects/acme-stage/locations/europe-west1/services"
        + "?pageSize=50&showDeleted=true");
    assertThat(b.resourcePath()).isEqualTo("projects/acme-stage/locations/europe-west1/services");
    assertThat(list.reads()).isTrue();
    assertThat(Discovery.build(list, URI.create("http://127.0.0.1:9/"), params("""
        {"parent":"projects/acme-stage/locations/-"}""")).uri())
        .hasToString("http://127.0.0.1:9/v2/projects/acme-stage/locations/-/services");
  }

  @Test
  void aColonDotSegmentOrEncodedCharacterNeverEntersAReservedExpansion() throws Exception {
    Discovery.Method get = method(RUN, "run", "projects.locations.services.get");

    for (String name : new String[] {
        "projects/acme-stage/locations/europe-west1/services/api:getIamPolicy",
        "projects/acme-stage/locations/europe-west1/services/..",
        "projects/acme-stage/locations/europe-west1/services/a%2Fb",
        "projects/acme-stage/locations/europe-west1/services/api?alt=media",
        "projects/acme-stage/locations/europe-west1/services/api#x",
        "projects/acme-stage//services/api"}) {
      assertThatThrownBy(() -> Discovery.build(get, null, params("{\"name\":\"" + name + "\"}")))
          .as(name).isInstanceOf(Refusal.class);
    }
    Discovery.Method version = method(ARTIFACTS, "artifactregistry", "getVersion");
    String digest = "projects/p/locations/l/repositories/r/dockerImages/app@sha256:" + "a".repeat(64);
    assertThat(Discovery.build(version, null, params("{\"name\":\"" + digest + "\"}")).uri().getRawPath())
        .endsWith("/dockerImages/app@sha256:" + "a".repeat(64));
  }

  @Test
  void theDocumentsPatternsParametersAndRequiredOnesDecide() throws Exception {
    Discovery.Method list = method(RUN, "run", "projects.locations.services.list");

    assertThatThrownBy(() -> Discovery.build(list, null, params("{\"parent\":\"folders/1\"}")))
        .isInstanceOf(Refusal.class).hasMessageContaining("form");
    assertThatThrownBy(() -> Discovery.build(list, null, params("""
        {"parent":"projects/acme-stage/locations/-","filter":"x"}""")))
        .isInstanceOf(Refusal.class).hasMessageContaining("has no parameter filter");
    assertThatThrownBy(() -> Discovery.build(list, null, params("{}")))
        .isInstanceOf(Refusal.class).hasMessageContaining("needs params.parent");
    assertThatThrownBy(() -> Discovery.build(list, null, params("""
        {"parent":"projects/acme-stage/locations/-","pageToken":["a","b"]}""")))
        .isInstanceOf(Refusal.class).hasMessageContaining("one value");
    assertThat(Discovery.build(list, null, params("""
        {"parent":"projects/acme-stage/locations/-","fields":"services.name"}""")).uri().getRawQuery())
        .isEqualTo("fields=services.name");
    Discovery.Method buckets = method(STORAGE, "storage", "buckets.list");
    assertThatThrownBy(() -> Discovery.build(buckets, null, params("{}")))
        .isInstanceOf(Refusal.class).hasMessageContaining("needs params.project");
    assertThat(Discovery.build(buckets, null, params("""
        {"project":"acme-stage","labels":["env:stage","team:pay"]}""")).uri().getRawQuery())
        .isEqualTo("project=acme-stage&labels=env%3Astage&labels=team%3Apay");
  }

  @Test
  void aSimpleExpansionIsEncodedWholeAsCloudStorageExpects() throws Exception {
    Discovery.Method get = method(STORAGE, "storage", "objects.get");

    Discovery.Built b = Discovery.build(get, null, params("""
        {"bucket":"acme-stage-uploads","object":"reports/2026 q3.csv"}"""));

    assertThat(b.uri().getRawPath()).isEqualTo("/storage/v1/b/acme-stage-uploads/o/reports%2F2026%20q3.csv");
    assertThat(b.resourcePath()).isEqualTo("b/acme-stage-uploads/o/reports%2F2026%20q3.csv");
    assertThatThrownBy(() -> Discovery.build(get, null, params("""
        {"bucket":"acme-stage-uploads","object":".."}"""))).isInstanceOf(Refusal.class);
    GoogleApi.requireNoDotSegments(b.uri());
    assertThatThrownBy(() -> GoogleApi.requireNoDotSegments(URI.create("https://storage.googleapis.com/storage/v1/b/x/o/%2e%2e")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void methodsAreFoundByTheirKeyOrTheirIdAndKnowWhatTheyDo() throws Exception {
    Discovery.Method sql = method(SQL, "sqladmin", "instances.list");
    Discovery.Method byId = method(SQL, "sqladmin", "sql.instances.list");
    Discovery.Method patch = method(RUN, "run", "projects.locations.services.patch");
    Discovery.Method setIam = method(RUN, "run", "projects.locations.services.setIamPolicy");

    assertThat(sql).isEqualTo(byId);
    assertThat(sql.key()).isEqualTo("instances.list");
    assertThat(Discovery.build(sql, null, params("{\"project\":\"acme-stage\"}")).resourcePath())
        .isEqualTo("projects/acme-stage/instances");
    assertThat(patch.reads()).isFalse();
    assertThat(patch.body()).isTrue();
    assertThat(patch.operation()).isTrue();
    assertThat(setIam.reads()).isFalse();
    assertThat(Discovery.build(setIam, null, params("""
        {"resource":"projects/acme-stage/locations/europe-west1/services/api"}""")).resourcePath())
        .as("the custom method is not part of the resource").isEqualTo(
            "projects/acme-stage/locations/europe-west1/services/api");
  }

  @Test
  void aDocumentWhoseRootIsntGooglesIsRefused() {
    JsonNode doc = TestCtx.JSON.readTree(RUN.replace("https://run.googleapis.com/", "https://evil.example/"));

    assertThatThrownBy(() -> Discovery.parse(doc, "run", "v2", false))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("outside googleapis.com");
  }
}
