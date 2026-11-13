package com.gaterook.connectors.logs;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Help for the Loki form. On this computer: a server on Loki's usual port 3100 (offered, never filled in), Grafana
 * Cloud's sign-in for its address, and for a Loki on this computer one look without credentials: does it need any, a
 * tenant, or another scheme. Signed in: its version and the label names of the last hour. Loki has no API that lists
 * tenants, so Tenant is never filled in.
 */
final class LokiSuggestions {

  /** Loki's default HTTP port. */
  static final int PORT = 3100;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Option NONE = new Option("none", "No authentication", null);
  private static final Option BASIC = new Option("basic", "Username and password", null);

  private LokiSuggestions() {}

  static void local(ConnectionConfig cfg, ExecContext ctx, Suggestions.Builder out) {
    JsonNode c = cfg.config();
    String url = LogsSuggestions.text(c, "/url");
    if (url == null) {
      Loopback.offer(out, "/url", "A server", PORT, ctx);
      return;
    }
    URI base;
    try {
      base = Settings.baseUrl(url, true);
    } catch (IllegalArgumentException e) {
      return;
    }
    String host = base.getHost().toLowerCase(Locale.ROOT);
    String tenant = LogsSuggestions.text(c, "/tenant");
    if (host.endsWith(".grafana.net")) {
      out.value("/auth/mode", "the URL", "basic", List.of(BASIC));
      out.note("Grafana Cloud signs in with the stack's numeric user ID as Username and an access policy token with "
          + "the logs:read scope as Password.");
      if (tenant != null) {
        out.note("Grafana Cloud takes the tenant from the user ID: leave Tenant empty.");
      }
      return;
    }
    if (Loopback.host(host)) {
      onThisComputer(base, tenant, LogsSuggestions.text(c, "/caCertificate"), ctx, out);
    }
  }

  /** One look at a Loki on this computer, the tenant header the only one sent: what it needs to be read. */
  private static void onThisComputer(URI base, String tenant, String ca, ExecContext ctx, Suggestions.Builder out) {
    Loopback.Answer a = Loopback.ask(base, "/loki/api/v1/labels?since=1h",
        tenant == null ? Map.of() : Map.of("X-Scope-OrgID", tenant), ca, "loki", "/url", ctx, out);
    if (a == null) {
      return;
    }
    String address = Settings.hostPort(base);
    switch (a.status()) {
      case 200 -> {
        List<String> labels = labels(a.body());
        if (labels == null) {
          out.note(address + " answers, but not as Loki. Check the port.");
          return;
        }
        out.value("/auth/mode", "Loki on " + address, "none", List.of(NONE));
        out.note("Loki on " + address + " answers without credentials" + (labels.isEmpty()
            ? ", but has no logs from the last hour." : ": " + LogsSuggestions.count(labels.size(), "label", "labels")
            + " in the last hour (" + LogsSuggestions.some(labels, 6) + ")."));
      }
      case 401, 403 -> out.note(a.body().contains("no org id")
          ? "Loki on " + address + " is multi-tenant: set Tenant (X-Scope-OrgID) to the one your log shippers send."
          : "Loki on " + address + " asks for credentials.");
      case 404 -> out.note(address + " answers, but has no Loki API at this URL. Check the port and the path.");
      default -> { }
    }
  }

  /**
   * Signed in as Test connection does, with the tenant and credentials of the draft: the version and the label names
   * of the last hour, in a note (Loki's own words when it refuses).
   */
  static void service(Settings.Loki s, Backend.Call call, Suggestions.Builder out) throws ConnectorException {
    LokiBackend loki = new LokiBackend(s, call);
    JsonNode data = loki.describe(null).path("labels");
    String version = null;
    try {
      version = loki.version();
    } catch (ConnectorException e) {
      // A gateway may hide buildinfo: the labels said enough.
    }
    List<String> labels = new ArrayList<>();
    data.forEach(l -> {
      if (l.isString()) {
        labels.add(l.asString());
      }
    });
    String who = "Signed in to Loki" + (version == null ? "" : " " + version) + " at " + s.target()
        + (s.tenant() == null ? "" : " as tenant " + s.tenant());
    out.note(labels.isEmpty() ? who + ": no logs in the last hour. Check the tenant and that something ships logs "
        + "to this Loki." : who + ": " + LogsSuggestions.count(labels.size(), "label", "labels")
        + " in the last hour (" + LogsSuggestions.some(labels, 6) + ").");
  }

  /** The label names of Loki's {@code labels} answer, or {@code null} for an answer Loki wouldn't give. */
  private static List<String> labels(String body) {
    try {
      JsonNode root = body.isBlank() ? null : JSON.readTree(body);
      if (root == null || !"success".equals(root.path("status").asString("")) || !root.path("data").isArray()) {
        return null;
      }
      List<String> out = new ArrayList<>();
      root.path("data").forEach(l -> {
        if (l.isString()) {
          out.add(l.asString());
        }
      });
      return out;
    } catch (JacksonException e) {
      return null;
    }
  }
}
