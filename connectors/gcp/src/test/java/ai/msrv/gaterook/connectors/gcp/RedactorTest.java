package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The connector's own masking of values GCP returns as configuration (report 09 §6 "Redaction"): golden JSON in, the
 * same JSON with {@code ‹masked›} where secrets usually are, and the count the answer reports.
 */
class RedactorTest {

  private static final String M = Redactor.MASK;

  @Test
  void computeMetadataValuesGoKeysStay() {
    JsonNode vm = TestCtx.JSON.readTree("""
        {"name":"api-1","status":"RUNNING","metadata":{"fingerprint":"abc=","items":[
          {"key":"startup-script","value":"export DB_PASSWORD=hunter2; ./run"},
          {"key":"enable-oslogin","value":"TRUE"}]},
         "networkInterfaces":[{"networkIP":"10.0.0.4"}]}""");

    assertThat(Redactor.redact(vm)).isEqualTo(2);

    assertThat(vm.at("/metadata/items/0/key").asString()).isEqualTo("startup-script");
    assertThat(vm.at("/metadata/items/0/value").asString()).isEqualTo(M);
    assertThat(vm.at("/metadata/items/1/value").asString()).isEqualTo(M);
    assertThat(vm.at("/metadata/fingerprint").asString()).isEqualTo("abc=");
    assertThat(vm.at("/networkInterfaces/0/networkIP").asString()).isEqualTo("10.0.0.4");
  }

  @Test
  void containerEnvironmentValuesGoReferencesStay() {
    JsonNode run = TestCtx.JSON.readTree("""
        {"name":"projects/acme-stage/locations/europe-west1/services/api","template":{"containers":[{
          "image":"europe-docker.pkg.dev/acme/api:1.4","env":[
            {"name":"DB_PASS","value":"cnry-run-env-1"},
            {"name":"DB_URL","valueSource":{"secretKeyRef":{"secret":"db-url","version":"latest"}}}]}]},
         "terminalCondition":{"type":"Ready","state":"CONDITION_SUCCEEDED"}}""");

    assertThat(Redactor.redact(run)).isEqualTo(2);

    assertThat(run.at("/template/containers/0/env/0/name").asString()).isEqualTo("DB_PASS");
    assertThat(run.at("/template/containers/0/env/0/value").asString()).isEqualTo(M);
    assertThat(run.at("/template/containers/0/env/1/valueSource/secretKeyRef/version").asString())
        .isEqualTo("latest");
    assertThat(run.toString()).doesNotContain("cnry-run-env-1");
    assertThat(run.at("/template/containers/0/image").asString()).isEqualTo("europe-docker.pkg.dev/acme/api:1.4");
  }

  @Test
  void functionEnvironmentsGkeAuthAndKubernetesSecretsGo() {
    JsonNode fn = TestCtx.JSON.readTree("""
        {"buildConfig":{"runtime":"python312","environmentVariables":{"PIP_TOKEN":"cnry-pip"}},
         "serviceConfig":{"environmentVariables":{"API_KEY":"cnry-api","LOG_LEVEL":"debug"},
          "secretEnvironmentVariables":[{"key":"DB","secret":"db-pass","version":"3"}]}}""");
    JsonNode gke = TestCtx.JSON.readTree("""
        {"name":"main","masterAuth":{"username":"admin","password":"cnry-gke","clientKey":"LS0tLS1CRUdJTi",
         "clusterCaCertificate":"LS0tLS1DQQ=="},"status":"RUNNING"}""");
    JsonNode secret = TestCtx.JSON.readTree("""
        {"kind":"Secret","metadata":{"name":"db"},"data":{"password":"aHVudGVyMg=="},"type":"Opaque"}""");

    assertThat(Redactor.redact(fn)).isEqualTo(4);
    assertThat(fn.toString()).doesNotContain("cnry-pip").doesNotContain("cnry-api").doesNotContain("debug");
    assertThat(fn.at("/buildConfig/runtime").asString()).isEqualTo("python312");
    assertThat(fn.at("/serviceConfig/secretEnvironmentVariables/0/key").asString()).isEqualTo("DB");

    assertThat(Redactor.redact(gke)).isEqualTo(2);
    assertThat(gke.at("/masterAuth/username").asString()).isEqualTo("admin");
    assertThat(gke.at("/masterAuth/clusterCaCertificate").asString()).isEqualTo("LS0tLS1DQQ==");
    assertThat(gke.toString()).doesNotContain("cnry-gke").doesNotContain("LS0tLS1CRUdJTi");

    assertThat(Redactor.redact(secret)).isEqualTo(1);
    assertThat(secret.at("/data/password").asString()).isEqualTo(M);
    assertThat(secret.at("/type").asString()).isEqualTo("Opaque");
  }

