package ai.msrv.gaterook.connectors.azure;

import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call of {@code azure_keyvault_read}: the names, versions, state and expiry of a vault's secrets, keys or
 * certificates, checked without I/O. Only Key Vault's list operations are ever called — {@code /secrets},
 * {@code /secrets/<name>/versions}, {@code /keys}, {@code /keys/<name>/versions}, {@code /certificates},
 * {@code /certificates/<name>/versions} —, which return identifiers and attributes, never a value. Key Vault is a
 * sensitive service: the Cloud policy must name it under Allowed services.
 *
 * @param name the secret, key or certificate whose versions are listed, or {@code null}
 */
record KeyVaultRequest(Vault vault, Action action, String name, int limit, String cursor) {

  static final String TOOL = "azure_keyvault_read";
  /** Key Vault returns at most 25 items a page. */
  static final int MAX_PAGE = 25;

  enum Action {
    SECRETS("secrets", "secrets", false), SECRET_VERSIONS("secret_versions", "secrets", true),
    KEYS("keys", "keys", false), KEY_VERSIONS("key_versions", "keys", true),
    CERTIFICATES("certificates", "certificates", false),
    CERTIFICATE_VERSIONS("certificate_versions", "certificates", true);

    final String id;
    final String collection;
    final boolean versions;

    Action(String id, String collection, boolean versions) {
      this.id = id;
      this.collection = collection;
      this.versions = versions;
    }

    static Action of(String id) {
      for (Action a : values()) {
        if (a.id.equals(id)) {
          return a;
        }
      }
      return null;
    }
  }

  /** A vault of the connection: its Resource Manager ID, or its name and subscription. */
  record Vault(String subscription, ResourceIds.Parsed id, String name) {
    String rawId() {
      return id == null ? null : "/" + String.join("/", id.segments());
    }
  }

  private static final Pattern VAULT = Pattern.compile("^[a-z][a-z0-9-]{1,22}[a-z0-9]$");
  private static final Pattern ITEM = Pattern.compile("^[0-9A-Za-z-]{1,127}$");

  static KeyVaultRequest of(JsonNode args, Settings s, AzurePolicy p) throws Refusal {
    String a = text(args, "action");
    Action action = Action.of(a == null ? "secrets" : a);
    if (action == null) {
      throw new Refusal("bad_input", "action is secrets, secret_versions, keys, key_versions, certificates or "
          + "certificate_versions.", null);
    }
    if (!p.serviceAllowed("keyvault")) {
      throw new Refusal(p.servicesRestricted() ? "service_not_allowed" : "sensitive_service", p.servicesRestricted()
          ? "The service keyvault isn't allowed: " + p.servicesText() + "." : "Key Vault is a sensitive service: the "
          + "Cloud policy of " + p.env().id() + " shows it only when Allowed services names keyvault.", "Even then "
          + "agents see names, versions and dates, never values.");
    }
    Vault vault = vault(args, s, p);
    String name = text(args, "name");
    if (action.versions && (name == null || !ITEM.matcher(name).matches())) {
      throw new Refusal("bad_input", "name is the " + action.collection.substring(0, action.collection.length() - 1)
          + "'s name (letters, digits and dashes).", null);
    }
    if (!action.versions) {
      name = null;
    }
    JsonNode l = args.get("limit");
    int limit = MAX_PAGE;
    if (l != null && !l.isNull()) {
      if (!l.isIntegralNumber() || l.asInt() < 1 || l.asInt() > MAX_PAGE) {
        throw new Refusal("bad_input", "limit is 1 to " + MAX_PAGE + ".", null);
      }
      limit = l.asInt();
    }
    return new KeyVaultRequest(vault, action, name, Math.min(limit, p.maxItems()), text(args, "cursor"));
  }

  private static Vault vault(JsonNode args, Settings s, AzurePolicy p) throws Refusal {
    String raw = text(args, "vault");
    if (raw == null) {
      throw new Refusal("bad_input", "vault is the key vault's resource ID (/subscriptions/…/providers/"
          + "Microsoft.KeyVault/vaults/<name>) or its name.", null);
    }
    if (raw.startsWith("/")) {
      ResourceIds.Parsed id = ResourceIds.parse(raw);
      if (id == null || !"microsoft.keyvault/vaults".equals(id.type()) || id.segments().size() != 8
          || id.resourceGroup() == null) {
        throw new Refusal("azure_path", "\"" + Settings.abbreviate(raw) + "\" isn't a key vault's resource ID.", null);
      }
      if (!s.hasSubscription(id.subscription())) {
        throw new Refusal("subscription", "Subscription " + id.subscription() + " isn't one of this connection's "
            + "subscriptions.", "Use one of: " + String.join(", ", s.subscriptions()) + ".");
      }
      String name = id.name().toLowerCase(Locale.ROOT);
      if (!VAULT.matcher(name).matches()) {
        throw new Refusal("bad_input", "A vault's name is 3 to 24 letters, digits and dashes.", null);
      }
      if (!p.resourceAllowed(id)) {
        throw new Refusal("resource_not_allowed", "The key vault " + name + " isn't allowed by the Cloud policy's "
            + "resource masks on " + p.env().id() + ".", null);
      }
      return new Vault(id.subscription(), id, name);
    }
    String name = raw.toLowerCase(Locale.ROOT);
    if (!VAULT.matcher(name).matches()) {
      throw new Refusal("bad_input", "vault is the key vault's resource ID or its name (3 to 24 letters, digits and "
          + "dashes).", null);
    }
    String subscription = text(args, "subscription");
    if (subscription == null && s.subscriptions().size() == 1) {
      subscription = s.subscriptions().getFirst();
    }
    if (subscription == null || !s.hasSubscription(subscription)) {
      throw new Refusal("subscription", subscription == null ? "Name the vault's subscription, or pass its "
          + "resource ID." : "Subscription " + subscription + " isn't one of this connection's subscriptions.",
          "Use one of: " + String.join(", ", s.subscriptions()) + ".");
    }
    if (!p.resources().isEmpty()) {
      throw new Refusal("resource_not_allowed", "The Cloud policy on " + p.env().id() + " allows some resources "
          + "only: pass the vault's resource ID, so GateRook can check it.", null);
    }
    return new Vault(subscription.toLowerCase(Locale.ROOT), null, name);
  }

  /** {@code /secrets} or {@code /secrets/<name>/versions}, encoded. */
  String path() {
    return "/" + action.collection + (name == null ? "" : "/" + ResourceIds.encode(name) + "/versions");
  }

  String hash() {
    return Cursors.hash(action.id, vault.rawId() == null ? vault.subscription() + "/" + vault.name()
        : vault.rawId().toLowerCase(Locale.ROOT), name);
  }

  ObjectNode payload() {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("tool", TOOL);
    ObjectNode a = p.putObject("arguments");
    a.put("vault", vault.rawId() != null ? vault.rawId() : vault.name());
    if (vault.rawId() == null) {
      a.put("subscription", vault.subscription());
    }
    a.put("action", action.id);
    if (name != null) {
      a.put("name", name);
    }
    a.put("limit", limit);
    if (cursor != null) {
      a.put("cursor", cursor);
    }
    return p;
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || !n.isValueNode() || n.asString().isBlank() ? null : n.asString().strip();
  }
}
