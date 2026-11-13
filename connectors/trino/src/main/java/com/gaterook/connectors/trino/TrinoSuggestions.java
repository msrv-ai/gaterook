package com.gaterook.connectors.trino;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.support.LocalPorts;
import com.gaterook.connectors.support.OutputSanitizer;
import com.gaterook.connectors.support.egress.AddressPolicy;
import com.gaterook.connectors.support.egress.UrlRules;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Trino / Presto form ({@link com.gaterook.connector.api.Connector#suggest}).
 *
 * <p>On this computer: a coordinator answering on Trino's usual port, offered as its URL and never filled in, and for
 * a URL on this computer, the engine its {@code /v1/info} names (no credentials, nothing else sent).
 *
 * <p>Signed in as Test connection signs in: the engine filled in from {@code /v1/info}, whose version tells Trino's
 * {@code X-Trino-*} protocol from Presto's {@code X-Presto-*} one; the catalogs for the default catalog and the list,
 * the only data catalog filled in as the default; the schemas of the default catalog. {@code SHOW} statements only.
 */
final class TrinoSuggestions {

  /** Trino's HTTP port by default, as its container image and tarball listen. */
  static final int DEFAULT_PORT = 8080;
  /** Trino's first release: PrestoSQL 350 and earlier spoke the protocol with X-Presto headers. */
  private static final int FIRST_TRINO = 351;
  private static final Pattern MAJOR = Pattern.compile("(\\d{1,6})");
  private static final Duration LOCAL_PROBE = Duration.ofSeconds(1);
  private static final int MAX_INFO = 64 * 1024;
  private static final int MAX_NAMES = Suggestions.MAX_OPTIONS + 1;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private TrinoSuggestions() {}

  static Suggestions suggest(TrinoHttp http, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Scope scope) {
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.LOCAL) {
      local(cfg, ctx, out);
    } else {
      boolean kept = http.has(cfg.id());
      try {
        service(http, cfg, secrets, ctx, out);
      } finally {
        if (!kept) {
          // The client a draft's suggestions made: drafts are many, each with an id of its own.
          http.evict(cfg.id());
        }
      }
    }
    return out.build();
  }

  /**
   * The engine of a coordinator by the version {@code /v1/info} names: Presto (prestodb) numbers its releases 0.x,
   * PrestoSQL up to 350 kept Presto's headers, and Trino's releases are numbered from 351 on, with or without a
   * vendor's suffix ({@code 476-e.1}, {@code 414-amzn-0}). {@code null} for a version that says neither.
   */
  static TrinoSettings.Engine engine(String version) {
    if (version == null || version.isBlank()) {
      return null;
    }
    String v = version.strip();
    if (v.startsWith("0.")) {
      return TrinoSettings.Engine.PRESTO;
    }
    Matcher m = MAJOR.matcher(v);
    if (!m.lookingAt()) {
      return null;
    }
    return Integer.parseInt(m.group(1)) >= FIRST_TRINO ? TrinoSettings.Engine.TRINO : TrinoSettings.Engine.PRESTO;
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  static void local(ConnectionConfig cfg, ExecContext ctx, Suggestions.Builder out) {
    if (LocalPorts.listening(DEFAULT_PORT)) {
      out.options("/url", "this computer", List.of(new Option("http://localhost:" + DEFAULT_PORT, null,
          "Trino's usual port " + DEFAULT_PORT + " answers here")));
    }
    URI base = loopbackHttp(cfg.string("/url"));
    if (base == null) {
      return;
    }
    JsonNode info = localInfo(base, ctx);
    String version = info == null ? null : info.path("nodeVersion").path("version").asString(null);
    TrinoSettings.Engine engine = engine(version);
    if (engine != null) {
      fillEngine(cfg, engine, version, base.getAuthority(), out);
    }
  }

  /**
   * {@code http://<loopback>:port} of a URL on this computer over plain HTTP, or {@code null}. A name other than
   * {@code localhost} is never resolved for this: local suggestions send nothing to a DNS server either.
   */
  private static URI loopbackHttp(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    try {
      HttpUrl parsed = UrlRules.parse(url.strip());
      if (parsed.isHttps() || !parsed.encodedPath().equals("/")) {
        return null;
      }
      String host = parsed.host();
      boolean loopback = host.equalsIgnoreCase("localhost")
          || AddressPolicy.canonicalIp(host).map(InetAddress::isLoopbackAddress).orElse(false);
      return loopback ? URI.create("http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + parsed.port())
          : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * {@code GET /v1/info} of a coordinator on this computer, within a second and through Trino's address rules (the
   * core's own ports are refused): public in Trino's protocol, so no credentials go with it.
   */
  private static JsonNode localInfo(URI base, ExecContext ctx) {
    URI uri = base.resolve("/v1/info");
    try {
      TrinoAddresses.of(ctx).check(uri);
    } catch (ConnectorException e) {
      return null;
    }
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).connectTimeout(LOCAL_PROBE)
        .build()) {
      HttpResponse<InputStream> r = client.send(HttpRequest.newBuilder(uri).timeout(LOCAL_PROBE)
          .header("Accept", "application/json").header("User-Agent", "GateRook").GET().build(),
          HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = r.body()) {
        byte[] bytes = body.readNBytes(MAX_INFO);
        return r.statusCode() == 200 ? JSON.readTree(bytes) : null;
      }
    } catch (IOException | RuntimeException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /** The engine as the coordinator names it; a note when the form says the other one. */
  private static void fillEngine(ConnectionConfig cfg, TrinoSettings.Engine engine, String version, String where,
      Suggestions.Builder out) {
    String id = engine == TrinoSettings.Engine.PRESTO ? "presto" : "trino";
    out.value("/engine", "the coordinator", id, List.of(new Option(id, engine.title, engine.title + " "
        + OutputSanitizer.sanitize(version))));
    // A connection without the setting speaks Trino's protocol.
    String chosen = "presto".equals(cfg.string("/engine")) ? "presto" : "trino";
    if (!chosen.equals(id)) {
      out.note("The coordinator at " + where + " is " + engine.title + " " + OutputSanitizer.sanitize(version)
          + ": choose " + engine.title + " under Engine.");
    }
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  static void service(TrinoHttp http, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Builder out) {
    TrinoSettings s;
    try {
      s = TrinoSettings.of(cfg);
    } catch (ConnectorException e) {
      // A default schema without its catalog (x-suggest: the form asks while Default catalog is empty), or a catalog
      // typed halfway or off the list: the catalogs are listed without them, and offered for the field.
      try {
        s = TrinoSettings.of(without(cfg, "catalog", "schema"));
        out.note(e.getMessage());
      } catch (ConnectorException again) {
        out.note(again.getMessage());
        return;
      }
    }
    TrinoHttp.Call c = new TrinoHttp.Call(cfg, s, secrets, ctx);
    String version;
    try {
      JsonNode info = http.info(c);
      if (info.path("starting").asBoolean(false)) {
        out.note(s.engine().title + " at " + s.address() + " is still starting: its catalogs follow once it runs.");
        return;
      }
      version = info.path("nodeVersion").path("version").asString("");
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    TrinoSettings.Engine engine = engine(version);
    if (engine != null) {
      fillEngine(cfg, engine, version, s.address(), out);
      if (engine != s.engine()) {
        // The catalogs come in the coordinator's own protocol: the other one's headers name no user it knows.
        s = new TrinoSettings(s.base(), engine, s.auth(), s.user(), s.catalog(), s.schema(), s.catalogs(),
            s.caCertificate(), s.maxScanBytes(), s.loopback());
        c = new TrinoHttp.Call(cfg, s, secrets, ctx);
      }
    }
    List<String> catalogs;
    boolean[] more = new boolean[1];
    try {
      catalogs = names(http, c, "SHOW CATALOGS", more);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    List<Option> options = catalogs.stream().map(n -> new Option(n, null, n.equals("system")
        ? "Trino's own metadata" : null)).toList();
    TrinoSettings settings = s;
    List<String> data = catalogs.stream().filter(n -> !n.equals("system") && settings.allows(n)
        && TrinoSettings.CATALOG.matcher(n).matches()).toList();
    String fill = s.catalog() == null && data.size() == 1 ? data.getFirst() : null;
    if (!options.isEmpty()) {
      out.field("/catalog", new Suggestions.Field(options, fill, "the coordinator", !more[0]));
      out.field("/catalogs", new Suggestions.Field(options, null, "the coordinator", !more[0]));
    }
    String title = s.engine().title + " " + OutputSanitizer.sanitize(version);
    out.note("Signed in to " + title + " at " + s.address() + (s.user() == null ? "" : " as " + s.user()) + ": "
        + catalogs.size() + (more[0] ? "+" : "") + (catalogs.size() == 1 ? " catalog." : " catalogs."));
    if (s.catalog() != null && catalogs.stream().noneMatch(n -> n.equalsIgnoreCase(settings.catalog()))) {
      out.note("The user sees no catalog " + s.catalog() + ": choose one it sees.");
      return;
    }
    String catalog = s.catalog() != null ? s.catalog() : fill;
    if (catalog == null || !TrinoSettings.CATALOG.matcher(catalog).matches()) {
      return;
    }
    try {
      List<String> schemas = names(http, c, "SHOW SCHEMAS FROM \"" + catalog.toLowerCase(Locale.ROOT) + "\"",
          new boolean[1]);
      out.options("/schema", "catalog " + catalog, schemas.stream().filter(n -> !n.equals("information_schema"))
          .map(Option::of).toList());
    } catch (ConnectorException e) {
      out.note("The schemas of " + catalog + " couldn't be listed: " + e.getMessage());
    }
  }

  /** {@code cfg} without the top-level fields {@code keys}. */
  private static ConnectionConfig without(ConnectionConfig cfg, String... keys) {
    ObjectNode config = cfg.config() instanceof ObjectNode o ? o.deepCopy() : JSON.createObjectNode();
    for (String key : keys) {
      config.remove(key);
    }
    return new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(), config,
        cfg.secretFields(), cfg.metadata());
  }

  /** The first column of a SHOW statement's rows, at most {@link #MAX_NAMES}; {@code more[0]} when it had more. */
  private static List<String> names(TrinoHttp http, TrinoHttp.Call c, String sql, boolean[] more)
      throws ConnectorException {
    List<String> out = new ArrayList<>();
    http.run(c, sql, TrinoHttp.Purpose.READ, (columns, row) -> {
      if (out.size() >= MAX_NAMES - 1) {
        more[0] = true;
        return false;
      }
      Optional.ofNullable(row.path(0).asString(null)).filter(n -> !n.isBlank()).ifPresent(out::add);
      return true;
    });
    return out;
  }
}
