package com.gaterook.connectors.azure;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.AzureCredentials;
import com.gaterook.connectors.cloudauth.AzureSpec;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudLocal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Help for the Azure form ({@link com.gaterook.connector.api.Connector#suggest}).
 *
 * <p>On this computer: the subscriptions of the Azure CLI's sign-in ({@code az login}), the default one filled in
 * while Subscriptions is empty; their tenants, filled in when one tenant fits the chosen subscriptions or the CLI knows
 * only one; and their cloud. Signed in with the draft's identity, while Subscriptions may still be empty
 * ({@code x-suggest}): the subscriptions it can see, the only one filled in, and the Log Analytics workspaces and
 * Application Insights components of the chosen subscriptions, read with Resource Manager list calls.
 *
 * <p>A suggestion never runs the Azure CLI and never starts a device sign-in: both may need the person, so they wait
 * for Test connection and a note says so. Only GETs are sent, each from GateRook's share of Resource Manager reads.
 */
final class AzureSuggestions {

  /** Where the options of this computer come from, as the hint under a field names it. */
  static final String CLI = "your Azure CLI sign-in";
  /** Where the options of the signed-in identity come from. */
  static final String AZURE = "Azure";
  static final String WORKSPACES_API = "2023-09-01";
  static final String COMPONENTS_API = "2020-02-02";
  /** Time kept back from the deadline: no list starts after it. */
  static final Duration LAST_LIST = Duration.ofMillis(1_500);

  /** The clouds of the form's choice, by the name {@code az cloud list} gives them, with the form's titles. */
  private static final Map<String, String> CLOUDS = Map.of("AzureCloud", "Azure", "AzureUSGovernment",
      "Azure Government", "AzureChinaCloud", "Azure China (21Vianet)");
  /** A component's resource ID as the form's pattern accepts it. */
  private static final Pattern COMPONENT = Pattern.compile(
      "^/subscriptions/[0-9a-fA-F-]{36}/resourceGroups/[^/]+/providers/[Mm]icrosoft\\.[Ii]nsights/components/[^/]+$");

  private AzureSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      CloudLocal local, CloudCredentials cloud, HttpClient http) {
    return scope == Suggestions.Scope.SERVICE ? service(cfg, secrets, ctx, cloud, http) : local(cfg, local);
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  static Suggestions local(ConnectionConfig cfg, CloudLocal local) {
    Suggestions.Builder out = Suggestions.builder();
    List<CloudLocal.AzureSubscription> subscriptions = local.azureSubscriptions();
    if (subscriptions.isEmpty()) {
      if (cli(cfg)) {
        out.note("The Azure CLI isn't signed in on this computer: run az login, or choose another way to sign in.");
      }
      return out.build();
    }
    List<String> chosen = guids(cfg.strings("/subscriptions"));
    CloudLocal.AzureSubscription preferred = subscriptions.stream().filter(CloudLocal.AzureSubscription::isDefault)
        .findFirst().orElse(null);
    out.value("/subscriptions", CLI, chosen.isEmpty() && preferred != null ? preferred.id() : null,
        local.azureSubscriptionOptions());

    // What the CLI knows of the subscriptions the form names, or of the default one it is about to name.
    Map<String, CloudLocal.AzureSubscription> byId = new LinkedHashMap<>();
    subscriptions.forEach(s -> byId.putIfAbsent(s.id().toLowerCase(Locale.ROOT), s));
    List<CloudLocal.AzureSubscription> known = chosen.isEmpty()
        ? preferred == null ? List.of() : List.of(preferred)
        : chosen.stream().map(byId::get).filter(Objects::nonNull).toList();

    Set<String> tenants = new LinkedHashSet<>();
    known.stream().map(CloudLocal.AzureSubscription::tenantId).filter(Objects::nonNull)
        .forEach(t -> tenants.add(t.toLowerCase(Locale.ROOT)));
    List<Option> tenantOptions = local.azureTenantOptions();
    String tenant = tenants.size() == 1 ? tenants.iterator().next()
        : tenants.isEmpty() && tenantOptions.size() == 1 ? tenantOptions.getFirst().value() : null;
    out.value("/tenantId", CLI, tenant, tenantOptions);
    if (tenants.size() > 1) {
      out.note("The chosen subscriptions belong to " + tenants.size() + " tenants: a connection signs in to one, so "
          + "make one connection per tenant.");
    }

    Set<String> clouds = new LinkedHashSet<>();
    known.stream().map(CloudLocal.AzureSubscription::cloud).filter(CLOUDS::containsKey).forEach(clouds::add);
    if (clouds.size() == 1) {
      String id = clouds.iterator().next();
      out.value("/cloud", CLI, id, List.of(new Option(id, CLOUDS.get(id), null)));
    }
    return out.build();
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  static Suggestions service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, CloudCredentials cloud,
      HttpClient http) {
    Suggestions.Builder out = Suggestions.builder();
    if (cfg.config() == null || !cfg.config().isObject()) {
      return out.build();
    }
    AzureSpec spec;
    try {
      spec = AzureSpec.fromConfig(cfg.config(), "/credentials");
    } catch (IllegalArgumentException e) {
      return out.note(e.getMessage()).build();
    }
    // Subscriptions may still be empty or half typed (x-suggest): the ones the form names so far.
    List<String> chosen = guids(cfg.strings("/subscriptions"));
    Settings s = new Settings(spec, chosen, List.of(), List.of(), null);
    if (spec.source() == AzureSpec.Source.CLI) {
      return out.note("Workspaces and Application Insights components aren't listed for an Azure CLI sign-in: "
          + "GateRook runs az only for Test connection and agents' calls.").build();
    }
    Arm arm = new Arm(http, cloud, new CloudCredentials.Owner(cfg.id(), cfg.version(), cfg.name()), s, secrets, ctx,
        false);
    Call call = new Call(cfg, s, AzurePolicy.defaults(cfg.env()), arm, ctx);
    AzureCredentials.AccessToken token;
    try {
      // A device sign-in only refreshes a sign-in the person completed in Test connection; it never starts one.
      token = arm.token();
    } catch (ConnectorException e) {
      boolean signIn = spec.source() == AzureSpec.Source.DEVICE_CODE
          && String.valueOf(e.getMessage()).startsWith("Nobody is signed in");
      return out.note(signIn ? "Choose Test connection to sign in with a code: the form then lists the "
          + "subscriptions, workspaces and components the account can read." : e.getMessage()).build();
    }
    String tenant = token.tenantId();
    if (spec.tenantIsGuid() && tenant != null && !spec.tenantId().equals(tenant)) {
      return out.note("The sign-in belongs to tenant " + tenant + ", not " + spec.tenantId() + ": check the tenant "
          + "ID.").build();
    }
    String who = token.account() == null || token.account().isBlank() ? spec.identity() : token.account();

    JsonNode visible;
    try {
      share(call, chosen.isEmpty() ? null : chosen.getFirst(), spec);
      visible = arm.get(arm.url("/subscriptions", Arm.query(ConnectionTest.SUBSCRIPTIONS_API)),
          "Azure Resource Manager");
    } catch (ConnectorException e) {
      return out.note(e.getMessage()).build();
    }
    Map<String, String> names = new LinkedHashMap<>();
    List<Option> options = new ArrayList<>();
    for (JsonNode sub : visible.path("value")) {
      String id = sub.path("subscriptionId").asString("").toLowerCase(Locale.ROOT);
      // What Test connection accepts: enabled, and in the sign-in's tenant.
      if (!Settings.GUID.matcher(id).matches() || names.containsKey(id)
          || !"Enabled".equalsIgnoreCase(sub.path("state").asString("Enabled"))
          || tenant != null && !tenant.equalsIgnoreCase(sub.path("tenantId").asString(tenant))) {
        continue;
      }
      String name = sub.path("displayName").asString("").strip();
      names.put(id, name);
      options.add(new Option(id, name.isEmpty() ? null : name, null));
    }
    options.sort(Comparator.comparing(o -> (o.label() == null ? o.value() : o.label()).toLowerCase(Locale.ROOT)));
    if (options.isEmpty()) {
      out.note(who + " sees no enabled subscription" + (tenant == null ? "" : " in tenant " + tenant) + ": assign "
          + "it the Reader role on one.");
      return out.build();
    }
    // The only subscription the identity sees is the obvious choice while the form names none.
    String only = chosen.isEmpty() && options.size() == 1 ? options.getFirst().value() : null;
    out.field("/subscriptions", new Suggestions.Field(options, only, AZURE,
        visible.path("nextLink").asString("").isEmpty()));
    out.note("Signed in as " + who + ": " + count(options.size(), "subscription") + ". Choose the ones agents may "
        + "read.");
    List<String> missing = chosen.stream().filter(id -> !names.containsKey(id)).toList();
    if (!missing.isEmpty()) {
      out.note(who + " can't see subscription" + (missing.size() == 1 ? " " : "s ") + few(missing)
          + ": assign the Reader role on " + (missing.size() == 1 ? "it" : "them") + ", or choose one it can see.");
    }
    logSources(only != null ? List.of(only) : chosen, call, names, out);
    return out.build();
  }

  /**
   * One read from GateRook's share of Resource Manager: the subscription's, or before the form names one, the
   * tenant's.
   */
  private static void share(Call call, String subscription, AzureSpec spec) throws ConnectorException {
    if (subscription != null) {
      call.quota(subscription, "Resource Manager", Call.ARM_PER_MINUTE);
    } else if (!call.ctx().acquire("azure:tenant:" + spec.tenantId() + ":Resource Manager", Call.ARM_PER_MINUTE)) {
      throw ConnectorException.resourceError("GateRook's share of Resource Manager reads for tenant "
          + spec.tenantId() + " (" + Call.ARM_PER_MINUTE + " per minute) is used up. Try again in a minute.", null);
    }
  }

  /** The workspaces and components of the chosen subscriptions the identity can see, one list call per kind each. */
  private static void logSources(List<String> subscriptions, Call call, Map<String, String> names,
      Suggestions.Builder out) {
    List<String> listed = subscriptions.stream().filter(names::containsKey).toList();
    List<Option> workspaces = new ArrayList<>();
    List<Option> components = new ArrayList<>();
    boolean complete = true;
    List<String> refused = new ArrayList<>();
    String failed = null;
    Arm arm = call.arm();
    loop:
    for (String sub : listed) {
      String where = listed.size() > 1 ? names.get(sub).isEmpty() ? sub : names.get(sub) : null;
      for (boolean ws : new boolean[] {true, false}) {
        if (call.ctx().remaining().compareTo(LAST_LIST) <= 0) {
          complete = false;
          break loop;
        }
        String what = ws ? "Log Analytics workspaces" : "Application Insights components";
        Arm.Answer a;
        try {
          call.quota(sub, "Resource Manager", Call.ARM_PER_MINUTE);
          a = arm.send("GET", arm.url("/subscriptions/" + sub + (ws ? "/providers/Microsoft.OperationalInsights"
              + "/workspaces" : "/providers/Microsoft.Insights/components"), Arm.query(ws ? WORKSPACES_API
              : COMPONENTS_API)), null, what);
        } catch (ConnectorException e) {
          failed = e.getMessage();
          complete = false;
          break loop;
        }
        if (a.status() == 403) {
          refused.add(what + " in " + (names.get(sub).isEmpty() ? sub : names.get(sub)));
          continue;
        }
        if (!a.ok()) {
          // A provider the subscription never registered has nothing to offer.
          continue;
        }
        if (!a.body().path("nextLink").asString("").isEmpty()) {
          complete = false;
        }
        for (JsonNode r : a.body().path("value")) {
          String value = ws ? workspaceId(r) : componentId(r.path("id").asString(""));
          if (value != null) {
            (ws ? workspaces : components).add(new Option(value, r.path("name").asString(null), detail(r, where)));
          }
        }
      }
    }
    Comparator<Option> byName = Comparator.comparing(o -> (o.label() == null ? o.value() : o.label())
        .toLowerCase(Locale.ROOT));
    workspaces.sort(byName);
    components.sort(byName);
    if (!workspaces.isEmpty()) {
      out.field("/logAnalyticsWorkspaces", new Suggestions.Field(workspaces, null, AZURE, complete));
    }
    if (!components.isEmpty()) {
      out.field("/applicationInsights", new Suggestions.Field(components, null, AZURE, complete));
    }
    List<String> found = new ArrayList<>();
    if (!workspaces.isEmpty()) {
      found.add(count(workspaces.size(), "Log Analytics workspace"));
    }
    if (!components.isEmpty()) {
      found.add(count(components.size(), "Application Insights component"));
    }
    if (!found.isEmpty()) {
      out.note(String.join(" and ", found) + " in the chosen subscriptions: add the ones logs_search and logs_tail "
          + "may read.");
    }
    if (!refused.isEmpty()) {
      out.note("The identity may not list " + few(refused) + ": type the ones the log tools read.");
    }
    if (failed != null) {
      out.note(failed);
    }
  }

  /** A workspace's ID as the Log Analytics query API names it ({@code properties.customerId}), or {@code null}. */
  static String workspaceId(JsonNode workspace) {
    String id = workspace.path("properties").path("customerId").asString("").strip().toLowerCase(Locale.ROOT);
    return Settings.GUID.matcher(id).matches() ? id : null;
  }

  /**
   * A component's resource ID in the form's spelling ({@code /subscriptions/<id>/resourceGroups/<group>/providers/
   * Microsoft.Insights/components/<name>}), whatever case Resource Manager answered in; {@code null} for anything else.
   */
  static String componentId(String raw) {
    ResourceIds.Parsed p = ResourceIds.parse(raw);
    if (p == null || !p.isResource() || p.resourceGroup() == null || p.segments().size() != 8
        || !"microsoft.insights/components".equals(p.type())) {
      return null;
    }
    String id = "/subscriptions/" + p.subscription() + "/resourceGroups/" + p.resourceGroup()
        + "/providers/Microsoft.Insights/components/" + p.name();
    return id.length() <= 512 && COMPONENT.matcher(id).matches() ? id : null;
  }

  /** {@code stage-app · westeurope}, and the subscription's name when the form names several. */
  private static String detail(JsonNode resource, String subscription) {
    List<String> parts = new ArrayList<>();
    ResourceIds.Parsed p = ResourceIds.parse(resource.path("id").asString(""));
    if (p != null && p.resourceGroup() != null) {
      parts.add(p.resourceGroup());
    }
    String location = resource.path("location").asString("");
    if (!location.isBlank()) {
      parts.add(location);
    }
    if (subscription != null) {
      parts.add(subscription);
    }
    return parts.isEmpty() ? null : String.join(" · ", parts);
  }

  private static boolean cli(ConnectionConfig cfg) {
    String source = cfg.string("/credentials/source");
    return source == null || source.isBlank() || source.equals(AzureSpec.Source.CLI.id());
  }

  private static List<String> guids(List<String> values) {
    List<String> out = new ArrayList<>();
    for (String v : values) {
      String id = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
      if (Settings.GUID.matcher(id).matches() && !out.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  private static String count(int n, String what) {
    return n + " " + what + (n == 1 ? "" : "s");
  }

  /** The first three of a list, for a note that stays short. */
  private static String few(List<String> items) {
    return String.join(", ", items.stream().limit(3).toList()) + (items.size() > 3 ? " and " + (items.size() - 3)
        + " more" : "");
  }
}
