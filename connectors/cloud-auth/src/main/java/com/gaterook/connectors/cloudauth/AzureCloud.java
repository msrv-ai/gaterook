package com.gaterook.connectors.cloudauth;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The Azure clouds GateRook signs in to, with every endpoint it uses there (the names {@code az cloud list} gives
 * them). The table is code, not configuration: where an Entra ID token may go is decided here, never by a server's
 * answer or a setting an agent could propose.
 *
 * <p>Azure Government and Azure operated by 21Vianet (China) have their own sign-in authority, Resource Manager and
 * Log Analytics hosts; Storage and Service Bus tokens use one audience in every cloud, with the cloud's own host
 * suffix. Azure AI Search has an audience of its own in each cloud ({@code https://search.azure.com},
 * {@code .azure.us}, {@code .azure.cn}) and its services under {@code search.windows.net}, {@code search.azure.us} and
 * {@code search.azure.cn}.
 */
public enum AzureCloud {

  AZURE_PUBLIC("AzureCloud", "login.microsoftonline.com", "management.azure.com", "api.loganalytics.io",
      "core.windows.net", "vault.azure.net", "servicebus.windows.net", "search.windows.net", "search.azure.com"),
  AZURE_US_GOVERNMENT("AzureUSGovernment", "login.microsoftonline.us", "management.usgovcloudapi.net",
      "api.loganalytics.us", "core.usgovcloudapi.net", "vault.usgovcloudapi.net", "servicebus.usgovcloudapi.net",
      "search.azure.us", "search.azure.us"),
  AZURE_CHINA("AzureChinaCloud", "login.chinacloudapi.cn", "management.chinacloudapi.cn",
      "api.loganalytics.azure.cn", "core.chinacloudapi.cn", "vault.azure.cn", "servicebus.chinacloudapi.cn",
      "search.azure.cn", "search.azure.cn");

  /** What a token is for: its {@code aud}, the resource of {@code <audience>/.default}. */
  public enum Audience {
    ARM("Azure Resource Manager"),
    LOG_ANALYTICS("Log Analytics"),
    STORAGE("Azure Storage"),
    KEY_VAULT("Key Vault"),
    SERVICE_BUS("Service Bus"),
    SEARCH("Azure AI Search");

    private final String title;

    Audience(String title) {
      this.title = title;
    }

    /** {@code Azure Resource Manager}, for texts. */
    public String title() {
      return title;
    }
  }

  /** One DNS label: a storage account or a vault name. */
  private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  private final String id;
  private final String authorityHost;
  private final String armHost;
  private final String logAnalyticsHost;
  private final String storageSuffix;
  private final String keyVaultSuffix;
  private final String serviceBusSuffix;
  private final String searchSuffix;
  private final String searchResourceHost;

  AzureCloud(String id, String authorityHost, String armHost, String logAnalyticsHost, String storageSuffix,
      String keyVaultSuffix, String serviceBusSuffix, String searchSuffix, String searchResourceHost) {
    this.id = id;
    this.authorityHost = authorityHost;
    this.armHost = armHost;
    this.logAnalyticsHost = logAnalyticsHost;
    this.storageSuffix = storageSuffix;
    this.keyVaultSuffix = keyVaultSuffix;
    this.serviceBusSuffix = serviceBusSuffix;
    this.searchSuffix = searchSuffix;
    this.searchResourceHost = searchResourceHost;
  }

  /** {@code AzureCloud}, {@code AzureUSGovernment}, {@code AzureChinaCloud}: what the connection form stores. */
  public String id() {
    return id;
  }

  /** The cloud of a form value; {@code null} or blank is the public cloud. */
  public static AzureCloud of(String id) {
    if (id == null || id.isBlank()) {
      return AZURE_PUBLIC;
    }
    for (AzureCloud c : values()) {
      if (c.id.equalsIgnoreCase(id.strip())) {
        return c;
      }
    }
    throw new IllegalArgumentException("Unknown Azure cloud \"" + id + "\": AzureCloud, AzureUSGovernment or "
        + "AzureChinaCloud");
  }

  /** {@code https://login.microsoftonline.com}: the Entra ID authority of the cloud. */
  public URI authority() {
    return URI.create("https://" + authorityHost);
  }

  /** {@code https://management.azure.com}: Azure Resource Manager. */
  public URI arm() {
    return URI.create("https://" + armHost);
  }

  /** {@code https://api.loganalytics.io}: the Log Analytics query API. */
  public URI logAnalytics() {
    return URI.create("https://" + logAnalyticsHost);
  }

  /** {@code core.windows.net}: the storage suffix of the cloud. */
  public String storageSuffix() {
    return storageSuffix;
  }

  /** {@code vault.azure.net}: the Key Vault suffix of the cloud. */
  public String keyVaultSuffix() {
    return keyVaultSuffix;
  }

  /** {@code servicebus.windows.net}: the Service Bus and Event Hubs namespace suffix of the cloud. */
  public String serviceBusSuffix() {
    return serviceBusSuffix;
  }

  /** {@code search.windows.net}: the Azure AI Search service suffix of the cloud. */
  public String searchSuffix() {
    return searchSuffix;
  }

  /**
   * The cloud of an Azure AI Search service host ({@code contoso.search.windows.net}: one label under a cloud's
   * {@link #searchSuffix}), or {@code null} for any other host.
   */
  public static AzureCloud ofSearchHost(String host) {
    if (host == null) {
      return null;
    }
    String h = host.toLowerCase(Locale.ROOT);
    for (AzureCloud c : values()) {
      if (oneLabelUnder(h, c.searchSuffix)) {
        return c;
      }
    }
    return null;
  }

  /**
   * {@code <account>.blob.core.windows.net}: the host of one storage service of an account.
   *
   * @param service {@code blob}, {@code queue}, {@code table}, {@code file} or {@code dfs}
   * @throws IllegalArgumentException for a name that isn't one DNS label or an unknown service
   */
  public String storageHost(String account, String service) {
    if (!LABEL.matcher(account).matches() || !java.util.Set.of("blob", "queue", "table", "file", "dfs")
        .contains(service)) {
      throw new IllegalArgumentException("Not a storage account and service: " + account + " " + service);
    }
    return account + "." + service + "." + storageSuffix;
  }

  /** {@code <vault>.vault.azure.net}: the host of one vault. */
  public String keyVaultHost(String vault) {
    if (!LABEL.matcher(vault).matches()) {
      throw new IllegalArgumentException("Not a vault name: " + vault);
    }
    return vault + "." + keyVaultSuffix;
  }

  /** {@code <namespace>.servicebus.windows.net}: the host of one Service Bus or Event Hubs namespace. */
  public String serviceBusHost(String namespace) {
    if (!LABEL.matcher(namespace).matches()) {
      throw new IllegalArgumentException("Not a namespace name: " + namespace);
    }
    return namespace + "." + serviceBusSuffix;
  }

  /**
   * The resource a token is asked for ({@code <resource>/.default}). Resource Manager's ends in a slash, as Entra ID
   * registers it; Storage and Service Bus use one resource in every cloud; Azure AI Search's is the cloud's own
   * ({@code https://search.azure.com}, {@code https://search.azure.us}, {@code https://search.azure.cn}).
   */
  public String resource(Audience audience) {
    return switch (audience) {
      case ARM -> "https://" + armHost + "/";
      case LOG_ANALYTICS -> "https://" + logAnalyticsHost;
      case STORAGE -> "https://storage.azure.com";
      case KEY_VAULT -> "https://" + keyVaultSuffix;
      case SERVICE_BUS -> "https://servicebus.azure.net";
      case SEARCH -> "https://" + searchResourceHost;
    };
  }

  /** {@code https://management.azure.com/.default}: the scope of a token request. */
  public String scope(Audience audience) {
    String resource = resource(audience);
    return (resource.endsWith("/") ? resource : resource + "/") + ".default";
  }

  /**
   * Whether a token for {@code audience} may be sent to {@code url}: HTTPS on port 443, no user info, and the host of
   * that audience in this cloud — Resource Manager and Log Analytics by exact name, a storage account
   * ({@code <account>.blob.core.windows.net} and the other storage services), a vault ({@code <vault>.vault.azure.net}),
   * a namespace ({@code <ns>.servicebus.windows.net}) or a search service ({@code <service>.search.windows.net}) by one
   * label under the cloud's suffix. Code that other people deploy runs elsewhere ({@code azurewebsites.net}, a custom
   * domain), where a token would reach whoever deployed it.
   */
  public boolean accepts(Audience audience, URI url) {
    if (url == null || !"https".equalsIgnoreCase(url.getScheme()) || url.getRawUserInfo() != null) {
      return false;
    }
    String host = url.getHost();
    int port = url.getPort();
    if (host == null || (port != -1 && port != 443)) {
      return false;
    }
    String h = host.toLowerCase(Locale.ROOT);
    return switch (audience) {
      case ARM -> h.equals(armHost);
      case LOG_ANALYTICS -> h.equals(logAnalyticsHost);
      case STORAGE -> storageHost(h);
      case KEY_VAULT -> oneLabelUnder(h, keyVaultSuffix);
      case SERVICE_BUS -> oneLabelUnder(h, serviceBusSuffix);
      case SEARCH -> oneLabelUnder(h, searchSuffix);
    };
  }

  private static boolean oneLabelUnder(String host, String suffix) {
    return host.endsWith("." + suffix) && LABEL.matcher(host.substring(0, host.length() - suffix.length() - 1))
        .matches();
  }

  private boolean storageHost(String h) {
    for (String service : new String[] {"blob", "queue", "table", "file", "dfs"}) {
      String suffix = "." + service + "." + storageSuffix;
      if (h.endsWith(suffix) && LABEL.matcher(h.substring(0, h.length() - suffix.length())).matches()) {
        return true;
      }
    }
    return false;
  }
}
