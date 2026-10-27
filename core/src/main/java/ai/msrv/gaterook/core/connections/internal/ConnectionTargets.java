package ai.msrv.gaterook.core.connections.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * Where a connection sends its credentials, and how its list row shows it: hosts, URLs and servers of a config. A
 * change of any of them with stored secrets needs the person's confirmation ("Send the stored password to
 * db2.dev.internal?", ARCHITECTURE.md §6), so that an edit can't redirect a password to a host the person never meant.
 */
public final class ConnectionTargets {

  /**
   * Property names that name where a connection connects; an MCP server's {@code command} is the program its secrets
   * go to as environment variables.
   */
  private static final Set<String> TARGET_KEYS = Set.of("host", "hostname", "server", "apiServer", "url", "uri",
      "baseUrl", "baseUrls", "endpoint", "address", "command");

  private ConnectionTargets() {}

  /** Every target of a config: pointer → value text. */
  public static Map<String, String> of(JsonNode config) {
    Map<String, String> out = new TreeMap<>();
    collect(config, "", null, out);
    return out;
  }

  private static void collect(JsonNode node, String pointer, String key, Map<String, String> out) {
    if (node == null || SecretFields.isPlaceholder(node)) {
      return;
    }
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        collect(e.getValue(), pointer + "/" + SecretFields.escape(e.getKey()), e.getKey(), out);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        collect(node.get(i), pointer + "/" + i, key, out);
      }
    } else if (key != null && TARGET_KEYS.contains(key) && node.isValueNode() && !node.isNull()) {
      out.put(pointer, node.asString());
    }
  }

  /** The first target that differs between two configs, if any. */
  public static java.util.Optional<String> changedTarget(JsonNode before, JsonNode after) {
    Map<String, String> a = of(before);
    Map<String, String> b = of(after);
    for (Map.Entry<String, String> e : b.entrySet()) {
      if (!e.getValue().equals(a.get(e.getKey()))) {
        return java.util.Optional.of(e.getValue());
      }
    }
    return java.util.Optional.empty();
  }

  /** {@link #display(JsonNode, JsonNode, JsonNode)} without the form's defaults and the last test's facts. */
  public static String display(JsonNode config) {
    return display(config, null, null);
  }

  /**
   * One line for the list, in the kit's words (design/pages/connections.html): where the connection connects, without
   * the scheme of a URL ("db.dev.internal:5432/app", "loki.dev.internal:3100", "10.20.0.14:22" with the form's
   * default port), the cluster's namespace ("k8s.dev.internal:6443 · ns app-dev") and the AWS account
   * ("eu-central-1 · acme-stage": its alias, its id, or the account the last test signed in to). An HTTP API (and an
   * MCP server reached over HTTP) keeps its whole base URL: the scheme is part of what the agent calls. Never user
   * info, a query or a fragment. Empty when the config names no target.
   *
   * @param schema   the type's config schema, for default ports; or {@code null}
   * @param metadata what the last successful test found ({@code account} of AWS); or {@code null}
   */
  public static String display(JsonNode config, JsonNode schema, JsonNode metadata) {
    JsonNode c = config.path("connection");
    if (c.path("host").isString()) {
      StringBuilder out = new StringBuilder(c.path("host").asString());
      JsonNode port = c.path("port").isNumber() || schema == null ? c.path("port")
          : schema.at("/properties/connection/properties/port/default");
      if (port.isNumber()) {
        out.append(':').append(port.asInt());
      }
      if (c.path("database").isString()) {
        out.append('/').append(c.path("database").asString());
      }
      return out.toString();
    }
    if (c.path("uri").isString()) {
      return address(c.path("uri").asString());
    }
    if (config.path("command").isArray() && !config.path("command").isEmpty()) {
      List<String> argv = new ArrayList<>();
      config.path("command").forEach(a -> argv.add(a.asString("")));
      String line = String.join(" ", argv);
      return line.length() > 120 ? line.substring(0, 119) + "…" : line;
    }
    for (String ptr : new String[] {"/baseUrls/0", "/baseUrl"}) {
      if (config.at(ptr).isString()) {
        return withoutUserInfo(config.at(ptr).asString());
      }
    }
    if (config.path("server").isString()) {
      String namespace = config.path("namespace").asString("");
      return address(config.path("server").asString()) + (namespace.isBlank() ? "" : " · ns " + namespace);
    }
    if (config.path("url").isString()) {
      // An MCP server's URL is what it is called at, scheme included; a log backend's is only where it is.
      String url = config.path("url").asString();
      return config.has("transport") ? withoutQuery(withoutUserInfo(url)) : address(url);
    }
    if (config.at("/regions/0").isString()) {
      String account = config.path("accountAlias").asString("");
      if (account.isBlank()) {
        account = config.path("accountId").asString("");
      }
      if (account.isBlank() && metadata != null) {
        // What the test's STS answer said: a server's word, one short line.
        String tested = oneLine(metadata.path("account").asString(null), 64);
        account = tested == null ? "" : tested;
      }
      return config.at("/regions/0").asString() + (account.isBlank() ? "" : " · " + account);
    }
    for (String ptr : new String[] {"/resourceNames/0", "/metricsProjects/0", "/region"}) {
      if (config.at(ptr).isString()) {
        return config.at(ptr).asString();
      }
    }
    return "";
  }

  /** {@code mongodb://u@mongo.dev.internal:27017/orders?tls=true} → {@code mongo.dev.internal:27017/orders}. */
  static String address(String uri) {
    String out = withoutUserInfo(uri);
    int scheme = out.indexOf("://");
    if (scheme >= 0) {
      out = out.substring(scheme + 3);
    }
    out = withoutQuery(out);
    return out.endsWith("/") ? out.substring(0, out.length() - 1) : out;
  }

  /** A URL without its query and fragment: they may carry a key ({@code ?apiKey=…}) and never say where it leads. */
  static String withoutQuery(String url) {
    int end = url.length();
    for (char stop : new char[] {'?', '#'}) {
      int i = url.indexOf(stop);
      if (i >= 0 && i < end) {
        end = i;
      }
    }
    return url.substring(0, end);
  }

  /**
   * A URI without user info: everything between {@code ://} and the last {@code @} of the authority (the text before
   * the first {@code /}, {@code ?} or {@code #}), so a password with an unescaped {@code @} leaves none of it behind.
   */
  private static String withoutUserInfo(String uri) {
    int scheme = uri.indexOf("://");
    if (scheme < 0) {
      return uri;
    }
    int start = scheme + 3;
    int end = uri.length();
    for (char stop : new char[] {'/', '?', '#'}) {
      int i = uri.indexOf(stop, start);
      if (i >= 0 && i < end) {
        end = i;
      }
    }
    int at = uri.lastIndexOf('@', end - 1);
    return at >= start ? uri.substring(0, start) + uri.substring(at + 1) : uri;
  }

  private static final java.util.regex.Pattern BREAKS = java.util.regex.Pattern.compile("[\\s\\u2028\\u2029]+");

  /**
   * Text a server sent — its version, the account a test signed in to — as one short line of a list or a tool's
   * description: control, bidi and zero-width characters as visible escapes ({@code OutputSanitizer}), so are the
   * other invisible code points (format characters such as the tags that can hide text from a reader but not from a
   * model, private use, unpaired surrogates, unassigned), line breaks and runs of spaces as one space, at most
   * {@code max} characters (the last one "…"). {@code null} when nothing is left.
   */
  public static String oneLine(String text, int max) {
    if (text == null) {
      return null;
    }
    // A server may send megabytes: only the start can be shown, so only the start is looked at.
    int cut = Math.min(text.length(), max * 4);
    if (cut < text.length() && cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
      cut--;
    }
    String line = BREAKS.matcher(visible(ai.msrv.gaterook.connectors.support.OutputSanitizer.sanitize(
        text.substring(0, cut)))).replaceAll(" ").strip();
    if (line.isEmpty()) {
      return null;
    }
    int points = line.codePointCount(0, line.length());
    if (points <= max && cut == text.length()) {
      return line;
    }
    return (points < max ? line : line.substring(0, line.offsetByCodePoints(0, max - 1))) + "…";
  }

  /**
   * The invisible code points {@code OutputSanitizer} leaves as they are, as escapes: a backslash, then
   * {@code u{E0041}}.
   */
  private static String visible(String text) {
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints().forEach(cp -> {
      int type = Character.getType(cp);
      if (type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.SURROGATE
          || type == Character.UNASSIGNED) {
        out.append("\\u{").append(Integer.toHexString(cp).toUpperCase(java.util.Locale.ROOT)).append('}');
      } else {
        out.appendCodePoint(cp);
      }
    });
    return out.toString();
  }
}
