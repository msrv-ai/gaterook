package ai.msrv.gaterook.connectors.cloudauth;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * GCP credentials of a connection, the {@code credentials} object of the GCP, Logs and GCP SM forms (reports 07 §4,
 * 09 §4, 12 §4.3): Google sign-in on this computer (ADC) or a service account key, optionally impersonating a service
 * account for {@link #lifetimeSeconds} (900 s by default: "a short-lived token, valid 15 min").
 *
 * @param keyField JSON pointer of the {@code serviceAccountKey} secret field; the value comes from the
 *                 {@code SecretResolver} of the call, never from the config
 */
public record GcpSpec(Source source, String keyField, String impersonate, List<String> delegates, int lifetimeSeconds,
    String quotaProject) {

  /** {@code credentials.source}. */
  public enum Source { ADC, SERVICE_ACCOUNT_KEY }

  public GcpSpec {
    Objects.requireNonNull(source, "source");
    delegates = delegates == null ? List.of() : List.copyOf(delegates);
    if (lifetimeSeconds < 300 || lifetimeSeconds > 3600) {
      throw new IllegalArgumentException("lifetimeSeconds must be 300-3600");
    }
    if (source == Source.SERVICE_ACCOUNT_KEY && (keyField == null || keyField.isBlank())) {
      throw new IllegalArgumentException("A service account key needs its secret field");
    }
    if (impersonate != null && impersonate.isBlank()) {
      impersonate = null;
    }
  }

  /**
   * Reads {@code credentials} as the forms write it: {@code {source, impersonate, delegates, lifetimeSeconds,
   * quotaProject}}; the key is the secret field {@code <pointer>/serviceAccountKey}.
   *
   * @param pointer JSON pointer of the {@code credentials} object in the connection config, e.g. {@code /credentials}
   */
  public static GcpSpec fromConfig(JsonNode config, String pointer) {
    JsonNode c = config.at(pointer);
    if (c.isMissingNode() || !c.isObject()) {
      throw new IllegalArgumentException("No GCP credentials at " + pointer);
    }
    Source source = switch (text(c, "source", "adc")) {
      case "adc" -> Source.ADC;
      case "serviceAccountKey" -> Source.SERVICE_ACCOUNT_KEY;
      default -> throw new IllegalArgumentException("Unknown GCP credentials source " + text(c, "source", ""));
    };
    List<String> delegates = new ArrayList<>();
    for (JsonNode d : c.path("delegates")) {
      delegates.add(d.asString());
    }
    return new GcpSpec(source, source == Source.SERVICE_ACCOUNT_KEY ? pointer + "/serviceAccountKey" : null,
        text(c, "impersonate", null), delegates, c.path("lifetimeSeconds").asInt(900), text(c, "quotaProject", null));
  }

  /** Short-lived when impersonating; a plain ADC or key token is what Secrets › Short-lived lists otherwise. */
  public String identity() {
    return impersonate != null ? impersonate : source == Source.ADC ? "Google sign-in on this computer" : "service account key";
  }

  private static String text(JsonNode node, String field, String dflt) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || n.asString().isBlank() ? dflt : n.asString();
  }
}
