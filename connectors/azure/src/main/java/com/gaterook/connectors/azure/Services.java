package com.gaterook.connectors.azure;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which Azure service a resource, a path or a metric belongs to: the unit of the Cloud policy's Allowed services. A
 * service is a resource provider namespace without {@code Microsoft.} — {@code compute} for
 * {@code Microsoft.Compute}, {@code web} for App Service and Functions — with the other names people use for it
 * ({@code aks}, {@code cosmosdb}, {@code monitor}), so the policy may say either. Resource groups and deployments are
 * {@code resources}. A third-party namespace keeps its whole name with dots as dashes ({@code datadog-monitoring}).
 *
 * <p>Key Vault and Authorization (role assignments) are sensitive: an empty Allowed services list ("every service")
 * still leaves them out; only a list that names one lets agents see it.
 */
final class Services {

  /** Groups of names of one service; the first is its id. */
  private static final List<List<String>> GROUPS = List.of(
      List.of("containerservice", "aks", "kubernetes"),
      List.of("web", "appservice", "functions"),
      List.of("documentdb", "cosmosdb", "cosmos"),
      List.of("dbforpostgresql", "postgresql", "postgres"),
      List.of("dbformysql", "mysql"),
      List.of("insights", "monitor"),
      List.of("operationalinsights", "loganalytics"),
      List.of("app", "containerapps"),
      List.of("containerregistry", "acr"),
      List.of("cache", "redis"),
      List.of("cognitiveservices", "openai", "aiservices"),
      List.of("keyvault", "kv"),
      List.of("authorization", "rbac"),
      List.of("resources", "resourcegroups"));

  /** Never shown unless the policy names them. */
  static final Set<String> SENSITIVE = Set.of("keyvault", "authorization");

  private static final Map<String, String> ID_OF = ids();

  private Services() {}

  private static Map<String, String> ids() {
    Map<String, String> out = new HashMap<>();
    for (List<String> group : GROUPS) {
      for (String name : group) {
        out.put(name, group.getFirst());
      }
    }
    return Map.copyOf(out);
  }

  /** The id of a service as a person writes it in the policy ({@code aks} → {@code containerservice}). */
  static String id(String name) {
    String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    return ID_OF.getOrDefault(n, n);
  }

  static boolean sensitive(String id) {
    return SENSITIVE.contains(id);
  }

  /** The service of a provider namespace: {@code Microsoft.Compute} → {@code compute}. */
  static String ofNamespace(String namespace) {
    String n = namespace.strip().toLowerCase(Locale.ROOT);
    if (n.startsWith("microsoft.")) {
      return id(n.substring("microsoft.".length()));
    }
    return id(n.replace('.', '-'));
  }

  /** The service of a resource type ({@code microsoft.web/sites}), or {@code null} without a namespace. */
  static String ofType(String type) {
    String ns = ResourceIds.namespaceOfType(type);
    return ns == null ? null : ofNamespace(ns);
  }
}
