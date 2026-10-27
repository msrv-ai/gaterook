package ai.msrv.gaterook.connectors.gcp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which Google Cloud service a resource, an asset type or a metric belongs to (report 09 §6 "Service catalogue"): the
 * unit of the policy's Allowed services. A service has one id and the other names people and Google use for it —
 * {@code cloudsql} is {@code sqladmin.googleapis.com} for Cloud Asset and {@code cloudsql.googleapis.com} in resource
 * names and metrics; GKE is {@code container}, {@code k8s.io} asset types and {@code kubernetes.io} metrics — so the
 * policy may say either. This is code, not policy: the policy only lists ids.
 *
 * <p>Secret Manager, IAM and Cloud KMS are sensitive: an empty Allowed services list ("every service") still leaves
 * them out; only a list that names one of them lets agents see its resources (report 09 §3.5).
 */
final class Services {

  /** Groups of names of one service; the first is its id. */
  private static final List<List<String>> GROUPS = List.of(
      List.of("cloudsql", "sqladmin"),
      List.of("container", "gke", "kubernetes", "k8s"),
      List.of("cloudfunctions", "functions"),
      List.of("storage", "gcs"),
      List.of("secretmanager"),
      List.of("iam", "iamcredentials"),
      List.of("cloudkms", "kms"));

  /** Never shown unless the policy names them. */
  static final Set<String> SENSITIVE = Set.of("secretmanager", "iam", "cloudkms");

  private static final Map<String, String> ID_OF = ids();

  private Services() {}

  private static Map<String, String> ids() {
    java.util.HashMap<String, String> out = new java.util.HashMap<>();
    for (List<String> group : GROUPS) {
      for (String name : group) {
        out.put(name, group.getFirst());
      }
    }
    return Map.copyOf(out);
  }

  /** The id of a service as a person writes it in the policy ({@code sqladmin} → {@code cloudsql}). */
  static String id(String name) {
    String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    return ID_OF.getOrDefault(n, n);
  }

  static boolean sensitive(String id) {
    return SENSITIVE.contains(id);
  }

  /**
   * The service of a host: {@code run.googleapis.com} → {@code run}, {@code cloudsql.googleapis.com} →
   * {@code cloudsql}, {@code k8s.io} / {@code apps.k8s.io} / {@code kubernetes.io} → {@code container}.
   */
  static String ofHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (h.equals("k8s.io") || h.endsWith(".k8s.io") || h.equals("kubernetes.io")) {
      return "container";
    }
    int dot = h.indexOf('.');
    return id(dot < 0 ? h : h.substring(0, dot));
  }

  /** The service of an asset type ({@code run.googleapis.com/Service}) or a metric type, by the part before "/". */
  static String ofType(String type) {
    int slash = type.indexOf('/');
    return ofHost(slash < 0 ? type : type.substring(0, slash));
  }

  /** The service of a full resource name {@code //run.googleapis.com/projects/…}. */
  static String ofName(String fullName) {
    String rest = fullName.startsWith("//") ? fullName.substring(2) : fullName;
    int slash = rest.indexOf('/');
    return ofHost(slash < 0 ? rest : rest.substring(0, slash));
  }

  /**
   * The {@code assetTypes} of a search limited to {@code services} (Cloud Asset reads them as RE2 patterns): the
   * asset hosts each service's resources have, e.g. {@code sqladmin.googleapis.com.*} for {@code cloudsql}.
   */
  static List<String> assetTypePatterns(List<String> services) {
    Set<String> out = new LinkedHashSet<>();
    for (String s : services) {
      String id = id(s);
      switch (id) {
        case "cloudsql" -> out.add("sqladmin.googleapis.com.*");
        case "container" -> {
          out.add("container.googleapis.com.*");
          out.add(".*k8s.io/.*");
        }
        default -> {
          if (id.matches("[a-z0-9-]{1,63}")) {
            out.add(id + ".googleapis.com.*");
          }
        }
      }
    }
    return new ArrayList<>(out);
  }
}
