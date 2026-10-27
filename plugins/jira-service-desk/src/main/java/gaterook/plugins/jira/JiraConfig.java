package gaterook.plugins.jira;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A connection's non-secret settings as the configSchema of the manifest defines them: the site, Cloud or Data Center,
 * the email of a Cloud token, the service desks the connection is limited to and the HTTP timeout. The secret is only
 * named ({@link #TOKEN}); its value comes through the call's {@code SecretResolver}.
 *
 * @param base           the site without a trailing slash; a Data Center may live under a context path
 * @param serviceDeskIds the allowlist; empty means every desk the account sees
 */
record JiraConfig(URI base, boolean cloud, String email, List<Long> serviceDeskIds, int timeoutMs) {

  /** The secret field: the API token (Cloud) or personal access token (Data Center). */
  static final String TOKEN = "/apiToken";

  static JiraConfig of(ConnectionConfig cfg) throws ConnectorException {
    String url = cfg.string("/baseUrl");
    if (url == null || url.isBlank()) {
      throw ConnectorException.badInput("The connection has no site URL.");
    }
    URI base;
    try {
      base = new URI(url.strip().replaceAll("/+$", ""));
    } catch (URISyntaxException e) {
      throw ConnectorException.badInput("The site URL isn't a URL: " + e.getMessage());
    }
    if (base.getHost() == null || base.getScheme() == null) {
      throw ConnectorException.badInput("The site URL needs https:// and a host.");
    }
    String deployment = cfg.string("/deployment");
    boolean cloud = deployment == null || deployment.toLowerCase(Locale.ROOT).equals("cloud");
    String email = cfg.string("/email");
    List<Long> desks = new ArrayList<>();
    for (String id : cfg.strings("/serviceDeskIds")) {
      try {
        desks.add(Long.parseLong(id));
      } catch (NumberFormatException e) {
        throw ConnectorException.badInput("Service desk ids are numbers, not " + id + ".");
      }
    }
    return new JiraConfig(base, cloud, email == null || email.isBlank() ? null : email.strip(), List.copyOf(desks),
        cfg.integer("/timeoutMs", 10_000));
  }

  /** {@code base} + {@code pathAndQuery} ({@code /rest/servicedeskapi/…}). */
  URI uri(String pathAndQuery) {
    return URI.create(base + pathAndQuery);
  }

  String host() {
    return base.getHost();
  }

  /** The REST API version of the platform: 3 in Cloud, 2 in Data Center (which has no v3). */
  String platformApi() {
    return cloud ? "3" : "2";
  }

  /** Whether {@code serviceDeskId} is within the connection's allowlist. */
  boolean allows(String serviceDeskId) {
    if (serviceDeskIds.isEmpty()) {
      return true;
    }
    try {
      return serviceDeskId != null && serviceDeskIds.contains(Long.parseLong(serviceDeskId));
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
