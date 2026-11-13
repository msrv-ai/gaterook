package com.gaterook.connectors.azure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes values that are secrets more often than not before an Azure answer leaves the connector; the core's masker
 * runs after it. AutoRest's {@code x-ms-secret} contract keeps secrets off GET answers — keys, connection strings and
 * app settings come from {@code list…} actions, which the connector denies — so this is the second line, for
 * services that break the contract and for configuration people put secrets in.
 *
 * <ul>
 *   <li>maps of settings: App Service {@code appSettings} and {@code connectionStrings} (an object, or
 *       {@code [{name, value}]}), container {@code env} and {@code environmentVariables} ({@code value} of each),
 *       Container Apps {@code secrets[*].value};</li>
 *   <li>a text value whose key names a secret ({@code password}, {@code secret}, {@code primaryKey},
 *       {@code accountKey}, {@code connectionString}, {@code sasToken}, {@code customData}, {@code userData},
 *       {@code privateKey}, {@code credential}, {@code key1}/{@code key2}, …), not a count, a name or a reference
 *       ({@code secretName}, {@code keyVaultUrl}, {@code passwordProfile} settings stay);</li>
 *   <li>anywhere, a text that is a storage or Service Bus connection string ({@code AccountKey=},
 *       {@code SharedAccessKey=}) or a URL with a SAS signature ({@code sig=}).</li>
 * </ul>
 * {@code instrumentationKey} stays: it is no credential, and agents need it to tell components apart.
 */
final class Redactor {

  static final String MASK = "‹masked›";

  private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(password|passwd|secret|token|private_?key"
      + "|client_?key|credential|connection_?string|sas|account_?key|access_?key|shared_?key|primary_?key"
      + "|secondary_?key|master_?key|admin_?key|api_?key|auth(orization)?_?key|custom_?data|user_?data|pfx).*");
  /** Keys that end in a sensitive word but hold counts, settings or references, not values. */
  private static final Pattern NOT_A_VALUE = Pattern.compile("(?i).*(count|ttl|seconds|enabled|type|policy|version"
      + "|expir\\w*|time|duration|rotation\\w*|name|names|id|ids|ref|source|lifetime|state|status|mode|kind"
      + "|profile|settings|configuration|length|format|scope|audience|issuer|thumbprint|keyvaulturl|uri|url"
      + "|endpoint|identity)$");
  private static final Set<String> KEY_NAMES = Set.of("key", "key1", "key2", "value");
  private static final Set<String> SETTING_MAPS = Set.of("appsettings", "connectionstrings", "environmentvariables",
      "env", "envvars", "settings");
  private static final Pattern CONNECTION_STRING = Pattern.compile(
      "(?i)(?:^|;)\\s*(?:AccountKey|SharedAccessKey|SharedAccessSignature|Password|Pwd)\\s*=");
  private static final Pattern SAS = Pattern.compile("(?i)[?&]sig=[A-Za-z0-9%/+=_-]{10,}");

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
        String lower = k.toLowerCase(java.util.Locale.ROOT);
        if (SETTING_MAPS.contains(lower) && v.isObject() && !lower.equals("settings")) {
          masked += maskAll((ObjectNode) v);
        } else if (SETTING_MAPS.contains(lower) && v.isArray()) {
          masked += maskItemValues((ArrayNode) v);
          masked += walk(v, k);
        } else if (lower.equals("secrets") && v.isArray()) {
          masked += maskItemValues((ArrayNode) v);
          masked += walk(v, k);
        } else if (v.isString() && (sensitiveKey(k) || looksSecret(v.asString()))) {
          masked += maskField(o, k);
        } else if (v.isString() && KEY_NAMES.contains(lower) && key != null && (sensitiveKey(key)
            || key.equalsIgnoreCase("keys"))) {
          // {"primaryKey": {"value": "…"}}, {"keys": [{"keyName": "key1", "value": "…"}]}
          masked += maskField(o, k);
        } else {
          masked += walk(v, k);
        }
      }
    } else if (node instanceof ArrayNode a) {
      for (int i = 0; i < a.size(); i++) {
        JsonNode item = a.get(i);
        if (item.isString() && looksSecret(item.asString())) {
          a.set(i, a.stringNode(MASK));
          masked++;
        } else {
          masked += walk(item, key);
        }
      }
    }
    return masked;
  }

  static boolean sensitiveKey(String key) {
    return SENSITIVE_KEY.matcher(key).matches() && !NOT_A_VALUE.matcher(key).matches();
  }

  /** A connection string with a key or password, or a URL that carries a SAS signature. */
  static boolean looksSecret(String text) {
    return !text.equals(MASK) && (CONNECTION_STRING.matcher(text).find() || SAS.matcher(text).find());
  }

  /** {@code [{name, value}]}: every {@code value} (and {@code connectionString}) that is text goes. */
  private static int maskItemValues(ArrayNode items) {
    int masked = 0;
    for (JsonNode item : items) {
      if (item instanceof ObjectNode o) {
        masked += maskField(o, "value");
        masked += maskField(o, "connectionString");
      }
    }
    return masked;
  }

  private static int maskAll(ObjectNode o) {
    int masked = 0;
    for (String name : new ArrayList<>(o.propertyNames())) {
      JsonNode v = o.get(name);
      if (v != null && v.isObject()) {
        // {"DB": {"value": "…", "type": "SQLAzure"}}: the connection string's value.
        masked += maskField((ObjectNode) v, "value");
      } else {
        masked += maskField(o, name);
      }
    }
    return masked;
  }

  private static int maskField(ObjectNode o, String name) {
    JsonNode v = o.get(name);
    if (v == null || v.isNull() || v.isContainer() || (v.isString() && (v.asString().isEmpty()
        || v.asString().equals(MASK)))) {
      return 0;
    }
    o.put(name, MASK);
    return 1;
  }
}
