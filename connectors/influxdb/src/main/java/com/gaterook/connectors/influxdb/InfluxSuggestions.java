package com.gaterook.connectors.influxdb;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.influxdb.InfluxSettings.Family;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntPredicate;

/**
 * What this computer tells the InfluxDB form ({@link InfluxDbConnector#suggest}), offered and never a host filled in:
 * the servers and organizations of the {@code influx} CLI's {@code ~/.influxdbv2/configs} (never their tokens), servers
 * answering on InfluxDB's ports (8181 for InfluxDB 3, 8086 for 2.x and 1.x), and for a URL of this computer the server
 * its {@code /ping} names, asked without credentials ({@link #ping}). The server's own suggestions, signed in, come
 * from the connector.
 */
final class InfluxSuggestions {

  static final String CLI_CONFIGS = "~/.influxdbv2/configs";
  static final String THIS_COMPUTER = "this computer";
  private static final long MAX_CONFIGS = 64 * 1024;

  private InfluxSuggestions() {}

  /** One profile of the {@code influx} CLI: its name, URL and organization, and whether it is the active one. */
  record CliConfig(String name, String url, String org, boolean active) {}

  /** What {@code /ping} names: {@code X-Influxdb-Version} and {@code X-Influxdb-Build}. */
  record Ping(String version, String build) {}

  /**
   * @param listening whether something accepts a TCP connection on a loopback port ({@code LocalPorts::listening})
   * @param configs   the CLI's profiles file ({@link #CLI_CONFIGS} in the home folder)
   * @param ping      what {@code /ping} of a URL of this computer answers, or {@code null} ({@link #ping})
   */
  static void local(ConnectionConfig cfg, IntPredicate listening, Path configs, Function<URI, Ping> ping,
      Suggestions.Builder out) {
    List<CliConfig> profiles = configs(configs);
    String url = text(cfg, "/url");
    Family found = null;
    if (url == null) {
      List<Option> options = new ArrayList<>();
      for (CliConfig p : profiles) {
        if (p.url() != null && options.stream().noneMatch(o -> o.value().equals(p.url()))) {
          options.add(new Option(p.url(), null, "influx CLI profile " + p.name() + (p.active() ? " (active)" : "")));
        }
      }
      for (int port : new int[] {8181, 8086}) {
        String local = "http://localhost:" + port;
        if (listening.test(port) && options.stream().noneMatch(o -> o.value().equals(local))) {
          options.add(new Option(local, null, "A server answers on port " + port + (port == 8181
              ? ", InfluxDB 3's" : ", InfluxDB 2.x and 1.x's")));
        }
      }
      out.options("/url", THIS_COMPUTER, options);
    } else {
      URI uri = uri(url);
      if (uri != null && loopback(uri.getHost())) {
        Ping answer = ping.apply(uri);
        found = answer == null ? null : InfluxDbConnector.detect(answer.version(), answer.build());
        if (found != null) {
          String address = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
          out.value("/version", "a check of " + address, found.id, List.of(new Option(found.id,
              InfluxDbConnector.serverName(answer.version(), answer.build()), null)));
        }
      }
    }
    // Organizations belong to InfluxDB 2.x, whose CLI the profiles are.
    boolean v2 = found == Family.V2 || (found == null && "2".equals(text(cfg, "/version")));
    if (v2 && text(cfg, "/organization") == null) {
      List<Option> orgs = new ArrayList<>();
      String value = null;
      for (CliConfig p : profiles) {
        if (p.org() == null) {
          continue;
        }
        if (orgs.stream().noneMatch(o -> o.value().equals(p.org()))) {
          orgs.add(new Option(p.org(), null, "influx CLI profile " + p.name()));
        }
        if (url != null && p.url() != null && same(url, p.url())) {
          value = p.org();
        }
      }
      // The profile of the very server typed knows its organization: filled in; others are offered.
      out.value("/organization", CLI_CONFIGS, value, orgs);
    }
  }

