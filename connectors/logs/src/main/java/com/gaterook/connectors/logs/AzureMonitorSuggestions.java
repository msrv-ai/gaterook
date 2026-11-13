package com.gaterook.connectors.logs;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.AzureCloud.Audience;
import com.gaterook.connectors.cloudauth.AzureCredentials;
import com.gaterook.connectors.cloudauth.AzureSpec;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudLocal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Azure Monitor Logs form. On this computer: the tenants and cloud of the Azure CLI's sign-in
 * ({@code ~/.azure/azureProfile.json}), the tenant filled in for a sign-in that needs one when the CLI knows only one.
 * Signed in, with the draft's credentials: the Log Analytics workspaces (by workspace ID) and Application Insights
 * components the account may see in its subscriptions, through Resource Manager and Azure Resource Graph, reads only.
 * The Azure CLI runs only once Test connection had the person allow its command; a device sign-in only once the
 * person signed in there.
 */
final class AzureMonitorSuggestions {

  static final String SUBSCRIPTIONS_API = "2022-12-01";
  static final String GRAPH_API = "2024-04-01";
  /** Subscriptions one Resource Graph query covers here; Resource Graph itself takes up to 1,000. */
  static final int MAX_SUBSCRIPTIONS = 200;
  /** Workspaces and components of every subscription, by name: one Resource Graph query. */
  static final String QUERY = "resources | where type =~ 'microsoft.operationalinsights/workspaces' or type =~ "
      + "'microsoft.insights/components' | project id, name, type, location, subscriptionId, resourceGroup, "
      + "customerId = tostring(properties.customerId) | order by name asc";
  private static final Set<String> CLOUDS = Set.of("AzureCloud", "AzureUSGovernment", "AzureChinaCloud");
  /** The form's pattern of a component's resource ID. */
  private static final Pattern COMPONENT = Pattern.compile("/subscriptions/[0-9a-fA-F-]{36}/resourceGroups/[^/]+/"
      + "providers/[Mm]icrosoft\\.[Ii]nsights/components/[^/]+");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AzureMonitorSuggestions() {}

  static void local(ConnectionConfig cfg, CloudLocal local, Suggestions.Builder out) {
    JsonNode c = cfg.config();
    String source = Optional.ofNullable(LogsSuggestions.text(c, "/credentials/source")).orElse("cli");
    boolean needsTenant = !source.equals("cli");
    List<CloudLocal.AzureSubscription> subscriptions = local.azureSubscriptions();
    List<Option> tenants = local.azureTenantOptions();
    String tenant = LogsSuggestions.text(c, "/tenantId");
    String value = needsTenant && tenant == null && tenants.size() == 1 ? tenants.getFirst().value() : null;
    out.value("/tenantId", "the Azure CLI's sign-in", value, tenants);
    Set<String> clouds = new LinkedHashSet<>();
    subscriptions.forEach(s -> {
      if (s.cloud() != null) {
        clouds.add(s.cloud());
      }
    });
    if (clouds.size() == 1 && CLOUDS.contains(clouds.iterator().next())) {
      String cloud = clouds.iterator().next();
      out.value("/cloud", "the Azure CLI's sign-in", cloud, List.of(Option.of(cloud)));
    }
    if (source.equals("cli") && subscriptions.isEmpty()) {
      out.note("The Azure CLI isn't signed in on this computer: run az login, or choose another sign-in.");
    }
    if (needsTenant && tenant == null && value == null) {
      out.note("A service principal or a device sign-in signs in to one tenant: set Tenant (directory) ID.");
    }
  }

