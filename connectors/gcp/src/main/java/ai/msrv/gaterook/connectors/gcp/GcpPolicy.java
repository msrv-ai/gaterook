package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Cloud section of an environment's policy as GCP reads it (report 09 §6; one section for GCP and AWS, merged by
 * the core, only stricter wins): which services, locations and resources agents may see, the longest time range of a
 * metrics query, data points and items per call. An empty list means "no restriction" (the core's {@code intersect}
 * merge); a list that kept nothing in common holds a value no name matches.
 *
 * <p>{@code regions} are GCP locations here: {@code europe-west1} also allows its zones ({@code europe-west1-b});
 * multi-regions ({@code EU}) and {@code global} are allowed only when listed.
 *
 * @param resources full resource name masks, {@code *} matches any text, {@code /} included
 *                  ({@code //run.googleapis.com/projects/acme-stage/*})
 */
record GcpPolicy(Env env, List<String> allowedServices, List<String> regions, List<String> resources,
    int maxRangeDays, int maxPoints, int maxItems) {

  GcpPolicy {
    allowedServices = List.copyOf(allowedServices);
    regions = List.copyOf(regions);
    resources = List.copyOf(resources);
  }

  /** The defaults of the policy schema: dev and stage 14 days, 2,000 points, 100 items; prod 7, 1,000, 50. */
  static GcpPolicy defaults(Env env) {
    boolean prod = env == Env.PROD;
    return new GcpPolicy(env, List.of(), List.of(), List.of(), prod ? 7 : 14, prod ? 1_000 : 2_000, prod ? 50 : 100);
  }

  static GcpPolicy of(PolicyView view, Env env) {
    GcpPolicy d = defaults(env);
    return new GcpPolicy(env, view.strings("allowedServices"), view.strings("regions"), view.strings("resources"),
        clamp(view.integer("maxRangeDays", d.maxRangeDays()), 1, 455),
        clamp(view.integer("maxPoints", d.maxPoints()), 10, 100_000),
        clamp(view.integer("maxItems", d.maxItems()), 1, 100));
  }

  /** Whether the policy lists services at all. */
  boolean servicesRestricted() {
    return !allowedServices.isEmpty();
  }

  /** The ids of the listed services ({@code sqladmin} → {@code cloudsql}). */
  List<String> allowedIds() {
    Set<String> out = new LinkedHashSet<>();
    for (String s : allowedServices) {
      out.add(Services.id(s));
    }
    return new ArrayList<>(out);
  }

  /** Whether agents may see resources and metrics of {@code service} (an id from {@link Services}). */
  boolean serviceAllowed(String service) {
    String id = Services.id(service);
    if (!servicesRestricted()) {
      return !Services.sensitive(id);
    }
    return allowedIds().contains(id);
  }

  /** Whether a resource in {@code location} may be seen; {@code null} or blank: the location isn't known. */
  boolean regionAllowed(String location) {
    if (regions.isEmpty()) {
      return true;
    }
    if (location == null || location.isBlank()) {
      return false;
    }
    String l = location.toLowerCase(Locale.ROOT);
    for (String r : regions) {
      String region = r.toLowerCase(Locale.ROOT);
      if (region.contains("*") ? Glob.text(region).matches(l)
          : l.equals(region) || (REGION.matcher(region).matches() && l.startsWith(region + "-"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * A region ({@code europe-west1}, {@code northamerica-northeast2}): it lets its zones in ({@code europe-west1-b}).
   * A multi-region ({@code us}, {@code asia}, {@code eur4}) lets in only itself, not the regions that share its prefix.
   */
  private static final java.util.regex.Pattern REGION = java.util.regex.Pattern.compile("^[a-z]+-[a-z]+[0-9]+$");

  boolean resourceAllowed(String fullName) {
    return resources.isEmpty() || Glob.anyText(resources, fullName);
  }

  /** {@code run, compute, cloudsql are allowed on stage}, for denials. */
  String servicesText() {
    List<String> shown = allowedServices.stream().filter(s -> !s.startsWith("\u0000")).toList();
    if (shown.isEmpty()) {
      return "no service is allowed on " + env.id();
    }
    return String.join(", ", shown) + (shown.size() == 1 ? " is" : " are") + " allowed on " + env.id();
  }

  String regionsText() {
    List<String> shown = regions.stream().filter(s -> !s.startsWith("\u0000")).toList();
    return shown.isEmpty() ? "no location is allowed on " + env.id()
        : "locations allowed on " + env.id() + ": " + String.join(", ", shown);
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }
}
