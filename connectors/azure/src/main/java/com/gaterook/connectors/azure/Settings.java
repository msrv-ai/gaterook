package com.gaterook.connectors.azure;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connectors.cloudauth.AzureSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * An Azure connection as its form writes it: the cloud and tenant, the subscriptions agents may read, the Entra ID
 * credentials, and the Log Analytics workspaces and Application Insights components the shared log tools read.
 * Nothing here is a secret: a client secret or certificate is a secret field the call resolves.
 *
 * @param knownTenant the tenant Test connection found in the token ({@code metadata.tenant}), or {@code null}
 */
record Settings(AzureSpec credentials, List<String> subscriptions, List<String> workspaces,
    List<String> applicationInsights, String knownTenant) {

  static final Pattern GUID = Pattern.compile("^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$");
  static final int MAX_SUBSCRIPTIONS = 20;
  static final int MAX_LOG_SOURCES = 20;

  /** Settings that can't be used; every call of the connection is denied with this message. */
  static final class InvalidSettingsException extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidSettingsException(String message) {
      super(message);
    }
  }

  Settings {
    subscriptions = List.copyOf(subscriptions);
    workspaces = List.copyOf(workspaces);
    applicationInsights = List.copyOf(applicationInsights);
  }

  static Settings parse(ConnectionConfig cfg) throws InvalidSettingsException {
    JsonNode c = cfg.config();
    if (c == null || !c.isObject()) {
      throw new InvalidSettingsException("The connection has no settings.");
    }
    AzureSpec spec;
    try {
      spec = AzureSpec.fromConfig(c, "/credentials");
    } catch (IllegalArgumentException e) {
      throw new InvalidSettingsException(e.getMessage());
    }
    List<String> subscriptions = guids(c.path("subscriptions"), "subscription ID");
    if (subscriptions.isEmpty()) {
      throw new InvalidSettingsException("Add at least one subscription ID under Subscriptions.");
    }
    if (subscriptions.size() > MAX_SUBSCRIPTIONS) {
      throw new InvalidSettingsException("Name at most " + MAX_SUBSCRIPTIONS + " subscriptions.");
    }
    List<String> workspaces = guids(c.path("logAnalyticsWorkspaces"), "Log Analytics workspace ID");
    List<String> components = new ArrayList<>();
    for (JsonNode n : c.path("applicationInsights")) {
      String id = n.asString("").strip();
      ResourceIds.Parsed p = ResourceIds.parse(id);
      if (p == null || !p.isResource() || !"microsoft.insights/components".equals(p.type())) {
        throw new InvalidSettingsException("\"" + abbreviate(id) + "\" isn't an Application Insights resource ID "
            + "(/subscriptions/…/resourceGroups/…/providers/Microsoft.Insights/components/<name>).");
      }
      if (!subscriptions.contains(p.subscription())) {
        throw new InvalidSettingsException("The Application Insights component " + p.name() + " is in subscription "
            + p.subscription() + ", which isn't one of the connection's subscriptions.");
      }
      components.add(id);
    }
    if (workspaces.size() + components.size() > MAX_LOG_SOURCES) {
      throw new InvalidSettingsException("Name at most " + MAX_LOG_SOURCES + " workspaces and components together.");
    }
    String known = null;
    if (cfg.metadata() != null && cfg.metadata().path("tenant").isString()) {
      String t = cfg.metadata().path("tenant").asString().toLowerCase(Locale.ROOT);
      known = GUID.matcher(t).matches() ? t : null;
    }
    return new Settings(spec, subscriptions, workspaces, List.copyOf(new LinkedHashSet<>(components)), known);
  }

  /**
   * The tenant every token must belong to: the configured directory ID, else the one Test connection found; or
   * {@code null} when neither is known yet (calls then ask for a test first).
   */
  String pinnedTenant() {
    return credentials.tenantIsGuid() ? credentials.tenantId() : knownTenant;
  }

  boolean hasSubscription(String subscription) {
    return subscription != null && subscriptions.contains(subscription.toLowerCase(Locale.ROOT));
  }

  /** Whether the shared log tools have anything to read. */
  boolean hasLogs() {
    return !workspaces.isEmpty() || !applicationInsights.isEmpty();
  }

  /** {@code 1f2e… +2 · AzureCloud}: where the connection reads, for texts. */
  String target() {
    String first = subscriptions.getFirst();
    return (subscriptions.size() == 1 ? first : first + " +" + (subscriptions.size() - 1)) + " · "
        + credentials.cloud().id();
  }

  private static List<String> guids(JsonNode array, String what) throws InvalidSettingsException {
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode n : array) {
      String v = n.asString("").strip().toLowerCase(Locale.ROOT);
      if (!GUID.matcher(v).matches()) {
        throw new InvalidSettingsException("\"" + abbreviate(v) + "\" isn't a " + what + " (a GUID like "
            + "0b1f6471-1bf0-4dda-aec3-111122223333).");
      }
      out.add(v);
    }
    return new ArrayList<>(out);
  }

  static String abbreviate(String s) {
    return s.length() <= 60 ? s : s.substring(0, 60) + "…";
  }
}
