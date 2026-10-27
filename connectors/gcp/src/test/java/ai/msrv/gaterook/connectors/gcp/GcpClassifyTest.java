package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code classify} without I/O (report 09 §3.5): every call is READ or DENY with the rule of the kit's style
 * ({@code stage.cloud.scope_not_allowed}), never MUTATION. Each row: tool, arguments, policy section, the rule or
 * {@code READ}.
 */
class GcpClassifyTest {

  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final GcpConnector connector = new GcpConnector(cloud);
  private final ConnectionConfig stage = Configs.stage();

  @AfterEach
  void close() {
    cloud.close();
  }

  @ParameterizedTest(name = "{0} {1} → {3}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      gcp_describe | {}                                                                       | {} | READ
      gcp_describe | {"scope":"folders/123456","query":"state=ACTIVE"}                         | {} | READ
      gcp_describe | {"scope":"projects/acme-prod"}                                            | {} | scope_not_allowed
      gcp_describe | {"assetTypes":["run.googleapis.com/Service","compute.googleapis.com/Instance"]} | {} | READ
      gcp_describe | {"assetTypes":["secretmanager.googleapis.com/Secret"]}                    | {} | sensitive_type
      gcp_describe | {"assetTypes":["iam.googleapis.com/ServiceAccount"]}                      | {} | sensitive_type
      gcp_describe | {"assetTypes":["cloudkms.googleapis.com/CryptoKey"]}                      | {} | sensitive_type
      gcp_describe | {"assetTypes":["secretmanager.googleapis.com/Secret"]}                    | {"allowedServices":["secretmanager"]} | READ
      gcp_describe | {"assetTypes":["run.googleapis.com/Service"]}                             | {"allowedServices":["compute"]} | service_not_allowed
      gcp_describe | {"assetTypes":["sqladmin.googleapis.com/Instance"]}                       | {"allowedServices":["cloudsql"]} | READ
      gcp_describe | {"name":"//cloudsql.googleapis.com/projects/acme-stage/instances/orders"} | {"allowedServices":["sqladmin"]} | READ
      gcp_describe | {}                                                                       | {"allowedServices":["\\u0000none"]} | service_not_allowed
      gcp_describe | {"assetTypes":["run.googleapis.com.*"]}                                    | {} | bad_input
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api"} | {} | READ
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api"} | {"regions":["europe-west1"]} | READ
      gcp_describe | {"name":"//compute.googleapis.com/projects/acme-stage/zones/europe-west1-b/instances/vm"} | {"regions":["europe-west1"]} | READ
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/locations/us-central1/services/api"} | {"regions":["europe-west1"]} | region_not_allowed
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/locations/us-central1/services/api"} | {"regions":["us"]} | region_not_allowed
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/locations/us/services/api"} | {"regions":["us"]} | READ
      gcp_describe | {"name":"//compute.googleapis.com/projects/acme-stage/zones/europe-west10-a/instances/vm"} | {"regions":["europe-west1"]} | region_not_allowed
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api"} | {"resources":["//run.googleapis.com/projects/acme-stage/*/services/api"]} | READ
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/billing"} | {"resources":["//run.googleapis.com/projects/acme-stage/*/services/api"]} | resource_not_allowed
      gcp_describe | {"name":"//secretmanager.googleapis.com/projects/acme-stage/secrets/db-password"} | {} | sensitive_type
      gcp_describe | {"name":"//secretmanager.googleapis.com/projects/acme-stage/secrets/db-password/versions/1:access"} | {"allowedServices":["secretmanager"]} | bad_name
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api:getIamPolicy"} | {} | bad_name
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/../x"} | {} | bad_name
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage//services/x"}         | {} | bad_name
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/services/a%2Fb"}      | {} | bad_name
      gcp_describe | {"name":"//run.googleapis.com/projects/acme-stage/services/a b"}        | {} | bad_name
      gcp_describe | {"name":"https://run.googleapis.com/v2/projects/acme-stage"}             | {} | bad_name
      gcp_describe | {"orderBy":"name; DROP"}                                                 | {} | bad_input
      gcp_describe | {"limit":500}                                                            | {} | bad_input
      gcp_metrics  | {"metric":"run.googleapis.com/request_count"}                            | {} | READ
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","project":"acme-prod"}      | {} | scope_not_allowed
      gcp_metrics  | {"metric":"run.googleapis.com/../../../acme-prod/metricDescriptors/x"}  | {} | bad_input
      gcp_metrics  | {"metric":"run.googleapis.com/./request_count"}                          | {} | bad_input
      gcp_metrics  | {"metric":"run.googleapis.com//request_count"}                           | {} | bad_input
      gcp_metrics  | {"metric":"run.googleapis.com/request_count/"}                           | {} | bad_input
      gcp_metrics  | {"metric":"run.googleapis.com/request_count/.."}                         | {} | bad_input
      gcp_metrics  | {"metric":"logging.googleapis.com/user/my-metric.v2"}                    | {} | READ
      gcp_metrics  | {"action":"list_metrics"}                                                | {} | READ
      gcp_metrics  | {"action":"list_metrics","prefix":"compute.googleapis.com/"}             | {"allowedServices":["run"]} | service_not_allowed
      gcp_metrics  | {"action":"alerts","state":"CLOSED"}                                     | {} | READ
      gcp_metrics  | {"action":"alerts","state":"ANY"}                                        | {} | bad_input
      gcp_metrics  | {"action":"delete"}                                                      | {} | bad_input
      gcp_metrics  | {}                                                                       | {} | bad_input
      gcp_metrics  | {"metric":"kubernetes.io/container/cpu/core_usage_time"}                 | {"allowedServices":["gke"]} | READ
      gcp_metrics  | {"metric":"cloudsql.googleapis.com/database/cpu/utilization"}            | {"allowedServices":["run"]} | service_not_allowed
      gcp_metrics  | {"metric":"secretmanager.googleapis.com/secret/access_count"}            | {} | sensitive_type
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","filter":"resource.labels.service_name = \\"api\\""} | {} | READ
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","filter":"metric.type = \\"compute.googleapis.com/x\\""} | {} | filter_scope
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","filter":"project = \\"other\\""} | {} | filter_scope
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","filter":"resource.labels.project_id != \\"acme-stage\\""} | {} | filter_scope
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","filter":"metric.labels.text = \\"project = x\\""} | {} | READ
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","filter":"a = 1) OR (resource.type = \\"gce_instance\\""} | {} | filter_syntax
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","filter":"a = \\"1"}        | {} | filter_syntax
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","from":"-15d"}              | {} | range
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","from":"-15d"}              | {"maxRangeDays":30} | READ
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","from":"now","to":"-1h"}    | {} | range
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","from":"1729600000"}        | {} | range
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","period":120}               | {} | bad_input
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","groupBy":["resource.labels.service_name"]} | {} | bad_input
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","groupBy":["resource.labels.service_name"],"reducer":"REDUCE_SUM"} | {} | READ
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","groupBy":["project"],"reducer":"REDUCE_SUM"} | {} | bad_input
      gcp_metrics  | {"metric":"run.googleapis.com/request_count","aligner":"ALIGN_NONE"}     | {} | bad_input
      gcp_create   | {}                                                                       | {} | bad_input
      """)
  void classifiesWithoutIo(String tool, String arguments, String section, String expected) {
    PolicyView policy = Configs.policy(Env.STAGE, section);

    Classification c = connector.classify(Configs.call(tool, "gcp-stage", arguments), stage, policy);

    assertThat(c.kind()).as(arguments).isNotEqualTo(Classification.Kind.MUTATION);
    if (expected.equals("READ")) {
      assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    } else {
      assertThat(c.kind()).as(arguments).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).isEqualTo("stage.cloud." + expected);
      assertThat(c.reason()).isNotBlank();
    }
  }

  @Test
  void aConnectionThatCantBeUsedDeniesEveryCall() {
    ConnectionConfig broken = Configs.of("gcp-broken", Env.DEV, "{\"scopes\":[\"buckets/x\"],\"credentials\":{}}");

    Classification c = connector.classify(Configs.call("gcp_describe", "gcp-broken", "{}"), broken,
        Configs.policy(Env.DEV));

    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo("dev.cloud.config_invalid");
    assertThat(c.reason()).contains("buckets/x");
  }

  @Test
  void aConnectionWithoutAProjectHasNoMetrics() {
    ConnectionConfig folders = Configs.of("gcp-org", Env.DEV, "{\"scopes\":[\"organizations/42\"],\"credentials\":"
        + "{\"source\":\"adc\"}}");

    Classification c = connector.classify(Configs.call("gcp_metrics", "gcp-org", "{\"metric\":\"run.googleapis.com/"
        + "request_count\"}"), folders, Configs.policy(Env.DEV));

    assertThat(c.ruleId()).isEqualTo("dev.cloud.scope_not_allowed");
    assertThat(c.hint()).contains("Metrics projects");
  }

  @Test
  void theDefaultMetricsProjectsAreTheProjectScopes() throws Exception {
    Settings s = Settings.parse(Configs.of("gcp-x", Env.DEV, "{\"scopes\":[\"projects/acme-a\",\"folders/7\","
        + "\"projects/123456789012\"],\"credentials\":{\"source\":\"adc\"}}"));

    assertThat(s.metricsProjects()).containsExactly("acme-a", "123456789012");
    assertThat(s.consumerProject()).isEqualTo("acme-a");
    assertThat(Settings.parse(stage).consumerProject()).as("the impersonated account's project")
        .isEqualTo("acme-stage");
  }

  @Test
  void prodLowersLimitsAndShortensTheRange() throws Exception {
    ConnectionConfig prod = Configs.prod();
    GcpPolicy p = GcpPolicy.of(Configs.policy(Env.PROD), Env.PROD);
    Settings s = Settings.parse(prod);

    DescribeRequest d = DescribeRequest.of(TestCtx.JSON.readTree("{\"limit\":80}"), "gcp-prod", s, p);
    MetricsRequest m = MetricsRequest.of(TestCtx.JSON.readTree("{\"metric\":\"run.googleapis.com/request_count\"}"),
        "gcp-prod", s, p, java.time.Instant.parse("2026-10-22T12:30:00Z"));

    assertThat(d.limit()).isEqualTo(50);
    assertThat(d.notes()).contains("limit lowered to 50 by the prod policy.");
    assertThat(DescribeRequest.of(TestCtx.JSON.readTree("{}"), "gcp-prod", s, p).limit()).isEqualTo(10);
    assertThat(m.limit()).isEqualTo(10);
    assertThat(m.period()).as("1 h in at most 360 points").isEqualTo(60);
    assertThat(m.from()).isEqualTo(java.time.Instant.parse("2026-10-22T11:30:00Z"));
    assertThat(connector.classify(Configs.call("gcp_metrics", "gcp-prod", "{\"metric\":\"run.googleapis.com/"
        + "request_count\",\"from\":\"-8d\"}"), prod, Configs.policy(Env.PROD)).ruleId())
        .isEqualTo("prod.cloud.range");
  }

  @Test
  void theAutomaticPeriodKeepsASeriesUnder360Points() {
    assertThat(MetricsRequest.autoPeriod(java.time.Duration.ofHours(1))).isEqualTo(60);
    assertThat(MetricsRequest.autoPeriod(java.time.Duration.ofHours(24))).isEqualTo(300);
    assertThat(MetricsRequest.autoPeriod(java.time.Duration.ofDays(3))).isEqualTo(900);
    assertThat(MetricsRequest.autoPeriod(java.time.Duration.ofDays(14))).isEqualTo(3_600);
    assertThat(MetricsRequest.autoPeriod(java.time.Duration.ofDays(400))).isEqualTo(86_400);
  }
}
