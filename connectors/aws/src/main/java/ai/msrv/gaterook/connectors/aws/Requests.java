package ai.msrv.gaterook.connectors.aws;

import tools.jackson.databind.JsonNode;

/** Checks shared by {@code aws_describe} and {@code aws_metrics} (report 09 §3.5), without I/O. */
final class Requests {

  private Requests() {}

  /**
   * The call's region: the argument, else the connection's first; it must be one of the connection's regions and
   * one the policy allows ({@code DENY cloud.scope_not_allowed}).
   */
  static String region(JsonNode args, String connection, Settings s, AwsPolicy p) throws Refusal {
    String region = args.hasNonNull("region") ? args.get("region").asString("").strip() : s.firstRegion();
    if (!s.hasRegion(region)) {
      throw new Refusal("scope_not_allowed", "\"" + abbreviate(region) + "\" isn't a region of " + connection + " ("
          + String.join(", ", s.regions()) + ").", null);
    }
    if (!p.regionAllowed(region)) {
      throw new Refusal("scope_not_allowed", region + " isn't allowed: " + p.regionsText() + ".", null);
    }
    return region;
  }

  /**
   * Whether the policy lets agents see {@code service} ({@code null}: a custom metric namespace).
   *
   * @param what the type or namespace, for the refusal
   */
  static void service(String service, String what, AwsPolicy p) throws Refusal {
    if (p.serviceAllowed(service)) {
      return;
    }
    if (service == null) {
      throw new Refusal("service_not_allowed", what + " is a custom namespace; the " + p.env().id() + " policy lists "
          + "the services agents may read (" + p.servicesText() + "), and custom namespaces aren't among them.", null);
    }
    if (Services.sensitive(service)) {
      throw new Refusal("sensitive_type", what + " belongs to " + service + ", which holds identities, keys or "
          + "secrets: agents see it only when the " + p.env().id() + " policy lists " + service + " by name.", null);
    }
    throw new Refusal("service_not_allowed", what + " belongs to " + service + ", which isn't allowed: "
        + p.servicesText() + ".", null);
  }

  /** Refuses control, bidi and zero-width characters in values the gateway sends on: they hide what is asked. */
  static void printable(String value, String field) throws Refusal {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == 0x7f || (c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e)
          || (c >= 0x2066 && c <= 0x2069) || c == 0xfeff) {
        throw new Refusal("bad_input", String.format("%s has the invisible character U+%04X; type it again.", field,
            (int) c), null);
      }
    }
  }

  static String abbreviate(String s) {
    return s.length() <= 80 ? s : s.substring(0, 80) + "…";
  }
}
