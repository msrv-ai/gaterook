package com.gaterook.connectors.azure;

import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Cloud section of an environment's policy as Azure reads it (the section GCP and AWS share, merged by the core,
 * only stricter wins): the services (provider namespaces, {@link Services}), locations and resource ID masks agents
 * may see, the longest time range of a metrics, alerts or Activity Log read, data points and items per call. An empty
 * list means "no restriction", except that sensitive services need to be named; a list that kept nothing in common
 * holds a value no name matches ({@link PolicyView#NOTHING}).
 *
 * <p>Locations are Azure's names ({@code westeurope}, {@code eastus2}), compared without case and spaces
 * ({@code West Europe}); {@code global} is allowed only when listed. Resource masks are ARM resource IDs with
 * {@code *} ({@code /subscriptions/*}{@code /resourceGroups/stage-*}), compared without case, as ARM does; a mask that
 * matches a resource also covers what is inside it (its configuration, its actions).
 */
record AzurePolicy(Env env, List<String> allowedServices, List<String> regions, List<String> resources,
    int maxRangeDays, int maxPoints, int maxItems) {

  AzurePolicy {
    allowedServices = List.copyOf(allowedServices);
    regions = List.copyOf(regions);
    resources = List.copyOf(resources);
  }

  /** The defaults of the policy schema: dev and stage 14 days, 2,000 points, 100 items; prod 7, 1,000, 50. */
  static AzurePolicy defaults(Env env) {
    boolean prod = env == Env.PROD;
    return new AzurePolicy(env, List.of(), List.of(), List.of(), prod ? 7 : 14, prod ? 1_000 : 2_000,
        prod ? 50 : 100);
  }

  static AzurePolicy of(PolicyView view, Env env) {
    AzurePolicy d = defaults(env);
    return new AzurePolicy(env, services(view), view.strings("regions"), view.strings("resources"),
        clamp(view.integer("maxRangeDays", d.maxRangeDays()), 1, 455),
        clamp(view.integer("maxPoints", d.maxPoints()), 10, 100_000),
        clamp(view.integer("maxItems", d.maxItems()), 1, 100));
  }

  private static List<String> services(PolicyView view) {
    List<String> listed = view.strings("allowedServices");
    if (!listed.removeIf(PolicyView.WITHIN_DEFAULT::equals)) {
      return listed;
    }
    listed.removeIf(s -> !s.startsWith("\u0000") && Services.sensitive(Services.id(s)));
    if (listed.isEmpty()) {
      listed.add(PolicyView.NOTHING);
    }
    return listed;
  }

  boolean servicesRestricted() {
    return !allowedServices.isEmpty();
  }

  /** Whether services, locations or resources are restricted: a result must then say what each row is. */
  boolean restricted() {
    return servicesRestricted() || !regions.isEmpty() || !resources.isEmpty();
  }

  List<String> allowedIds() {
    Set<String> out = new LinkedHashSet<>();
    for (String s : allowedServices) {
      out.add(Services.id(s));
    }
    return new ArrayList<>(out);
  }

  /** Whether agents may see resources and metrics of {@code service} (an id of {@link Services}). */
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
    String l = normal(location);
    for (String r : regions) {
      if (r.startsWith("\u0000")) {
        continue;
      }
      String region = normal(r);
      if (region.contains("*") ? Glob.text(region).matches(l) : l.equals(region)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the resource at {@code path} (an ARM ID, maybe with more segments) matches a mask. */
  boolean resourceAllowed(ResourceIds.Parsed path) {
    if (resources.isEmpty()) {
      return true;
    }
    List<String> masks = resources.stream().filter(m -> !m.startsWith("\u0000"))
        .map(m -> m.toLowerCase(Locale.ROOT)).toList();
    for (String prefix : path.prefixes()) {
      if (Glob.anyText(masks, prefix)) {
        return true;
      }
    }
    return false;
  }

  /** {@code compute, web are allowed on stage}, for denials. */
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

  private static String normal(String location) {
    return location.toLowerCase(Locale.ROOT).replace(" ", "");
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }
}
