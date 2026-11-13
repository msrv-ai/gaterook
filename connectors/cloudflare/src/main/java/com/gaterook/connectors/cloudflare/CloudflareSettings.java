package com.gaterook.connectors.cloudflare;

import com.gaterook.connector.api.ConnectionConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * A Cloudflare connection: the account its account-level tools read, the zones agents may use (none listed: every
 * zone of that account the token can see) and the API token, a secret field.
 */
record CloudflareSettings(String accountId, List<String> zones) {

  /** The JSON pointer of the API token. */
  static final String TOKEN = "/credentials/token";

  /** A zone name as Cloudflare writes it: lower-case labels, Punycode for international names. */
  static final Pattern ZONE_NAME = Pattern.compile(
      "(?=.{3,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  /** A connection whose settings can't be used: every call is refused with this. */
  static final class InvalidSettingsException extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidSettingsException(String message) {
      super(message, null, false, false);
    }
  }

  CloudflareSettings {
    zones = List.copyOf(zones);
  }

  static CloudflareSettings parse(ConnectionConfig cfg) throws InvalidSettingsException {
    JsonNode c = cfg.config();
    String account = c.path("accountId").asString("");
    if (!Args.HEX32.matcher(account).matches()) {
      throw new InvalidSettingsException("Account ID must be the account's 32-character ID (0-9, a-f).");
    }
    Set<String> zones = new LinkedHashSet<>();
    JsonNode list = c.path("zones");
    if (!list.isMissingNode() && !list.isNull()) {
      if (!list.isArray()) {
        throw new InvalidSettingsException("Zones must be a list of zone names.");
      }
      for (JsonNode z : list) {
        String name = normalize(z.asString(""));
        if (name == null) {
          throw new InvalidSettingsException("\"" + Args.abbreviate(z.asString("")) + "\" isn't a zone name, such "
              + "as example.com.");
        }
        zones.add(name);
      }
    }
    return new CloudflareSettings(account, new ArrayList<>(zones));
  }

  /** A zone name in Cloudflare's form ({@code Example.COM.} → {@code example.com}), or {@code null}. */
  static String normalize(String name) {
    if (name == null) {
      return null;
    }
    String n = Args.lower(name.strip());
    if (n.endsWith(".")) {
      n = n.substring(0, n.length() - 1);
    }
    return ZONE_NAME.matcher(n).matches() ? n : null;
  }

  /** Whether agents may use the zone of this (normalized) name. */
  boolean zoneAllowed(String name) {
    return zones.isEmpty() || zones.contains(name);
  }

  /** "example.com, example.org" or "every zone of account 0123…" for descriptions and refusals. */
  String zonesText() {
    return zones.isEmpty() ? "every zone of account " + accountId + " that the token can see"
        : String.join(", ", zones);
  }
}