  /** The CLI's profiles, without their tokens; empty without the file. */
  static List<CliConfig> configs(Path file) {
    List<CliConfig> out = new ArrayList<>();
    try {
      if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_CONFIGS) {
        return out;
      }
      Map<String, Map<String, String>> sections = new LinkedHashMap<>();
      Map<String, String> current = null;
      for (String raw : Files.readAllLines(file)) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        if (line.startsWith("[") && line.endsWith("]")) {
          current = sections.computeIfAbsent(line.substring(1, line.length() - 1).strip(), k -> new LinkedHashMap<>());
          continue;
        }
        int eq = line.indexOf('=');
        if (current == null || eq <= 0) {
          continue;
        }
        String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
        if (!key.equals("url") && !key.equals("org") && !key.equals("active")) {
          // The token and anything else stay unread.
          continue;
        }
        current.put(key, unquote(line.substring(eq + 1).strip()));
      }
      sections.forEach((name, s) -> {
        String url = s.get("url");
        if (url != null && !url.matches("https?://[^\\s?#@]{1,2000}")) {
          url = null;
        }
        String org = s.get("org");
        if (org != null && (org.isBlank() || !InfluxSettings.NAME.matcher(org).matches())) {
          org = null;
        }
        if (url != null || org != null) {
          out.add(new CliConfig(name, url == null ? null : trimSlash(url), org, "true".equals(s.get("active"))));
        }
      });
      // The active profile first: it is the one the CLI uses.
      out.sort((a, b) -> Boolean.compare(b.active(), a.active()));
    } catch (IOException | RuntimeException e) {
      return List.of();
    }
    return out;
  }

  /**
   * {@code /ping} of a URL of this computer, without credentials and without a proxy: InfluxDB 2.x and 1.x answer it
   * to anyone, InfluxDB 3 may want a token. {@code null} when nothing answers or the URL isn't this computer.
   */
  static Ping ping(URI base, Duration timeout) {
    if (base == null || !loopback(base.getHost())) {
      return null;
    }
    HttpURLConnection c = null;
    try {
      String path = base.getRawPath() == null ? "" : trimSlash(base.getRawPath());
      URI ping = new URI(base.getScheme(), null, base.getHost(), base.getPort(), null, null, null)
          .resolve(path + "/ping");
      c = (HttpURLConnection) ping.toURL().openConnection(Proxy.NO_PROXY);
      int ms = (int) Math.max(1, timeout.toMillis());
      c.setConnectTimeout(ms);
      c.setReadTimeout(ms);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("User-Agent", InfluxHttp.USER_AGENT);
      c.getResponseCode();
      String version = c.getHeaderField("X-Influxdb-Version");
      String build = c.getHeaderField("X-Influxdb-Build");
      return version == null && build == null ? null : new Ping(version, build);
    } catch (IOException | RuntimeException | java.net.URISyntaxException e) {
      return null;
    } finally {
      if (c != null) {
        c.disconnect();
      }
    }
  }

  static boolean loopback(String host) {
    if (host == null) {
      return false;
    }
    String h = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    if (h.equalsIgnoreCase("localhost")) {
      return true;
    }
    try {
      return h.matches("[0-9.]+|[0-9A-Fa-f:]+") && InetAddress.ofLiteral(h).isLoopbackAddress();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static URI uri(String url) {
    try {
      URI u = URI.create(url);
      return u.getScheme() != null && u.getScheme().matches("(?i)https?") && u.getHost() != null ? u : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean same(String a, String b) {
    return trimSlash(a).equalsIgnoreCase(trimSlash(b));
  }

  private static String trimSlash(String s) {
    String t = s;
    while (t.endsWith("/")) {
      t = t.substring(0, t.length() - 1);
    }
    return t;
  }

  private static String unquote(String value) {
    String v = value;
    int hash = v.indexOf(" #");
    if (!v.startsWith("\"") && hash >= 0) {
      v = v.substring(0, hash).strip();
    }
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
      return v.substring(1, v.length() - 1);
    }
    return v;
  }

  private static String text(ConnectionConfig cfg, String pointer) {
    String v = cfg.string(pointer);
    return v == null || v.isBlank() ? null : v.strip();
  }
}
