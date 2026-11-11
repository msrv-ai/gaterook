package ai.msrv.gaterook.connectors.azure;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call of {@code azure_storage_read} or {@code azure_storage_change}: an action on one storage account of the
 * connection's subscriptions, checked without I/O the same way in {@code classify}, the approval preview and
 * {@code execute}. The account is its Resource Manager ID, or its name in a subscription of the connection (the run
 * looks the ID up); requests go to the account's own Blob, Queue, Table or File host with an Entra ID token for
 * Storage — never with an account key.
 *
 * <p>Reads: {@code list_blobs}, {@code read_blob} (bounded text), {@code peek_messages} (Queue Storage's
 * {@code peekonly}, which dequeues nothing), {@code query_entities} (Tables), {@code list_files} and
 * {@code read_file} (Azure Files). Changes, each approved as the exact request: {@code upload_blob} (text) and
 * {@code delete_blob}.
 *
 * @param container the container, queue, table or share
 * @param item      the blob name, or the file or directory path in the share ({@code ""} for its root)
 */
record StorageRequest(String tool, Action action, Account account, String container, String item, String prefix,
    String delimiter, long offset, int maxBytes, int limit, String filter, List<String> select, String cursor,
    String content, String contentType, boolean overwrite, boolean deleteSnapshots) {

  static final String READ = "azure_storage_read";
  static final String CHANGE = "azure_storage_change";
  static final int DEFAULT_BYTES = 64 * 1024;
  static final int MAX_BYTES = 256 * 1024;
  static final int DEFAULT_MESSAGE_BYTES = 4 * 1024;
  static final int MAX_MESSAGE_BYTES = 64 * 1024;
  static final int MAX_MESSAGES = 32;
  static final int MAX_UPLOAD = 1 << 20;

  /** What a call does, the storage service it uses and the collection of its Resource Manager sub-resource. */
  enum Action {
    LIST_BLOBS("list_blobs", "blob", "blobServices/default/containers", false),
    READ_BLOB("read_blob", "blob", "blobServices/default/containers", false),
    PEEK_MESSAGES("peek_messages", "queue", "queueServices/default/queues", false),
    QUERY_ENTITIES("query_entities", "table", "tableServices/default/tables", false),
    LIST_FILES("list_files", "file", "fileServices/default/shares", false),
    READ_FILE("read_file", "file", "fileServices/default/shares", false),
    UPLOAD_BLOB("upload_blob", "blob", "blobServices/default/containers", true),
    DELETE_BLOB("delete_blob", "blob", "blobServices/default/containers", true);

    final String id;
    final String service;
    final String collection;
    final boolean change;

    Action(String id, String service, String collection, boolean change) {
      this.id = id;
      this.service = service;
      this.collection = collection;
      this.change = change;
    }

    /** The argument that names the container, queue, table or share. */
    String containerField() {
      return switch (service) {
        case "queue" -> "queue";
        case "table" -> "table";
        case "file" -> "share";
        default -> "container";
      };
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

  /**
   * A storage account of the connection.
   *
   * @param id   its Resource Manager ID, or {@code null} when only the name was given
   * @param name its name, lower case
   */
  record Account(String subscription, ResourceIds.Parsed id, String name) {

    /** {@code /subscriptions/…/storageAccounts/<name>} as given, or {@code null}. */
    String rawId() {
      return id == null ? null : "/" + String.join("/", id.segments());
    }
  }

  private static final Pattern ACCOUNT_NAME = Pattern.compile("^[a-z0-9]{3,24}$");
  private static final Pattern CONTAINER = Pattern.compile("^(\\$root|\\$logs|\\$web|[a-z0-9](?!.*--)[a-z0-9-]{1,61}"
      + "[a-z0-9])$");
  private static final Pattern QUEUE_OR_SHARE = Pattern.compile("^[a-z0-9](?!.*--)[a-z0-9-]{1,61}[a-z0-9]$");
  private static final Pattern TABLE = Pattern.compile("^[A-Za-z][A-Za-z0-9]{2,62}$");
  private static final Pattern PROPERTY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,254}$");
  private static final Pattern CONTENT_TYPE = Pattern.compile("^[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}"
      + "(\\s*;\\s*[A-Za-z0-9_-]{1,32}=[A-Za-z0-9_.-]{1,64}){0,3}$");
  static final int MAX_FILTER = 2_048;

  static StorageRequest of(String tool, JsonNode args, Settings s, AzurePolicy p) throws Refusal {
    boolean change = CHANGE.equals(tool);
    String id = text(args, "action");
    Action action = Action.of(id == null ? "" : id);
    if (action == null) {
      throw new Refusal("bad_input", "action of " + tool + " is " + (change ? "upload_blob or delete_blob."
          : "list_blobs, read_blob, peek_messages, query_entities, list_files or read_file."), null);
    }
    if (action.change && !change) {
      throw new Refusal("not_a_read", action.id + " changes Azure Storage.", "Call it with azure_storage_change: a "
          + "person approves the exact request.");
    }
    if (!action.change && change) {
      throw new Refusal("is_a_read", action.id + " only reads.", "Call it with azure_storage_read.");
    }
    Account account = account(args, s, p);
    if (!p.serviceAllowed("storage")) {
      throw new Refusal("service_not_allowed", "The service storage isn't allowed: " + p.servicesText() + ".", null);
    }
    String field = action.containerField();
    String container = text(args, field);
    Pattern rule = switch (action.service) {
      case "queue", "file" -> QUEUE_OR_SHARE;
      case "table" -> TABLE;
      default -> CONTAINER;
    };
    if (container == null || !rule.matcher(container).matches()) {
      throw new Refusal("bad_input", field + " is the " + (field.equals("share") ? "file share" : field) + "'s name ("
          + switch (field) {
            case "table" -> "3 to 63 letters and digits, starting with a letter";
            default -> "3 to 63 lower-case letters, digits and single dashes";
          } + ").", null);
    }
    if (account.id() != null) {
      ResourceIds.Parsed sub = ResourceIds.parse(account.rawId() + "/" + action.collection + "/" + container);
      if (sub == null || !p.resourceAllowed(sub)) {
        throw new Refusal("resource_not_allowed", "The " + field + " " + container + " of " + account.name()
            + " isn't allowed by the Cloud policy's resource masks on " + p.env().id() + ".", null);
      }
    }
    String item = "";
    if (action == Action.READ_BLOB || action == Action.UPLOAD_BLOB || action == Action.DELETE_BLOB) {
      item = path(args, "blob", false, "blob is the blob's full name in the container, such as reports/2026-q3.csv.");
    } else if (action == Action.READ_FILE) {
      item = path(args, "path", false, "path is the file's path in the share, such as logs/app.log.");
    } else if (action == Action.LIST_FILES) {
      item = path(args, "path", true, "path is a directory of the share, such as logs; leave it out for the root.");
    }
    if ((action == Action.READ_BLOB || action == Action.READ_FILE) && Texts.sensitiveName(item)) {
      throw new Refusal("object_not_allowed", item + " holds keys, credentials or Terraform state more often than "
          + "not, so " + tool + " doesn't read it.", "Its properties are in the list of its "
          + (action == Action.READ_BLOB ? "container (list_blobs)." : "directory (list_files)."));
    }
    String prefix = null;
    String delimiter = null;
    if (action == Action.LIST_BLOBS) {
      prefix = text(args, "prefix");
      if (prefix != null && (prefix.length() > 1024 || controls(prefix))) {
        throw new Refusal("bad_input", "prefix is the start of blob names, up to 1,024 characters.", null);
      }
      JsonNode d = args.get("delimiter");
      delimiter = d == null || d.isNull() ? "/" : d.asString("");
      if (delimiter.length() > 8 || controls(delimiter)) {
        throw new Refusal("bad_input", "delimiter groups names into prefixes, like / (the default); \"\" lists every "
            + "blob.", null);
      }
      if (delimiter.isEmpty()) {
        delimiter = null;
      }
    }
    long offset = 0;
    int maxBytes = 0;
    if (action == Action.READ_BLOB || action == Action.READ_FILE) {
      offset = args.path("offset").isNumber() ? args.path("offset").asLong(0) : 0;
      if (offset < 0) {
        throw new Refusal("bad_input", "offset is 0 or more.", null);
      }
      maxBytes = integer(args, "maxBytes", DEFAULT_BYTES, 1, MAX_BYTES);
    } else if (action == Action.PEEK_MESSAGES) {
      maxBytes = integer(args, "maxBytes", DEFAULT_MESSAGE_BYTES, 1, MAX_MESSAGE_BYTES);
    }
    int limit = switch (action) {
      case PEEK_MESSAGES -> integer(args, "count", 10, 1, MAX_MESSAGES);
      case LIST_BLOBS, LIST_FILES, QUERY_ENTITIES -> integer(args, "limit", Math.min(50, p.maxItems()), 1,
          p.maxItems());
      default -> 0;
    };
    String filter = null;
    List<String> select = List.of();
    if (action == Action.QUERY_ENTITIES) {
      filter = text(args, "filter");
      if (filter != null && (filter.length() > MAX_FILTER || controls(filter))) {
        throw new Refusal("bad_input", "filter is an OData filter of up to " + MAX_FILTER + " characters, such as "
            + "PartitionKey eq 'orders' and Timestamp ge datetime'2026-11-01T00:00:00Z'.", null);
      }
      List<String> names = new ArrayList<>();
      for (JsonNode n : args.path("select")) {
        String name = n.asString("");
        if (!PROPERTY.matcher(name).matches() || names.size() >= 50) {
          throw new Refusal("bad_input", "select names up to 50 entity properties, such as [\"PartitionKey\", "
              + "\"RowKey\", \"Status\"].", null);
        }
        names.add(name);
      }
      select = List.copyOf(names);
    }
    String cursor = action.change ? null : text(args, "cursor");
    String content = null;
    String contentType = null;
    boolean overwrite = false;
    if (action == Action.UPLOAD_BLOB) {
      JsonNode c = args.get("content");
      if (c == null || !c.isString()) {
        throw new Refusal("bad_input", "content is the blob's text.", null);
      }
      content = c.asString();
      if (content.getBytes(StandardCharsets.UTF_8).length > MAX_UPLOAD) {
        throw new Refusal("request_too_large", "content is larger than 1 MB; upload_blob writes text up to 1 MB.",
            null);
      }
      contentType = text(args, "contentType");
      if (contentType == null) {
        contentType = "text/plain; charset=utf-8";
      } else if (!CONTENT_TYPE.matcher(contentType).matches()) {
        throw new Refusal("bad_input", "contentType is a media type, such as application/json.", null);
      }
      overwrite = args.path("overwrite").asBoolean(false);
    }
    boolean deleteSnapshots = action == Action.DELETE_BLOB && args.path("deleteSnapshots").asBoolean(false);
    return new StorageRequest(change ? CHANGE : READ, action, account, container, item, prefix, delimiter, offset,
        maxBytes, limit, filter, select, cursor, content, contentType, overwrite, deleteSnapshots);
  }

  private static Account account(JsonNode args, Settings s, AzurePolicy p) throws Refusal {
    String raw = text(args, "account");
    if (raw == null) {
      throw new Refusal("bad_input", "account is the storage account's resource ID (/subscriptions/…/providers/"
          + "Microsoft.Storage/storageAccounts/<name>) or its name.", null);
    }
    if (raw.startsWith("/")) {
      ResourceIds.Parsed id = ResourceIds.parse(raw);
      if (id == null || !"microsoft.storage/storageaccounts".equals(id.type()) || id.segments().size() != 8
          || id.resourceGroup() == null) {
        throw new Refusal("azure_path", "\"" + Settings.abbreviate(raw) + "\" isn't a storage account's resource ID "
            + "(/subscriptions/<id>/resourceGroups/<group>/providers/Microsoft.Storage/storageAccounts/<name>).", null);
      }
      if (!s.hasSubscription(id.subscription())) {
        throw new Refusal("subscription", "Subscription " + id.subscription() + " isn't one of this connection's "
            + "subscriptions.", "Use one of: " + String.join(", ", s.subscriptions()) + ".");
      }
      String name = id.name().toLowerCase(Locale.ROOT);
      if (!ACCOUNT_NAME.matcher(name).matches()) {
        throw new Refusal("bad_input", "A storage account's name is 3 to 24 lower-case letters and digits.", null);
      }
      // Masks are checked on the container, queue, table or share: its ID starts with the account's, so a mask of
      // the account (or its resource group) covers it, and a mask of one container covers that container only.
      return new Account(id.subscription(), id, name);
    }
    String name = raw.toLowerCase(Locale.ROOT);
    if (!ACCOUNT_NAME.matcher(name).matches()) {
      throw new Refusal("bad_input", "account is the storage account's resource ID or its name (3 to 24 lower-case "
          + "letters and digits).", null);
    }
    String subscription = text(args, "subscription");
    if (subscription == null && s.subscriptions().size() == 1) {
      subscription = s.subscriptions().getFirst();
    }
    if (subscription == null || !s.hasSubscription(subscription)) {
      throw new Refusal("subscription", subscription == null ? "Name the account's subscription, or pass the "
          + "account's resource ID." : "Subscription " + subscription + " isn't one of this connection's "
          + "subscriptions.", "Use one of: " + String.join(", ", s.subscriptions()) + ".");
    }
    if (!p.resources().isEmpty()) {
      throw new Refusal("resource_not_allowed", "The Cloud policy on " + p.env().id() + " allows some resources "
          + "only: pass the storage account's resource ID, so GateRook can check it.", "azure_describe finds it: "
          + "resources | where type =~ 'microsoft.storage/storageaccounts' | project id, name");
    }
    return new Account(subscription.toLowerCase(Locale.ROOT), null, name);
  }

  /** A blob name or a file path: segments without {@code .} or {@code ..}, no control characters or backslash. */
  private static String path(JsonNode args, String field, boolean optional, String help) throws Refusal {
    JsonNode n = args.get(field);
    String v = n == null || n.isNull() ? null : n.asString("");
    if (v == null || v.isEmpty()) {
      if (optional) {
        return "";
      }
      throw new Refusal("bad_input", help, null);
    }
    if (optional && v.endsWith("/")) {
      v = v.substring(0, v.length() - 1);
    }
    boolean bad = v.length() > 1024 || v.startsWith("/") || v.endsWith("/") || v.endsWith(".") || controls(v)
        || v.indexOf('\\') >= 0 || Arrays.stream(v.split("/", -1)).anyMatch(seg -> seg.isEmpty() || seg.equals(".")
        || seg.equals(".."));
    if (bad) {
      throw new Refusal("bad_input", help + " Names have no empty, . or .. parts and don't end in / or a dot.", null);
    }
    return v;
  }

  boolean read() {
    return !action.change;
  }

  /** The Resource Manager ID of the container, queue, table or share once the account's ID is known. */
  String subResource(String accountId) {
    return accountId + "/" + action.collection + "/" + container;
  }

  /** The path on the service's host, each segment percent-encoded: {@code /<container>/<blob>}. */
  String encodedPath() {
    StringBuilder out = new StringBuilder("/").append(ResourceIds.encode(container));
    if (action == Action.QUERY_ENTITIES) {
      return out.append("()").toString();
    }
    if (!item.isEmpty()) {
      for (String seg : item.split("/")) {
        out.append('/').append(ResourceIds.encode(seg));
      }
    }
    return out.toString();
  }

  /** {@code <account>/<container>/<item>}, for texts. */
  String where() {
    return account.name() + "/" + container + (item.isEmpty() ? "" : "/" + item);
  }

  /** The parts that make two reads the same listing, for cursors. */
  String hash() {
    return Cursors.hash(action.id, account.rawId() == null ? account.subscription() + "/" + account.name()
        : account.rawId().toLowerCase(Locale.ROOT), container, item, prefix, delimiter, filter, String.join(",",
        select));
  }

  long bodyBytes() {
    return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
  }

  /** The canonical form an approval stores and executes. */
  ObjectNode payload() {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("tool", tool);
    ObjectNode a = p.putObject("arguments");
    a.put("action", action.id);
    a.put("account", account.rawId() != null ? account.rawId() : account.name());
    if (account.rawId() == null) {
      a.put("subscription", account.subscription());
    }
    a.put(action.containerField(), container);
    switch (action) {
      case READ_BLOB, UPLOAD_BLOB, DELETE_BLOB -> a.put("blob", item);
      case READ_FILE, LIST_FILES -> {
        if (!item.isEmpty()) {
          a.put("path", item);
        }
      }
      default -> {
        // The container alone names what is listed, peeked or queried.
      }
    }
    if (prefix != null) {
      a.put("prefix", prefix);
    }
    if (action == Action.LIST_BLOBS) {
      a.put("delimiter", delimiter == null ? "" : delimiter);
    }
    if (action == Action.READ_BLOB || action == Action.READ_FILE) {
      a.put("offset", offset);
    }
    if (maxBytes > 0) {
      a.put("maxBytes", maxBytes);
    }
    if (limit > 0) {
      a.put(action == Action.PEEK_MESSAGES ? "count" : "limit", limit);
    }
    if (filter != null) {
      a.put("filter", filter);
    }
    if (!select.isEmpty()) {
      ArrayNode sel = a.putArray("select");
      select.forEach(sel::add);
    }
    if (cursor != null) {
      a.put("cursor", cursor);
    }
    if (content != null) {
      a.put("content", content);
      a.put("contentType", contentType);
      a.put("overwrite", overwrite);
    }
    if (action == Action.DELETE_BLOB) {
      a.put("deleteSnapshots", deleteSnapshots);
    }
    return p;
  }

  private static boolean controls(String s) {
    return s.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
  }

  private static int integer(JsonNode args, String field, int dflt, int min, int max) throws Refusal {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return Math.min(dflt, max);
    }
    if (!n.isIntegralNumber() || n.asLong() < min || n.asLong() > max) {
      throw new Refusal("bad_input", field + " is " + min + " to " + max + ".", null);
    }
    return n.asInt();
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || !n.isValueNode() || n.asString().isBlank() ? null : n.asString().strip();
  }
}
