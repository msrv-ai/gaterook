package com.gaterook.connectors.cloudauth;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Entra ID credentials of an Azure connection, as the Azure and Azure Monitor Logs forms write them: the cloud and the
 * tenant at the top of the config, the {@code credentials} object below it.
 *
 * <ul>
 *   <li>{@link Source#CLI}: the Azure CLI's sign-in on this computer ({@code az login}); GateRook runs
 *       {@code az account get-access-token} only once the person allowed the exact command ({@link #trustedCli});</li>
 *   <li>{@link Source#DEVICE_CODE}: a sign-in the person completes in a browser with a code that Test connection
 *       shows; Azure CLI's public client unless {@link #clientId} names the organization's own app;</li>
 *   <li>{@link Source#CLIENT_SECRET} and {@link Source#CLIENT_CERTIFICATE}: a service principal, whose secret or PEM
 *       certificate comes from the call's {@code SecretResolver}, never from the config.</li>
 * </ul>
 *
 * @param tenantId    the directory: a GUID or a verified domain ({@code contoso.onmicrosoft.com}); {@code null} only for
 *                    the CLI, which then uses its default tenant
 * @param clientId    the application (client) ID: required for a service principal, optional for a device sign-in
 * @param secretField JSON pointer of the client secret or the PEM certificate field, or {@code null}
 * @param trustedCli  the SHA-256 of the CLI command the person allowed ({@code /trustedCli}), or {@code null}
 */
public record AzureSpec(AzureCloud cloud, Source source, String tenantId, String clientId, String secretField,
    String trustedCli) {

  /** {@code credentials.source}. */
  public enum Source {
    CLI("cli"), DEVICE_CODE("deviceCode"), CLIENT_SECRET("clientSecret"), CLIENT_CERTIFICATE("clientCertificate");

    private final String id;

    Source(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }

    static Source of(String id) {
      for (Source s : values()) {
        if (s.id.equals(id)) {
          return s;
        }
      }
      throw new IllegalArgumentException("Unknown Azure credentials source \"" + id + "\"");
    }
  }

  /**
   * The public client of Azure CLI, which azure-identity also uses for device and browser sign-ins: no secret, the
   * redirect and device flows already consented in every tenant that allows the CLI.
   */
  public static final String AZURE_CLI_CLIENT_ID = "04b07795-8ddb-461a-bbee-02f9e1bf7b46";

  static final Pattern GUID = Pattern.compile("^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$");
  private static final Pattern DOMAIN = Pattern.compile(
      "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");
  private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");

  public AzureSpec {
    Objects.requireNonNull(cloud, "cloud");
    Objects.requireNonNull(source, "source");
    tenantId = blankToNull(tenantId);
    clientId = blankToNull(clientId);
    trustedCli = blankToNull(trustedCli);
    if (tenantId != null) {
      tenantId = tenantId.toLowerCase(Locale.ROOT);
      if (tenantId.equals("common") || tenantId.equals("organizations") || tenantId.equals("consumers")) {
        throw new IllegalArgumentException("Name one tenant: common, organizations and consumers aren't tenants.");
      }
      if (!GUID.matcher(tenantId).matches() && !DOMAIN.matcher(tenantId).matches()) {
        throw new IllegalArgumentException("The tenant is a directory ID (a GUID) or a domain like "
            + "contoso.onmicrosoft.com.");
      }
    } else if (source != Source.CLI) {
      throw new IllegalArgumentException("Set the tenant (directory) ID: a service principal or a device sign-in "
          + "signs in to one tenant.");
    }
    if (clientId != null) {
      clientId = clientId.toLowerCase(Locale.ROOT);
      if (!GUID.matcher(clientId).matches()) {
        throw new IllegalArgumentException("The application (client) ID is a GUID.");
      }
    }
    if ((source == Source.CLIENT_SECRET || source == Source.CLIENT_CERTIFICATE) && clientId == null) {
      throw new IllegalArgumentException("A service principal needs its application (client) ID.");
    }
    if ((source == Source.CLIENT_SECRET || source == Source.CLIENT_CERTIFICATE)
        && (secretField == null || secretField.isBlank())) {
      throw new IllegalArgumentException("A service principal needs its secret field");
    }
    if (trustedCli != null && !FINGERPRINT.matcher(trustedCli).matches()) {
      throw new IllegalArgumentException("The allowed Azure CLI command isn't a SHA-256 fingerprint.");
    }
  }

  /**
   * Reads the credentials as the forms write them: {@code cloud}, {@code tenantId} and {@code trustedCli} at the top
   * of the config, {@code {source, clientId}} at {@code pointer}; the secret is the field
   * {@code <pointer>/clientSecret} or {@code <pointer>/certificate}.
   *
   * @param pointer JSON pointer of the {@code credentials} object, e.g. {@code /credentials}
   */
  public static AzureSpec fromConfig(JsonNode config, String pointer) {
    JsonNode c = config.at(pointer);
    if (c.isMissingNode() || !c.isObject()) {
      throw new IllegalArgumentException("No Azure credentials at " + pointer);
    }
    Source source = Source.of(text(c, "source", "cli"));
    String field = switch (source) {
      case CLIENT_SECRET -> pointer + "/clientSecret";
      case CLIENT_CERTIFICATE -> pointer + "/certificate";
      default -> null;
    };
    return new AzureSpec(AzureCloud.of(text(config, "cloud", null)), source, text(config, "tenantId", null),
        text(c, "clientId", null), field, text(config, "trustedCli", null));
  }

  /** The client a device sign-in uses: the configured one, else Azure CLI's public client. */
  public String publicClientId() {
    return clientId != null ? clientId : AZURE_CLI_CLIENT_ID;
  }

  /** Whether {@link #tenantId} is a directory ID rather than a domain. */
  public boolean tenantIsGuid() {
    return tenantId != null && GUID.matcher(tenantId).matches();
  }

  /** What Secrets › Short-lived and Test connection show before a token says who signed in. */
  public String identity() {
    return switch (source) {
      case CLI -> "Azure CLI sign-in on this computer";
      case DEVICE_CODE -> "Azure sign-in (device code)";
      case CLIENT_SECRET, CLIENT_CERTIFICATE -> "service principal " + clientId;
    };
  }

  /** The same credentials with another trusted CLI fingerprint (Test connection compares before and after). */
  public AzureSpec withTrustedCli(String fingerprint) {
    return new AzureSpec(cloud, source, tenantId, clientId, secretField, fingerprint);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static String text(JsonNode node, String field, String dflt) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || !n.isValueNode() || n.asString().isBlank() ? dflt : n.asString().strip();
  }
}
