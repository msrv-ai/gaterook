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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code classify} of {@code gcp_api_read} and {@code gcp_api_change} without I/O and without a Discovery document in
 * memory: READ for a read, MUTATION for a change, or DENY with the rule of the kit's style. Each row: tool,
 * arguments, policy section, the rule, {@code READ} or {@code MUTATION}. The connection is a dev connection of one
 * project, so a project outside it is refused at once (with folder scopes, {@code execute} looks it up).
 */
class GcpApiClassifyTest {

  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final GcpConnector connector = new GcpConnector(cloud);
  private final ConnectionConfig dev = Configs.of("gcp-dev", Env.DEV, """
      {"scopes":["projects/acme-dev"],"credentials":{"source":"adc"}}""");

  @AfterEach
  void close() {
    connector.close();
    cloud.close();
  }

  @ParameterizedTest(name = "{0} {1} {2} → {3}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/-"}} | {} | READ
      gcp_api_read   | {"service":"run","method":"run.projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/europe-west1"}} | {} | READ
      gcp_api_read   | {"service":"compute","method":"instances.list","params":{"project":"acme-dev","zone":"europe-west1-b"}} | {} | READ
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-prod/locations/-"}} | {} | scope_not_allowed
      gcp_api_read   | {"service":"compute","method":"instances.list","params":{"project":"acme-prod","zone":"europe-west1-b"}} | {} | scope_not_allowed
      gcp_api_read   | {"service":"storage","method":"objects.list","params":{"bucket":"acme-dev-uploads"}} | {} | READ
      gcp_api_read   | {"service":"run","method":"projects.locations.list","params":{}}                       | {} | no_scope
      gcp_api_read   | {"service":"cloudbilling","method":"billingAccounts.get","params":{"name":"billingAccounts/0X0X0X-0X0X0X-0X0X0X"}} | {} | scope_not_allowed
      gcp_api_read   | {"service":"cloudresourcemanager","version":"v3","method":"folders.get","params":{"name":"folders/42"}} | {} | scope_not_allowed
      gcp_api_read   | {"service":"secretmanager","method":"projects.secrets.versions.access","params":{"name":"projects/acme-dev/secrets/db/versions/1"}} | {"allowedServices":["secretmanager"]} | method_not_allowed
      gcp_api_read   | {"service":"secretmanager","method":"projects.secrets.list","params":{"parent":"projects/acme-dev"}} | {} | sensitive_type
      gcp_api_read   | {"service":"secretmanager","method":"projects.secrets.list","params":{"parent":"projects/acme-dev"}} | {"allowedServices":["secretmanager"]} | READ
      gcp_api_read   | {"service":"redis","method":"projects.locations.instances.getAuthString","params":{"name":"projects/acme-dev/locations/europe-west1/instances/cache"}} | {} | method_not_allowed
      gcp_api_read   | {"service":"compute","method":"instances.getSerialPortOutput","params":{"project":"acme-dev","zone":"europe-west1-b","instance":"vm"}} | {} | method_not_allowed
      gcp_api_read   | {"service":"iamcredentials","method":"projects.serviceAccounts.generateAccessToken","params":{"name":"projects/-/serviceAccounts/sa@acme-dev.iam.gserviceaccount.com"}} | {} | method_not_allowed
      gcp_api_read   | {"service":"cloudasset","method":"searchAllResources","params":{"scope":"projects/acme-dev"}} | {} | method_not_allowed
      gcp_api_read   | {"service":"logging","method":"entries.list","params":{}}                             | {} | method_not_allowed
      gcp_api_read   | {"service":"bigquery","method":"tabledata.list","params":{"projectId":"acme-dev","datasetId":"d","tableId":"t"}} | {} | method_not_allowed
      gcp_api_read   | {"service":"bigquery","method":"tables.list","params":{"projectId":"acme-dev","datasetId":"d"}} | {} | READ
      gcp_api_read   | {"service":"monitoring","method":"projects.timeSeries.list","params":{"name":"projects/acme-dev"}} | {} | method_not_allowed
      gcp_api_read   | {"service":"monitoring","method":"projects.alertPolicies.list","params":{"name":"projects/acme-dev"}} | {} | READ
      gcp_api_read   | {"service":"cloudbuild","method":"projects.locations.connections.fetchReadToken","params":{"connection":"projects/acme-dev/locations/europe-west1/connections/gh"}} | {} | method_not_allowed
      gcp_api_read   | {"service":"storage","method":"objects.get","params":{"bucket":"acme-dev-uploads","object":"a.txt","alt":"media"}} | {} | param_not_allowed
      gcp_api_read   | {"service":"storage","method":"objects.get","params":{"bucket":"acme-dev-uploads","object":"a.txt","alt":"json"}} | {} | READ
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/-","key":"AIza"}} | {} | param_not_allowed
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/-","access_token":"ya29.x"}} | {} | param_not_allowed
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/-"}} | {"regions":["europe-west1"]} | region_not_allowed
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/us-central1"}} | {"regions":["europe-west1"]} | region_not_allowed
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/europe-west1"}} | {"regions":["europe-west1"]} | READ
      gcp_api_read   | {"service":"compute","method":"instances.list","params":{"project":"acme-dev","zone":"us-east1-b"}} | {"regions":["europe-west1"]} | region_not_allowed
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/-"}} | {"allowedServices":["compute"]} | service_not_allowed
      gcp_api_read   | {"service":"iam","method":"projects.serviceAccounts.list","params":{"name":"projects/acme-dev"}} | {} | sensitive_type
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":["projects/acme-dev"]}} | {} | READ
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":{"x":1}}} | {} | bad_input
      gcp_api_read   | {"service":"Run","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/-"}} | {} | bad_input
      gcp_api_read   | {"service":"run","method":"projects/../x","params":{"parent":"projects/acme-dev/locations/-"}} | {} | bad_input
      gcp_api_read   | {"service":"run","version":"2","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/-"}} | {} | bad_input
      gcp_api_read   | {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-dev/locations/-"},"body":"x"} | {} | bad_input
      gcp_api_change | {"service":"run","method":"projects.locations.services.patch","params":{"name":"projects/acme-dev/locations/europe-west1/services/api","updateMask":"traffic"},"body":{"traffic":[]}} | {} | MUTATION
      gcp_api_change | {"service":"compute","method":"instances.reset","params":{"project":"acme-dev","zone":"europe-west1-b","instance":"vm"}} | {} | MUTATION
      gcp_api_change | {"service":"compute","method":"instances.reset","params":{"project":"acme-prod","zone":"europe-west1-b","instance":"vm"}} | {} | scope_not_allowed
      gcp_api_change | {"service":"iam","method":"projects.serviceAccounts.keys.create","params":{"name":"projects/acme-dev/serviceAccounts/sa@acme-dev.iam.gserviceaccount.com"}} | {"allowedServices":["iam"]} | method_not_allowed
      gcp_api_change | {"service":"storage","method":"projects.hmacKeys.create","params":{"projectId":"acme-dev","serviceAccountEmail":"sa@acme-dev.iam.gserviceaccount.com"}} | {} | method_not_allowed
      gcp_api_change | {"service":"bigquery","method":"jobs.query","params":{"projectId":"acme-dev"},"body":{"query":"DELETE FROM t WHERE true"}} | {} | method_not_allowed
      gcp_api_change | {"service":"run","method":"projects.locations.services.delete","params":{"name":"projects/acme-dev/locations/europe-west1/services/api"}} | {"regions":["us-central1"]} | region_not_allowed
      """)
  void classifiesWithoutIo(String tool, String arguments, String section, String expected) {
    PolicyView policy = section.equals("{}") ? Configs.policy(Env.DEV) : Configs.policy(Env.DEV, section);
    Classification c = connector.classify(Configs.call(tool, dev.name(), arguments), dev, policy);

    switch (expected) {
      case "READ" -> assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
      case "MUTATION" -> {
        assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.MUTATION);
        assertThat(c.reason()).startsWith("Calls ");
        assertThat(c.facts().path("gcp.service").asString()).isNotEmpty();
      }
      default -> {
        assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.DENY);
        assertThat(c.ruleId()).isEqualTo("dev.cloud." + expected);
        assertThat(c.reason()).isNotBlank();
      }
    }
  }
}
