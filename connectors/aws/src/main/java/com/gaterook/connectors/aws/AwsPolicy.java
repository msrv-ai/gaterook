package com.gaterook.connectors.aws;

import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Cloud section of an environment's policy as AWS reads it (report 09 §6; one section for GCP and AWS, merged by
 * the core, only stricter wins): which services, regions and resources agents may see, the longest time range of a
 * metrics query, data points and items per call. An empty list means "no restriction" (the core's {@code intersect}
 * merge), except that sensitive services need to be named; a list that kept nothing in common holds a value no name
 * matches ({@link PolicyView#NOTHING}). A services list an endpoint or a tool override narrowed from an empty one
 * ({@code x-empty: connector-default}, marked {@link PolicyView#WITHIN_DEFAULT}) stays inside what the empty list
 * allows: its sensitive services are dropped and its session opens no service the default session doesn't.
 *
 * @param allowedServices IAM prefixes as listed ({@code ec2}, {@code rds}); sensitive ones only when named here
 * @param resources       ARN masks; {@code *} matches any text, {@code :} and {@code /} included. Agents see nothing
 *                        outside them, so a resource whose ARN is unknown is hidden too
 */
record AwsPolicy(Env env, List<String> allowedServices, List<String> regions, List<String> resources,
    int maxRangeDays, int maxPoints, int maxItems) {

  /** What the session policy may name: an IAM prefix. */
  private static final Pattern IAM_PREFIX = Pattern.compile("^[a-z0-9-]{1,40}$");

  AwsPolicy {
    allowedServices = List.copyOf(allowedServices);
    regions = List.copyOf(regions);
    resources = List.copyOf(resources);
  }

  /** The defaults of the policy schema: dev and stage 14 days, 2,000 points, 100 items; prod 7, 1,000, 50. */
  static AwsPolicy defaults(Env env) {
    boolean prod = env == Env.PROD;
    return new AwsPolicy(env, List.of(), List.of(), List.of(), prod ? 7 : 14, prod ? 1_000 : 2_000, prod ? 50 : 100);
  }

  static AwsPolicy of(PolicyView view, Env env) {
    AwsPolicy d = defaults(env);
    return new AwsPolicy(env, services(view), view.strings("regions"), view.strings("resources"),
        clamp(view.integer("maxRangeDays", d.maxRangeDays()), 1, 455),
        clamp(view.integer("maxPoints", d.maxPoints()), 10, 100_000),
        clamp(view.integer("maxItems", d.maxItems()), 1, 100));
  }

  /**
   * The services list as the core merged it. Narrowed from an empty list, it keeps no sensitive service (an empty list
   * never shows them) and keeps the marker, so the session stays inside the default one; a list left with no service
   * allows none.
   */
  private static List<String> services(PolicyView view) {
    List<String> listed = view.strings("allowedServices");
    if (!listed.removeIf(PolicyView.WITHIN_DEFAULT::equals)) {
      return listed;
    }
    listed.removeIf(s -> !s.startsWith("\u0000") && Services.sensitive(Services.id(s)));
    if (listed.isEmpty()) {
      listed.add(PolicyView.NOTHING);
    }
    listed.add(PolicyView.WITHIN_DEFAULT);
    return listed;
  }

  boolean servicesRestricted() {
    return !allowedServices.isEmpty();
  }

  /** Whether the services list was narrowed from an empty one, so it stays inside what an empty list allows. */
  private boolean withinDefault() {
    return allowedServices.contains(PolicyView.WITHIN_DEFAULT);
  }

  /** The listed services as IAM prefixes ({@code elbv2} → {@code elasticloadbalancing}). */
  List<String> allowedIds() {
    Set<String> out = new LinkedHashSet<>();
    for (String s : allowedServices) {
      out.add(Services.id(s));
    }
    return new ArrayList<>(out);
  }

  /**
   * Whether agents may see resources and metrics of {@code service}; {@code null} is a custom metric namespace, which
   * only an environment that lists no services shows (the list names AWS services, never someone's own metrics).
   */
  boolean serviceAllowed(String service) {
    if (!servicesRestricted()) {
      return service == null || !Services.sensitive(service);
    }
    return service != null && allowedIds().contains(service);
  }

  boolean regionAllowed(String region) {
    if (regions.isEmpty()) {
      return true;
    }
    String r = region.toLowerCase(Locale.ROOT);
    for (String allowed : regions) {
      String a = allowed.toLowerCase(Locale.ROOT);
      if (a.contains("*") ? Glob.text(a).matches(r) : a.equals(r)) {
        return true;
      }
    }
    return false;
  }

  boolean resourcesRestricted() {
    return !resources.isEmpty();
  }

  /** Whether a resource with this ARN may be seen; with masks set, an unknown ARN may not. */
  boolean resourceAllowed(String arn) {
    if (!resourcesRestricted()) {
      return true;
    }
    return arn != null && Glob.anyText(resources, arn);
  }

  /** The services the generated session policy allows: the listed ones, else the common read-only set. */
  List<String> sessionServices() {
    if (!servicesRestricted()) {
      return Services.DEFAULT_SESSION;
    }
    return allowedIds().stream().filter(s -> IAM_PREFIX.matcher(s).matches())
        .filter(s -> !withinDefault() || Services.DEFAULT_SESSION.contains(s)).toList();
  }

  /** {@code ec2, rds, sqs are allowed on stage}, for denials. */
  String servicesText() {
    List<String> shown = allowedServices.stream().filter(s -> !s.startsWith("\u0000")).toList();
    if (shown.isEmpty()) {
      return "no service is allowed on " + env.id();
    }
    return String.join(", ", shown) + (shown.size() == 1 ? " is" : " are") + " allowed on " + env.id();
  }

  String regionsText() {
    List<String> shown = regions.stream().filter(s -> !s.startsWith("\u0000")).toList();
    return shown.isEmpty() ? "no region is allowed on " + env.id()
        : "regions allowed on " + env.id() + ": " + String.join(", ", shown);
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }
}
