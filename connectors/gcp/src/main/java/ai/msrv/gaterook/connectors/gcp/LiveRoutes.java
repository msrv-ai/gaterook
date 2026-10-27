package ai.msrv.gaterook.connectors.gcp;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The live GET of one resource (report 09 §7 {@code LiveGetTable}): eight services whose current state the gateway
 * reads from the service's own API instead of the inventory snapshot, which can be minutes old.
 *
 * <p>The URL is built by the gateway from a full resource name that matched one of these anchored patterns; an agent
 * never supplies a URL. That is what keeps the dangerous GETs of these very APIs out of reach (report 09 §1, layer 2):
 * a Memorystore instance's {@code …/authString} (its password), a VM's {@code …/serialPort} output, a bucket's objects
 * {@code b/{b}/o/{o}} (data), a secret version's {@code :access}. Every pattern ends at the resource itself
 * ({@code $}), segments are unreserved URL characters without {@code :} (no custom methods) and start with a letter
 * or digit (no {@code .} or {@code ..}), and Compute names only collections of resources, so no extra path segment,
 * method, query or encoded character can follow.
 */
final class LiveRoutes {

  /** A path segment: unreserved characters, no {@code :}, {@code @}, {@code %}; never {@code .} or {@code ..}. */
  private static final String SEG = "[A-Za-z0-9][A-Za-z0-9._~-]{0,254}";
  /** Project id or number in a resource name. */
  private static final String PROJECT = "[a-z0-9][a-z0-9-]{0,62}";
  /** Region, zone or location. */
  private static final String LOCATION = "[a-z0-9][a-z0-9-]{0,62}";
  /** Compute collections a resource of which may be read; anything else (methods, sub-resources) has no route. */
  private static final String COMPUTE = "(?:instances|disks|instanceGroups|instanceGroupManagers|autoscalers"
      + "|instanceTemplates|networks|subnetworks|firewalls|routers|routes|addresses|forwardingRules|backendServices"
      + "|backendBuckets|healthChecks|urlMaps|targetHttpProxies|targetHttpsProxies|targetPools|sslCertificates"
      + "|sslPolicies|images|snapshots|machineImages|networkEndpointGroups|securityPolicies|vpnGateways|vpnTunnels"
      + "|targetVpnGateways|interconnectAttachments|reservations|resourcePolicies|nodeGroups|nodeTemplates)";

  /**
   * One service's route.
   *
   * @param service     the service id ({@link Services})
   * @param endpointKey the test endpoint key in {@code gateway.cloud.endpoints}
   * @param base        the production base URL
   * @param prefix      the API path before the captured resource path
   */
  record Route(String service, String endpointKey, URI base, String prefix, Pattern pattern) {}

  /** Where one live GET goes. */
  record Target(String service, URI uri) {}

  static final List<Route> ROUTES = List.of(
      route("compute", "compute", "/compute/v1/", "^//compute\\.googleapis\\.com/(projects/" + PROJECT
          + "/(?:zones|regions)/" + LOCATION + "/" + COMPUTE + "/" + SEG + "|projects/" + PROJECT + "/global/" + COMPUTE
          + "/" + SEG + ")$"),
      route("run", "run", "/v2/", "^//run\\.googleapis\\.com/(projects/" + PROJECT + "/locations/" + LOCATION
          + "/(?:services|jobs)/" + SEG + ")$"),
      route("cloudsql", "sqladmin", "/v1/", "^//cloudsql\\.googleapis\\.com/(projects/" + PROJECT + "/instances/" + SEG
          + ")$"),
      route("container", "container", "/v1/", "^//container\\.googleapis\\.com/(projects/" + PROJECT
          + "/(?:locations|zones)/" + LOCATION + "/clusters/" + SEG + ")$"),
      route("pubsub", "pubsub", "/v1/", "^//pubsub\\.googleapis\\.com/(projects/" + PROJECT
          + "/(?:topics|subscriptions)/" + SEG + ")$"),
      route("storage", "storage", "/storage/v1/b/", "^//storage\\.googleapis\\.com/([a-z0-9][a-z0-9._-]{1,221}"
          + "[a-z0-9])$"),
      route("cloudfunctions", "cloudfunctions", "/v2/", "^//cloudfunctions\\.googleapis\\.com/(projects/" + PROJECT
          + "/locations/" + LOCATION + "/functions/" + SEG + ")$"),
      route("redis", "redis", "/v1/", "^//redis\\.googleapis\\.com/(projects/" + PROJECT + "/locations/" + LOCATION
          + "/instances/" + SEG + ")$"));

  private LiveRoutes() {}

  private static Route route(String service, String api, String prefix, String regex) {
    return new Route(service, "gcp." + api, URI.create("https://" + api + ".googleapis.com"), prefix,
        Pattern.compile(regex));
  }

  /**
   * The live GET of {@code fullName}, or empty when it has no route (the answer is then the inventory snapshot).
   *
   * @param testBase the base URL of a test endpoint by key, or {@code null} for the real service
   */
  static Optional<Target> of(String fullName, Function<String, URI> testBase) {
    if (fullName == null || fullName.contains("..")) {
      return Optional.empty();
    }
    for (Route r : ROUTES) {
      Matcher m = r.pattern().matcher(fullName);
      if (!m.matches()) {
        continue;
      }
      String path = m.group(1);
      // GKE names zonal clusters with zones/…; the v1 API reads every cluster under locations/….
      if (r.service().equals("container")) {
        path = path.replaceFirst("^(projects/[^/]+)/zones/", "$1/locations/");
      }
      URI test = testBase.apply(r.endpointKey());
      URI base = test != null ? test : r.base();
      String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1)
          : base.toString();
      return Optional.of(new Target(r.service(), URI.create(root + r.prefix() + path)));
    }
    return Optional.empty();
  }

  /** The services with a live read, for describe and the tool description. */
  static List<String> services() {
    return ROUTES.stream().map(Route::service).toList();
  }
}
