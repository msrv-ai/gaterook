package com.gaterook.connectors.cloudflare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes values Cloudflare returns as plain configuration but that are secrets more often than not, before an answer
 * leaves the connector; the core's masker (taint, key names, entropy, personal data) runs after it. Counted, so the
 * answer can say how many values were masked.
 *
 * <ul>
 *   <li>Worker bindings: the text of {@code secret_text}, the key of {@code secret_key}, and the text of
 *       {@code plain_text} or the value of {@code json} whose name looks like a credential ({@code API_KEY},
 *       {@code DATABASE_URL}, {@code STRIPE_SECRET});</li>
 *   <li>Pages environment variables ({@code env_vars}): every {@code secret_text} value, and {@code plain_text} values
 *       named like a credential;</li>
 *   <li>a Logpush job's {@code destination_conf}: query values that carry credentials ({@code secret-access-key},
 *       {@code header_Authorization}, a SAS {@code sig}) and long opaque path segments (a Sumo Logic collector's);</li>
 *   <li>credentials in the query of request URLs of logs and security events ({@code ?token=…});</li>
 *   <li>everywhere, a text value whose key names a secret: {@code token}, {@code tunnel_secret},
 *       {@code web_analytics_token}, {@code password}, {@code api_key}, {@code private_key}.</li>
 * </ul>
 */
final class Redactor {

  static final String MASK = "‹masked›";

  private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(password|passwd|secret|token|private_?key"
      + "|client_?key|credential|connection_?string|api_?key|auth_?key|access_?key|signing_?key|authorization"
      + "|cookie).*");
  /** Keys that end in a sensitive word but hold names, counts, settings or cursors, not values. */
  private static final Pattern NOT_A_VALUE = Pattern.compile("(?i).*(count|ttl|seconds|enabled|type|policy|version"
      + "|expir\\w*|time|duration|name|names|id|ids|tag|uri|url|ref|source|cursor|status|_at|_on|required|present"
      + "|length|size|format|mode)$");
  /** Binding and variable names that usually hold a credential. */
  private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(key|token|secret|pass|pwd|auth|credential"
      + "|private|dsn|salt|cookie|session|signature|signing|webhook|conn|database_?url|db_?url|bearer|jwt"
      + "|cert).*");
  /** Query parameters that carry credentials. */
  private static final Pattern SECRET_PARAM = Pattern.compile("(?i)(header_.*|.*(token|key|auth|pass|pwd|secret"
      + "|sig|signature|session|credential|jwt|code|cookie).*)");
  /** A path segment that looks like an embedded key: long, without separators a name would have. */
  private static final Pattern OPAQUE_SEGMENT = Pattern.compile("[A-Za-z0-9_=+-]{24,}");
  private static final Pattern QUERY_PAIR = Pattern.compile("([^=&?#]+)=([^&#]*)");

  private Redactor() {}

  /** Masks in place; returns the number of values masked. */
  static int redact(JsonNode node) {
    return walk(node, null);
  }

  private static int walk(JsonNode node, String key) {
    int masked = 0;
    if (node instanceof ObjectNode o) {
      List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(o.properties());
      for (Map.Entry<String, JsonNode> f : fields) {
        String k = f.getKey();
        JsonNode v = f.getValue();
        if (k.equals("bindings") && v.isArray()) {
          masked += bindings((ArrayNode) v);
        } else if (k.equals("env_vars") && v.isObject()) {
          masked += envVars((ObjectNode) v);
        } else if (k.equals("destination_conf") && v.isString()) {
          masked += replace(o, k, destination(v.asString()));
        } else if ((k.equals("url") || k.equals("clientRequestQuery") || k.equals("clientRequestURI")) && v.isString()) {
          masked += replace(o, k, query(v.asString()));
        } else if (v.isString() && sensitiveKey(k)) {
          masked += mask(o, k);
        } else {
          masked += walk(v, k);
        }
      }
    } else if (node instanceof ArrayNode a) {
      for (JsonNode item : a) {
        masked += walk(item, key);
      }
    }
    return masked;
  }

  static boolean sensitiveKey(String key) {
    return SENSITIVE_KEY.matcher(key).matches() && !NOT_A_VALUE.matcher(key).matches();
  }

  /** Worker bindings ({@code settings}, a version's {@code resources.bindings}). */
  private static int bindings(ArrayNode list) {
    int masked = 0;
    for (JsonNode b : list) {
      if (!(b instanceof ObjectNode o)) {
        continue;
      }
      String type = o.path("type").asString("");
      boolean secretName = SECRET_NAME.matcher(o.path("name").asString("")).matches();
      switch (type) {
        case "secret_text" -> masked += mask(o, "text");
        case "secret_key" -> {
          masked += mask(o, "key_base64");
          masked += maskAny(o, "key_jwk");
        }
        case "plain_text" -> masked += secretName ? mask(o, "text") : 0;
        case "json" -> masked += secretName ? maskAny(o, "json") : walk(o.path("json"), "json");
        default -> masked += walk(o, "binding");
      }
    }
    return masked;
  }

  /** Pages {@code env_vars}: {@code {"NAME": {"type": "plain_text" | "secret_text", "value": "…"}}}. */
  private static int envVars(ObjectNode vars) {
    int masked = 0;
    for (Map.Entry<String, JsonNode> e : new ArrayList<>(vars.properties())) {
      if (!(e.getValue() instanceof ObjectNode v)) {
        if (e.getValue().isString()) {
          masked += mask(vars, e.getKey());
        }
        continue;
      }
      boolean secret = !"plain_text".equals(v.path("type").asString("")) || SECRET_NAME.matcher(e.getKey()).matches();
      masked += secret ? mask(v, "value") : 0;
    }
    return masked;
  }

  /** A text with credentials masked, and how many. */
  record Masked(String text, int count) {}

  /**
   * A Logpush destination: {@code s3://bucket/path?region=us-east-1&access-key-id=‹masked›&secret-access-key=‹masked›},
   * {@code sumo://endpoint.collection.sumologic.com/receiver/v1/http/‹masked›}.
   */
  static Masked destination(String conf) {
    int q = conf.indexOf('?');
    String head = q < 0 ? conf : conf.substring(0, q);
    int count = 0;
    int scheme = head.indexOf("://");
    StringBuilder out = new StringBuilder();
    if (scheme >= 0) {
      int pathStart = head.indexOf('/', scheme + 3);
      String authority = pathStart < 0 ? head.substring(scheme + 3) : head.substring(scheme + 3, pathStart);
      int at = authority.lastIndexOf('@');
      if (at >= 0) {
        // user:password@host
        authority = MASK + "@" + authority.substring(at + 1);
        count++;
      }
      out.append(head, 0, scheme + 3).append(authority);
      if (pathStart >= 0) {
        for (String segment : head.substring(pathStart + 1).split("/", -1)) {
          out.append('/');
          if (OPAQUE_SEGMENT.matcher(segment).matches()) {
            out.append(MASK);
            count++;
          } else {
            out.append(segment);
          }
        }
      }
    } else {
      out.append(head);
    }
    if (q >= 0) {
      Masked rest = params(conf.substring(q));
      out.append(rest.text());
      count += rest.count();
    }
    return new Masked(out.toString(), count);
  }

  /** A URL or a query string with credential-like parameters masked: {@code /api?token=‹masked›&page=2}. */
  static Masked query(String url) {
    int q = url.indexOf('?');
    if (q < 0) {
      return new Masked(url, 0);
    }
    Masked rest = params(url.substring(q));
    return new Masked(url.substring(0, q) + rest.text(), rest.count());
  }

  private static Masked params(String query) {
    StringBuilder out = new StringBuilder();
    Matcher m = QUERY_PAIR.matcher(query);
    int last = 0;
    int count = 0;
    while (m.find()) {
      out.append(query, last, m.start());
      String name = m.group(1);
      String value = m.group(2);
      if (!value.isEmpty() && !value.equals(MASK) && SECRET_PARAM.matcher(java.net.URLDecoder.decode(name,
          java.nio.charset.StandardCharsets.UTF_8)).matches()) {
        out.append(name).append('=').append(MASK);
        count++;
      } else {
        out.append(m.group());
      }
      last = m.end();
    }
    out.append(query.substring(last));
    return new Masked(out.toString(), count);
  }

  private static int replace(ObjectNode o, String key, Masked m) {
    if (m.count() > 0) {
      o.put(key, m.text());
    }
    return m.count();
  }

  private static int mask(ObjectNode o, String name) {
    JsonNode v = o.get(name);
    if (v == null || v.isNull() || v.isContainer() || (v.isString() && (v.asString().isEmpty()
        || v.asString().equals(MASK)))) {
      return 0;
    }
    o.put(name, MASK);
    return 1;
  }

  /** Masks a value whatever its kind, an object or list too. */
  private static int maskAny(ObjectNode o, String name) {
    JsonNode v = o.get(name);
    if (v == null || v.isNull() || (v.isString() && (v.asString().isEmpty() || v.asString().equals(MASK)))) {
      return 0;
    }
    o.put(name, MASK);
    return 1;
  }
}
