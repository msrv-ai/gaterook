package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The live reads of one resource (report 09 §7 {@code LiveGetTable}): eight routes to the resource itself, and none to
 * the dangerous GETs of the same APIs — Memorystore's {@code authString}, a VM's {@code serialPort}, a bucket's
 * objects, a secret version's {@code :access} — nor to anything an encoded or relative path could reach.
 */
class LiveRoutesTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', textBlock = """
      //compute.googleapis.com/projects/acme-stage/zones/europe-west1-b/instances/api-1          | compute        | https://compute.googleapis.com/compute/v1/projects/acme-stage/zones/europe-west1-b/instances/api-1
      //compute.googleapis.com/projects/acme-stage/regions/europe-west1/subnetworks/default      | compute        | https://compute.googleapis.com/compute/v1/projects/acme-stage/regions/europe-west1/subnetworks/default
      //compute.googleapis.com/projects/acme-stage/global/networks/default                       | compute        | https://compute.googleapis.com/compute/v1/projects/acme-stage/global/networks/default
      //run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api                | run            | https://run.googleapis.com/v2/projects/acme-stage/locations/europe-west1/services/api
      //run.googleapis.com/projects/acme-stage/locations/europe-west1/jobs/nightly                | run            | https://run.googleapis.com/v2/projects/acme-stage/locations/europe-west1/jobs/nightly
      //cloudsql.googleapis.com/projects/acme-stage/instances/orders                              | cloudsql       | https://sqladmin.googleapis.com/v1/projects/acme-stage/instances/orders
      //container.googleapis.com/projects/acme-stage/locations/europe-west1/clusters/main         | container      | https://container.googleapis.com/v1/projects/acme-stage/locations/europe-west1/clusters/main
      //container.googleapis.com/projects/acme-stage/zones/europe-west1-b/clusters/legacy         | container      | https://container.googleapis.com/v1/projects/acme-stage/locations/europe-west1-b/clusters/legacy
      //pubsub.googleapis.com/projects/acme-stage/topics/orders                                   | pubsub         | https://pubsub.googleapis.com/v1/projects/acme-stage/topics/orders
      //pubsub.googleapis.com/projects/acme-stage/subscriptions/orders-worker                     | pubsub         | https://pubsub.googleapis.com/v1/projects/acme-stage/subscriptions/orders-worker
      //storage.googleapis.com/acme-stage-exports                                                 | storage        | https://storage.googleapis.com/storage/v1/b/acme-stage-exports
      //cloudfunctions.googleapis.com/projects/acme-stage/locations/europe-west1/functions/resize | cloudfunctions | https://cloudfunctions.googleapis.com/v2/projects/acme-stage/locations/europe-west1/functions/resize
      //redis.googleapis.com/projects/acme-stage/locations/europe-west1/instances/cache           | redis          | https://redis.googleapis.com/v1/projects/acme-stage/locations/europe-west1/instances/cache
      """)
  void eightServicesHaveARouteToTheResourceItself(String name, String service, String url) {
    Optional<LiveRoutes.Target> t = LiveRoutes.of(name, key -> null);

    assertThat(t).as(name).isPresent();
    assertThat(t.get().service()).isEqualTo(service);
    assertThat(t.get().uri()).isEqualTo(URI.create(url));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // The dangerous GETs of report 09 §1: a password, console output, object data, a secret value.
      "//redis.googleapis.com/projects/acme-stage/locations/europe-west1/instances/cache/authString",
      "//compute.googleapis.com/projects/acme-stage/zones/europe-west1-b/instances/api-1/serialPort",
      "//compute.googleapis.com/projects/acme-stage/zones/europe-west1-b/instances/api-1/serialPortOutput",
      "//storage.googleapis.com/acme-stage-exports/o/customers.csv",
      "//storage.googleapis.com/b/acme-stage-exports/o/customers.csv",
      "//secretmanager.googleapis.com/projects/acme-stage/secrets/db-password/versions/1:access",
      "//secretmanager.googleapis.com/projects/acme-stage/secrets/db-password/versions/latest",
      // Custom methods, sub-resources and collections that aren't resources.
      "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api:getIamPolicy",
      "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api/revisions/api-001",
      "//compute.googleapis.com/projects/acme-stage/zones/europe-west1-b/instances/api-1/getGuestAttributes",
      "//compute.googleapis.com/projects/acme-stage/aggregated/instances",
      "//compute.googleapis.com/projects/acme-stage/zones/europe-west1-b/instances",
      "//compute.googleapis.com/projects/acme-stage/zones/europe-west1-b/getEffectiveFirewalls/api-1",
      "//container.googleapis.com/projects/acme-stage/locations/europe-west1/clusters/main/nodePools/default",
      "//container.googleapis.com/projects/acme-stage/locations/europe-west1/clusters/main/k8s/namespaces/app",
      "//cloudsql.googleapis.com/projects/acme-stage/instances/orders/users",
      // Paths that walk elsewhere, encode, query or carry a fragment.
      "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/../../../../../v1/x",
      "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/..",
      "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/.hidden",
      "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api%2Fx",
      "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api?alt=media",
      "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api#x",
      "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api/",
      "//run.googleapis.com/projects/acme-stage//locations/europe-west1/services/api",
      "//storage.googleapis.com/acme..exports",
      "//redis.googleapis.com/projects/acme-stage/locations/europe-west1/instances/cache@other",
      "//RUN.googleapis.com/projects/acme-stage/locations/europe-west1/services/api",
      "//run.googleapis.com.evil.example/projects/acme-stage/locations/europe-west1/services/api",
      "//iam.googleapis.com/projects/acme-stage/serviceAccounts/gw@acme-stage.iam.gserviceaccount.com",
      "//cloudkms.googleapis.com/projects/acme-stage/locations/global/keyRings/r/cryptoKeys/k"})
  void noRouteReachesAnythingButTheResourceItself(String name) {
    assertThat(LiveRoutes.of(name, key -> null)).as(name).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"gcp.run", "gcp.sqladmin"})
  void aTestEndpointReplacesOnlyTheBase(String key) {
    URI test = URI.create("http://127.0.0.1:4567");
    String name = key.equals("gcp.run") ? "//run.googleapis.com/projects/p/locations/l/services/s"
        : "//cloudsql.googleapis.com/projects/p/instances/i";

    URI uri = LiveRoutes.of(name, k -> k.equals(key) ? test : null).orElseThrow().uri();

    assertThat(uri.toString()).startsWith("http://127.0.0.1:4567/").doesNotContain("googleapis");
    assertThat(uri.getPath()).isIn("/v2/projects/p/locations/l/services/s", "/v1/projects/p/instances/i");
  }
}