  @Test
  void keysThatNameASecretLoseTheirTextValuesOnly() {
    JsonNode n = TestCtx.JSON.readTree("""
        {"rootPassword":"cnry-sql","authString":"cnry-redis","userData":"#!/bin/sh","tokenCount":3,
         "passwordValidationPolicy":{"minLength":12},"secretName":"db-pass","clientSecret":"cnry-oauth",
         "connectionString":"postgres://u:p@h/db","tokenTtl":"3600s","privateKeyData":"MIIE",
         "credentials":"cnry-creds","settings":{"tier":"db-custom-2-7680","authorizedGcpServiceAccount":"x"}}""");

    assertThat(Redactor.redact(n)).isEqualTo(7);

    for (String masked : new String[] {"rootPassword", "authString", "userData", "clientSecret", "connectionString",
        "privateKeyData", "credentials"}) {
      assertThat(n.path(masked).asString()).as(masked).isEqualTo(M);
    }
    assertThat(n.path("tokenCount").asInt()).isEqualTo(3);
    assertThat(n.at("/passwordValidationPolicy/minLength").asInt()).isEqualTo(12);
    assertThat(n.path("secretName").asString()).isEqualTo("db-pass");
    assertThat(n.path("tokenTtl").asString()).isEqualTo("3600s");
    assertThat(n.at("/settings/tier").asString()).isEqualTo("db-custom-2-7680");
  }

  @Test
  void projectMetadataNodeMetadataAndEnvironmentMapsGo() {
    // The inventory's snapshot of //compute.googleapis.com/projects/acme-stage: project-wide startup scripts.
    JsonNode project = TestCtx.JSON.readTree("""
        {"name":"acme-stage","commonInstanceMetadata":{"fingerprint":"f=","items":[
          {"key":"startup-script","value":"cnry-project-script"},{"key":"db-password","value":"hunter2"}]}}""");
    JsonNode cluster = TestCtx.JSON.readTree("""
        {"name":"main","nodeConfig":{"machineType":"e2-medium","metadata":{"startup":"cnry-node"}},
         "nodePools":[{"name":"pool-1","config":{"metadata":{"disable-legacy-endpoints":"cnry-pool"}}}]}""");
    JsonNode dataproc = TestCtx.JSON.readTree("""
        {"config":{"gceClusterConfig":{"zoneUri":"europe-west1-b","metadata":{"init":"cnry-dataproc"}}}}""");
    JsonNode appEngine = TestCtx.JSON.readTree("""
        {"id":"v1","runtime":"python312","envVariables":{"API_KEY":"cnry-appengine"}}""");
    JsonNode workflow = TestCtx.JSON.readTree("""
        {"name":"wf","userEnvVars":{"WEBHOOK":"cnry-workflow"},"state":"ACTIVE"}""");
    JsonNode batch = TestCtx.JSON.readTree("""
        {"taskGroups":[{"taskSpec":{"environment":{"variables":{"API_HOST":"cnry-batch"}}}}]}""");
    JsonNode sql = TestCtx.JSON.readTree("""
        {"name":"replica","replicaConfiguration":{"mysqlReplicaConfiguration":{"clientKey":"cnry-sql-key",
         "username":"repl"}}}""");
    JsonNode pod = TestCtx.JSON.readTree("""
        {"kind":"Deployment","metadata":{"name":"api","labels":{"app":"api"}},
         "spec":{"template":{"metadata":{"labels":{"app":"api"}}}}}""");

    assertThat(Redactor.redact(project)).isEqualTo(2);
    assertThat(project.at("/commonInstanceMetadata/items/0/key").asString()).isEqualTo("startup-script");
    assertThat(project.toString()).doesNotContain("cnry-project-script").doesNotContain("hunter2");
    assertThat(Redactor.redact(cluster)).isEqualTo(2);
    assertThat(cluster.at("/nodeConfig/machineType").asString()).isEqualTo("e2-medium");
    assertThat(cluster.toString()).doesNotContain("cnry-node").doesNotContain("cnry-pool");
    assertThat(Redactor.redact(dataproc)).isEqualTo(1);
    assertThat(dataproc.toString()).doesNotContain("cnry-dataproc").contains("europe-west1-b");
    assertThat(Redactor.redact(appEngine)).isEqualTo(1);
    assertThat(appEngine.toString()).doesNotContain("cnry-appengine").contains("python312");
    assertThat(Redactor.redact(workflow)).isEqualTo(1);
    assertThat(workflow.toString()).doesNotContain("cnry-workflow").contains("ACTIVE");
    assertThat(Redactor.redact(batch)).isEqualTo(1);
    assertThat(batch.toString()).doesNotContain("cnry-batch");
    assertThat(Redactor.redact(sql)).isEqualTo(1);
    assertThat(sql.toString()).doesNotContain("cnry-sql-key").contains("repl");
    // A Kubernetes object's metadata is names and labels: it stays.
    assertThat(Redactor.redact(pod)).isZero();
    assertThat(pod.at("/spec/template/metadata/labels/app").asString()).isEqualTo("api");
  }

  @Test
  void maskingTwiceCountsOnce() {
    JsonNode n = TestCtx.JSON.readTree("{\"password\":\"x\"}");

    assertThat(Redactor.redact(n)).isEqualTo(1);
    assertThat(Redactor.redact(n)).isZero();
  }
}
