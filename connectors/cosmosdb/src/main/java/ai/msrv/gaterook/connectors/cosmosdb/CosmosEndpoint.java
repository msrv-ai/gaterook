package ai.msrv.gaterook.connectors.cosmosdb;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The account endpoint of a connection, read strictly: GateRook sends a key or a token only to a Cosmos DB host of
 * Azure — {@code https://<account>.documents.azure.com} (public cloud), {@code .documents.azure.us} (Azure
 * Government), {@code .documents.azure.cn} (Azure operated by 21Vianet), port 443 — or to the emulator on this computer
 * ({@code localhost}, {@code 127.0.0.1} or {@code [::1]} with a port). Plain HTTP is only for the emulator, and only in
 * development builds. Every request goes to this address: never to one a response names, and never redirected.
 *
 * @param scheme  {@code https}, or {@code http} for the emulator of a development build
 * @param host    the host in lower case, as in the URL ({@code [::1]} with its brackets)
 * @param port    the port, {@code -1} for HTTPS's default
 * @param cloud   which Azure cloud the host belongs to, or the emulator
 * @param account the account's host label ({@code acme}, {@code acme-westeurope} for a regional endpoint), or
 *                {@code emulator}
 */
record CosmosEndpoint(String scheme, String host, int port, Cloud cloud, String account) {

  /** Azure's clouds by their Cosmos DB host suffix and Microsoft Entra ID's sign-in host. */
  enum Cloud {
    PUBLIC(".documents.azure.com", "login.microsoftonline.com", "Azure"),
    GOVERNMENT(".documents.azure.us", "login.microsoftonline.us", "Azure Government"),
    CHINA(".documents.azure.cn", "login.chinacloudapi.cn", "Azure operated by 21Vianet"),
    EMULATOR(null, null, "the emulator");

    final String suffix;
    final String login;
    final String label;

    Cloud(String suffix, String login, String label) {
      this.suffix = suffix;
      this.login = login;
      this.label = label;
    }
  }

  /** A DNS label of an account or a regional endpoint: Cosmos DB account names are 3 to 44 characters, a region more. */
  private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])");

  /** Thrown with a message that says what to enter. */
  static final class Invalid extends Exception {
    private static final long serialVersionUID = 1L;

    Invalid(String message) {
      super(message, null, false, false);
    }
  }

  /**
   * Reads the endpoint a person entered.
   *
   * @param plainLoopback whether plain HTTP to the emulator on this computer is allowed: development builds only
   */
  static CosmosEndpoint parse(String text, boolean plainLoopback) throws Invalid {
    if (text == null || text.isBlank()) {
      throw new Invalid("Enter the account endpoint, such as https://acme.documents.azure.com.");
    }
    String t = text.strip();
    if (t.contains(";") || t.toLowerCase(Locale.ROOT).contains("accountkey")) {
      throw new Invalid("Enter only the account endpoint, such as https://acme.documents.azure.com: the key goes into "
          + "Account key, never into the endpoint.");
    }
    URI uri;
    try {
      uri = new URI(t);
    } catch (URISyntaxException e) {
      throw new Invalid("The account endpoint isn't a URL. Enter it as the portal's Keys page shows it, such as "
          + "https://acme.documents.azure.com.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http")) {
      throw new Invalid("The account endpoint starts with https://, such as https://acme.documents.azure.com.");
    }
    if (host.isEmpty() || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
      throw new Invalid("Enter the account endpoint without a path, query or user, such as "
          + "https://acme.documents.azure.com.");
    }
    int port = uri.getPort();
    if (loopback(host)) {
      if (port < 1 || port > 65_535) {
        throw new Invalid("The emulator's endpoint names its port, such as http://127.0.0.1:8081.");
      }
      if (scheme.equals("http") && !plainLoopback) {
        throw new Invalid("Plain HTTP to the emulator is only for development builds of GateRook. Start the emulator "
            + "with --protocol https and a certificate this computer trusts, and use https://.");
      }
      return new CosmosEndpoint(scheme, host, port, Cloud.EMULATOR, "emulator");
    }
    if (!scheme.equals("https")) {
      throw new Invalid("GateRook reaches Cosmos DB over HTTPS only: use https://" + host + ".");
    }
    if (port != -1 && port != 443) {
      throw new Invalid("Cosmos DB's endpoint has no port of its own: use https://" + host + ".");
    }
    for (Cloud cloud : Cloud.values()) {
      if (cloud.suffix != null && host.endsWith(cloud.suffix)) {
        String label = host.substring(0, host.length() - cloud.suffix.length());
        if (!LABEL.matcher(label).matches()) {
          throw new Invalid("The account endpoint is https://<account>" + cloud.suffix + ", with the account's name "
              + "(lower-case letters, digits and hyphens).");
        }
        return new CosmosEndpoint("https", host, -1, cloud, label);
      }
    }
    throw new Invalid("GateRook sends Cosmos DB keys and tokens only to Azure's Cosmos DB hosts "
        + "(https://<account>.documents.azure.com, .documents.azure.us or .documents.azure.cn), not to " + host + ".");
  }

  static boolean loopback(String host) {
    return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
  }

  /** Whether this is the emulator on this computer. */
  boolean emulator() {
    return cloud == Cloud.EMULATOR;
  }

  /** {@code https://acme.documents.azure.com}, {@code http://127.0.0.1:8081}. */
  URI base() {
    return URI.create(scheme + "://" + host + (port < 0 ? "" : ":" + port));
  }

  /** The host, with the port for the emulator: for targets and messages. */
  String label() {
    return port < 0 ? host : host + ":" + port;
  }

  /** The resource of Microsoft Entra ID tokens for this account: {@code https://<account host>/.default}. */
  String scope() {
    return "https://" + host + "/.default";
  }
}
