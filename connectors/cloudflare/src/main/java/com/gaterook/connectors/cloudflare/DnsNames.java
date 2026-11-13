package com.gaterook.connectors.cloudflare;

import java.util.regex.Pattern;

/**
 * DNS record names as agents write them — {@code www.example.com}, {@code www}, {@code @}, {@code *.dev},
 * {@code _dmarc} — made absolute in their zone the way Cloudflare reads them: a name that doesn't end in the zone's
 * name is relative to it. The approver sees the absolute name.
 */
final class DnsNames {

  /** One label of a record name: letters, digits, hyphens and underscores ({@code _acme-challenge}), or {@code *}. */
  private static final Pattern LABEL = Pattern.compile("\\*|[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?");

  private DnsNames() {}

  /** Checks a name before its zone is known: its labels, without the zone. */
  static void check(String name) throws Refusal {
    String n = trim(name);
    if (n.equals("@")) {
      return;
    }
    labels(n, name);
  }

  /** The absolute name of {@code name} in {@code zone}, checked. */
  static String absolute(String name, String zone) throws Refusal {
    String n = trim(name);
    String full;
    if (n.equals("@") || n.equals(zone)) {
      full = zone;
    } else if (n.endsWith("." + zone)) {
      full = n;
    } else {
      full = n + "." + zone;
    }
    if (full.length() > 253) {
      throw Refusal.badInput("The record name " + Args.abbreviate(full) + " is longer than 253 characters.", null);
    }
    labels(full, name);
    if (full.indexOf('*') > 0) {
      throw Refusal.badInput("\"" + Args.abbreviate(name) + "\" has a * that isn't its first label.",
          "A wildcard record is written *.example.com or *.dev.");
    }
    return full;
  }

  private static String trim(String name) {
    String n = Args.lower(name.strip());
    return n.endsWith(".") && n.length() > 1 ? n.substring(0, n.length() - 1) : n;
  }

  private static void labels(String n, String original) throws Refusal {
    if (n.isEmpty() || n.length() > 253) {
      throw Refusal.badInput("\"" + Args.abbreviate(original) + "\" isn't a record name.", null);
    }
    for (String label : n.split("\\.", -1)) {
      if (!LABEL.matcher(label).matches()) {
        throw Refusal.badInput("\"" + Args.abbreviate(original) + "\" isn't a record name: each part is letters, "
            + "digits, hyphens or underscores.", "Write www.example.com, www, @ or *.example.com.");
      }
    }
  }
}
