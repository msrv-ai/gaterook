package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_kv}: the account's KV namespaces, the keys of one (with their expiration and metadata), and the
 * value of one key — text, up to {@link #MAX_VALUE} bytes. A JSON value is masked like any answer ({@link Redactor});
 * a key whose name says it holds a credential is refused before any request, as {@code gcp_storage_read} refuses key
 * files.
 */
final class KvRead {

  static final Set<String> ARGS = Set.of("connection", "view", "namespace", "prefix", "limit", "cursor", "key", "page");
  static final int MAX_VALUE = 64 * 1024;
  /** Key names that announce a credential: never read. */
  static final Pattern CREDENTIAL_KEY = Pattern.compile("(?i).*(secret|token|password|passwd|credential"
      + "|private[_-]?key|api[_-]?key|access[_-]?key|session[_-]?key|signing[_-]?key|\\.pem|\\.p12|\\.pfx|\\.key|\\.env"
      + "|id_rsa|id_ed25519|kubeconfig|tfstate).*");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String view;
  private final String namespace;
  private final String prefix;
  private final int limit;
  private final String cursor;
  private final String key;
  private final Integer page;

  private KvRead(String view, String namespace, String prefix, int limit, String cursor, String key, Integer page) {
    this.view = view;
    this.namespace = namespace;
    this.prefix = prefix;
    this.limit = limit;
    this.cursor = cursor;
    this.key = key;
    this.page = page;
  }

  static KvRead parse(ObjectNode args) throws Refusal {
    Args.only(args, CloudflareTools.KV, ARGS);
    String view = Args.choice(args, "view", Set.of("namespaces", "keys", "value"), "namespaces");
    String namespace = Args.string(args, "namespace", Args.HEX32, "a KV namespace ID (32 characters, 0-9 and a-f)");
    String key = args.hasNonNull("key") ? Args.string(args, "key", 512) : null;
    String cursor = Args.string(args, "cursor", 1_024);
    switch (view) {
      case "namespaces" -> Args.notFor(args, view, "namespace", "prefix", "limit", "cursor", "key");
      case "keys" -> {
        if (namespace == null) {
          throw Refusal.badInput("View keys needs namespace.", "Pass a namespace ID from view namespaces.");
        }
        Args.notFor(args, view, "key", "page");
        if (cursor != null && !cursor.matches("[A-Za-z0-9_=+/-]{1,1024}")) {
          throw Refusal.badInput("cursor isn't one this tool gave.", null);
        }
      }
      default -> {
        if (namespace == null || key == null) {
          throw Refusal.badInput("View value needs namespace and key.", "List the keys with view keys first.");
        }
        Args.notFor(args, view, "prefix", "limit", "cursor", "page");
        if (key.equals(".") || key.equals("..") || key.getBytes(StandardCharsets.UTF_8).length > 512) {
          throw Refusal.badInput("\"" + Args.abbreviate(key) + "\" isn't a key this tool reads.", null);
        }
        if (CREDENTIAL_KEY.matcher(key).matches()) {
          throw new Refusal("kv_credential", "The key " + Args.abbreviate(key) + " is named like a credential, so "
              + "GateRook doesn't read its value.", "Ask the person to read it in the Cloudflare dashboard if it is "
              + "needed.");
        }
      }
    }
    Integer limit = Args.integer(args, "limit", 10, 1_000);
    return new KvRead(view, namespace, Args.string(args, "prefix", 512), limit == null ? 100 : limit, cursor, key,
        Args.integer(args, "page", 1, 1_000));
  }

  ToolResult run(Call c) throws ConnectorException {
    String a = c.settings().accountId();
    CloudflareApi api = c.api();
    ObjectNode s = Answers.envelope(c, view);
    List<String> notes = new ArrayList<>();
    switch (view) {
      case "namespaces" -> {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("page", page == null ? null : String.valueOf(page));
        q.put("per_page", "100");
        CloudflareApi.Answer r = api.get(c, api.uri(List.of("accounts", a, "storage", "kv", "namespaces"), q),
            "Workers KV");
        ArrayNode items = Answers.F.arrayNode();
        for (JsonNode n : r.result()) {
          items.add(Answers.pick(n, "id", "title", "supports_url_encoding"));
        }
        s.set("items", items);
        Answers.paging(s, r.info());
        return Answers.answer(c, "KV namespaces · " + c.cfg().name(), s, notes);
      }
      case "keys" -> {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("prefix", prefix);
        q.put("limit", String.valueOf(limit));
        q.put("cursor", cursor);
        CloudflareApi.Answer r = api.get(c, api.uri(List.of("accounts", a, "storage", "kv", "namespaces", namespace,
            "keys"), q), "Workers KV");
        ArrayNode items = Answers.F.arrayNode();
        for (JsonNode k : r.result()) {
          items.add(Answers.pick(k, "name", "expiration", "metadata"));
        }
        s.set("items", items);
        String next = r.info().path("cursor").asString("");
        if (!next.isEmpty()) {
          s.put("nextCursor", next);
          notes.add("More keys: call again with cursor set to nextCursor.");
        }
        return Answers.answer(c, "KV keys · " + namespace + " · " + c.cfg().name(), s, notes);
      }
      default -> {
        CloudflareApi.Raw raw = api.raw(c, api.uri("accounts", a, "storage", "kv", "namespaces", namespace, "values",
            key), MAX_VALUE, "Workers KV");
        ObjectNode item = Answers.F.objectNode();
        item.put("namespace", namespace);
        item.put("key", key);
        item.put("bytes", raw.bytes().length);
        String text = utf8(raw.bytes(), raw.truncated());
        if (text == null) {
          item.put("binary", true);
          notes.add("The value isn't UTF-8 text, so only its size is shown.");
        } else {
          JsonNode json = null;
          String t = text.strip();
          if (!raw.truncated() && (t.startsWith("{") || t.startsWith("["))) {
            try {
              json = JSON.readTree(t);
            } catch (JacksonException e) {
              json = null;
            }
          }
          if (json != null && json.isContainer()) {
            item.set("json", json);
          } else {
            item.put("text", text);
          }
        }
        if (raw.truncated()) {
          item.put("cut", true);
          notes.add("Only the first " + (MAX_VALUE / 1024) + " KB of the value are shown.");
        }
        s.set("item", item);
        return Answers.answer(c, "KV value · " + Args.abbreviate(key) + " · " + c.cfg().name(), s, notes);
      }
    }
  }

  /** The bytes as UTF-8 text, or {@code null} when they aren't (a cut value may end inside a character). */
  private static String utf8(byte[] bytes, boolean cut) {
    for (int trim = 0; trim < (cut ? 4 : 1) && trim <= bytes.length; trim++) {
      try {
        String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, bytes.length - trim)).toString();
        return s.indexOf('\u0000') >= 0 ? null : s;
      } catch (CharacterCodingException e) {
        // Try again without a last character that the cut may have split.
      }
    }
    return null;
  }
}
