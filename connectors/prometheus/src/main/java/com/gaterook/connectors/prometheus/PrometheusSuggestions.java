package com.gaterook.connectors.prometheus;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Help for the Prometheus form ({@link PrometheusConnector#suggest}). On this computer: {@code http://localhost:9090}
 * for an empty URL when a server answers there, offered and never filled in. Asked as Test connection asks, with the
 * draft's credentials: which server answers ({@code buildinfo}: Prometheus, Grafana Mimir, Cortex, Thanos), the sign-in
 * a server without one asks for ({@code WWW-Authenticate} of a 401) filled into Authentication, a Mimir or Cortex that
 * needs a tenant ({@code no org id}), and for a URL without a path that finds nothing, the path the API answers under
 * ({@code /prometheus} of Mimir, {@code /api/prom} of Grafana Cloud).
 */
final class PrometheusSuggestions {

  static final int PORT = 9090;
  /** Paths before {@code /api/v1} that servers other than Prometheus answer under. */
  static final List<String> PREFIXES = List.of("prometheus", "api/prom", "select/0/prometheus");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PrometheusSuggestions() {}

  /** @param listening whether something accepts a TCP connection on a loopback port ({@code LocalPorts::listening}) */
  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      PrometheusApi api, IntPredicate listening) {
    Suggestions.Builder out = Suggestions.builder();
    String url = cfg.config().path("url").asString("");
    if (url.isBlank() && listening.test(PORT)) {
      out.options("/url", "this computer", List.of(new Option("http://localhost:" + PORT, null,
          "A Prometheus server answers on port " + PORT)));
    }
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, api, out);
    }
    return out.build();
  }

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, PrometheusApi api,
      Suggestions.Builder out) {
    PrometheusSettings s;
    try {
      s = PrometheusSettings.of(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    PrometheusApi.Call c = new PrometheusApi.Call(cfg, s, secrets, ctx);
    Plan info = Plan.buildInfo();
    try {
      PrometheusApi.Answer a = api.fetch(c, info);
      if (a.status() == 401) {
        refused(a, s, out);
        return;
      }
      if (a.status() == 404 && s.base().getRawPath().equals("/")) {
        elsewhere(cfg, s, secrets, ctx, api, out);
        return;
      }
      if (!a.ok()) {
        out.note(PrometheusApi.error(a, info, s).getMessage());
        return;
      }
      String server = server(a);
      PrometheusApi.Answer q = api.fetch(c, Plan.probe("vector(1)", ctx.clock().instant()));
      if (q.status() == 401 && said(q).contains("no org id")) {
        out.note(server + " needs a tenant: set Tenant (X-Scope-OrgID) under Advanced settings.");
        return;
      }
      if (!q.ok()) {
        out.note(PrometheusApi.error(q, Plan.probe("vector(1)", ctx.clock().instant()), s).getMessage());
        return;
      }
      out.note((s.auth() == PrometheusSettings.Auth.NONE ? "Connected to " : "Signed in to ") + server + " at "
          + s.target() + ": PromQL answers.");
    } catch (ConnectorException e) {
      out.note(e.getMessage());
    }
  }

  /** A 401: the tenant a Mimir or Cortex needs, or the sign-in the server asks for. */
  private static void refused(PrometheusApi.Answer a, PrometheusSettings s, Suggestions.Builder out) {
    if (said(a).contains("no org id")) {
      out.note("The server needs a tenant: set Tenant (X-Scope-OrgID) under Advanced settings.");
      return;
    }
    String scheme = a.authenticate().strip().toLowerCase(Locale.ROOT);
    String mode = scheme.startsWith("basic") ? "basic" : scheme.startsWith("bearer") ? "bearer" : null;
    if (s.auth() == PrometheusSettings.Auth.NONE && mode != null) {
      out.value("/auth/mode", "the server", mode, List.of(Option.of(mode)));
      out.note("The server asks for " + (mode.equals("basic") ? "a username and password" : "a bearer token")
          + ": enter " + (mode.equals("basic") ? "them" : "it") + " under Credentials.");
      return;
    }
    out.note(PrometheusApi.error(a, Plan.buildInfo(), s).getMessage());
  }

  /** A URL without a path that finds nothing: the path the API answers under, tried with the same credentials. */
  private static void elsewhere(ConnectionConfig cfg, PrometheusSettings s, SecretResolver secrets, ExecContext ctx,
      PrometheusApi api, Suggestions.Builder out) throws ConnectorException {
    String origin = s.base().toString().substring(0, s.base().toString().length() - 1);
    for (String prefix : PREFIXES) {
      PrometheusSettings under = new PrometheusSettings(URI.create(origin + "/" + prefix + "/"), s.tenant(), s.auth(),
          s.username(), s.caCertificate(), s.maxSeries(), s.maxPoints(), s.maxResponseBytes());
      PrometheusApi.Answer a = api.fetch(new PrometheusApi.Call(cfg, under, secrets, ctx), Plan.buildInfo());
      if (a.ok() && success(a)) {
        String url = origin + "/" + prefix;
        out.options("/url", "the server", List.of(Option.of(url, server(a))));
        out.note(server(a) + " answers under /" + prefix + ": use " + url + ".");
        return;
      }
    }
    out.note("Nothing answers at /api/v1/status/buildinfo of " + s.target() + ". Check the server URL: Mimir's ends "
        + "in /prometheus, Grafana Cloud's in /api/prom.");
  }

  /** {@code Grafana Mimir 2.14.0}: buildinfo's application and version; Prometheus says no application. */
  static String server(PrometheusApi.Answer a) {
    try {
      JsonNode data = JSON.readTree(a.body()).path("data");
      String application = data.path("application").asString("Prometheus");
      String version = data.path("version").asString("");
      return QueryReader.cell(application + (version.isEmpty() ? "" : " " + version), 80);
    } catch (JacksonException e) {
      return "The server";
    }
  }

  private static boolean success(PrometheusApi.Answer a) {
    try {
      return "success".equals(JSON.readTree(a.body()).path("status").asString(""));
    } catch (JacksonException e) {
      return false;
    }
  }

  private static String said(PrometheusApi.Answer a) {
    return new String(a.body(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
  }
}