  /**
   * Signed in with the draft's credentials for Resource Manager: the subscriptions, then one Resource Graph query
   * for their workspaces and components. Nothing runs or asks for the person: the CLI's command must already be
   * allowed, a device sign-in already done.
   */
  static void service(ConnectionConfig cfg, Backend.Call call, Suggestions.Builder out) throws ConnectorException {
    AzureSpec spec = AzureSpec.fromConfig(cfg.config(), "/credentials");
    AzureCredentials azure = call.cloud().azure();
    if (spec.source() == AzureSpec.Source.CLI
        && !azure.cliCommand(spec).fingerprint().equals(spec.trustedCli())) {
      out.note("Workspaces and components appear after Test connection: it asks you to allow the Azure CLI command "
          + "first.");
      return;
    }
    AzureCredentials.AccessToken token;
    try {
      token = azure.token(new CloudCredentials.Owner(call.cfg().id(), call.cfg().version(), call.cfg().name()), spec,
          Audience.ARM, call.secrets(), call.ctx().remaining().minus(Http.MARGIN));
    } catch (ConnectorException e) {
      if (spec.source() == AzureSpec.Source.DEVICE_CODE) {
        out.note("Workspaces and components appear after Test connection: sign in with the code it shows first.");
        return;
      }
      throw e;
    }
    call.ctx().taint(token.value());
    if (spec.tenantIsGuid() && token.tenantId() != null && !spec.tenantId().equals(token.tenantId())) {
      out.note("The Azure sign-in belongs to tenant " + token.tenantId() + ", not " + spec.tenantId() + ": sign in to "
          + "the connection's tenant, or fix the tenant ID.");
      return;
    }
    String arm = base(azure.base(spec.cloud(), Audience.ARM));
    JsonNode listed = send(call, azure, spec, token, URI.create(arm + "/subscriptions?api-version="
        + SUBSCRIPTIONS_API), null, "Resource Manager");
    Map<String, String> subscriptions = new LinkedHashMap<>();
    boolean complete = listed.path("nextLink").asString("").isEmpty();
    for (JsonNode s : listed.path("value")) {
      String id = s.path("subscriptionId").asString("").toLowerCase(Locale.ROOT);
      if (!Settings.GUID.matcher(id).matches() || !s.path("state").asString("Enabled").equals("Enabled")) {
        continue;
      }
      if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
        complete = false;
        break;
      }
      subscriptions.put(id, s.path("displayName").asString(id));
    }
    String who = "Signed in as " + token.account();
    if (subscriptions.isEmpty()) {
      out.note(who + ", but the account sees no Azure subscription: type the workspace IDs.");
      return;
    }
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    ArrayNode subs = body.putArray("subscriptions");
    subscriptions.keySet().forEach(subs::add);
    body.put("query", QUERY);
    body.putObject("options").put("$top", Suggestions.MAX_OPTIONS * 2).put("resultFormat", "objectArray");
    JsonNode rows = send(call, azure, spec, token, URI.create(arm + "/providers/Microsoft.ResourceGraph/resources"
        + "?api-version=" + GRAPH_API), body.toString(), "Azure Resource Graph");
    complete &= rows.path("$skipToken").asString("").isEmpty();
    List<Option> workspaces = new ArrayList<>();
    List<Option> components = new ArrayList<>();
    for (JsonNode row : rows.path("data")) {
      String type = row.path("type").asString("").toLowerCase(Locale.ROOT);
      String name = row.path("name").asString("");
      String subscription = row.path("subscriptionId").asString("").toLowerCase(Locale.ROOT);
      String location = row.path("location").asString("");
      String in = subscriptions.get(subscription);
      String detail = (location.isBlank() ? "" : location + " · ") + (in == null ? subscription : in);
      if (type.equals("microsoft.operationalinsights/workspaces")) {
        String id = row.path("customerId").asString("").toLowerCase(Locale.ROOT);
        if (Settings.GUID.matcher(id).matches()) {
          workspaces.add(new Option(id, name.isBlank() ? null : name, detail));
        }
      } else if (type.equals("microsoft.insights/components")) {
        String id = "/subscriptions/" + subscription + "/resourceGroups/" + row.path("resourceGroup").asString("")
            + "/providers/Microsoft.Insights/components/" + name;
        if (COMPONENT.matcher(id).matches() && Settings.COMPONENT.matcher(id.toLowerCase(Locale.ROOT)).matches()) {
          components.add(new Option(id, name, detail));
        }
      }
    }
    out.field("/workspaces", new Suggestions.Field(workspaces, null, "your Azure subscriptions", complete));
    out.field("/applicationInsights", new Suggestions.Field(components, null, "your Azure subscriptions", complete));
    String in = " in " + LogsSuggestions.count(subscriptions.size(), "subscription", "subscriptions");
    out.note(workspaces.isEmpty() && components.isEmpty()
        ? who + ": no Log Analytics workspace or Application Insights component" + in + "."
        : who + ": " + LogsSuggestions.count(workspaces.size(), "Log Analytics workspace", "Log Analytics workspaces")
            + " and " + LogsSuggestions.count(components.size(), "Application Insights component",
            "Application Insights components") + in + ". Choose the ones agents may query.");
  }

  /** One Resource Manager request with the call's token: only to the cloud's Resource Manager. */
  private static JsonNode send(Backend.Call call, AzureCredentials azure, AzureSpec spec,
      AzureCredentials.AccessToken token, URI uri, String body, String what) throws ConnectorException {
    if (!azure.sendsTokensTo(spec.cloud(), Audience.ARM, uri)) {
      throw ConnectorException.denied("GateRook sends Azure tokens only to the cloud's Resource Manager, not to "
          + uri.getHost() + ".");
    }
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Accept", "application/json")
        .header("Authorization", "Bearer " + token.value());
    if (body == null) {
      b.GET();
    } else {
      b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    }
    Http.Response r = Http.send(call.clients().google(), b, call.ctx(), what);
    JsonNode tree;
    try {
      tree = JSON.readTree(r.body().isBlank() ? "{}" : r.body());
    } catch (JacksonException e) {
      throw ConnectorException.resourceError(what + " answered " + r.status() + " with something that isn't JSON.",
          null);
    }
    if (r.ok()) {
      return tree;
    }
    String message = Http.excerpt(tree.at("/error/message").asString(""));
    throw switch (r.status()) {
      case 401 -> ConnectorException.unavailable(what + " refused the token: " + message + " Sign in again, then "
          + "test again.", null);
      case 403 -> ConnectorException.resourceError(what + " denied " + token.account() + ": " + message + " Assign "
          + "Reader on the subscriptions, or type the workspace IDs.", null);
      case 429 -> ConnectorException.resourceError(what + " is throttling requests: try again in a minute.", null);
      default -> r.status() >= 500 ? ConnectorException.unavailable(what + " failed (" + r.status() + "): " + message,
          null) : ConnectorException.resourceError(what + " answered " + r.status() + ": " + message, null);
    };
  }

  private static String base(URI uri) {
    String b = uri.toString();
    return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
  }
}
