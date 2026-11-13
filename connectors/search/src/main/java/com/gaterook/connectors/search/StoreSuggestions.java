package com.gaterook.connectors.search;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.support.LocalPorts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the forms of the vector databases and search engines
 * ({@link com.gaterook.connector.api.Connector#suggest}). On this computer: a server of the type answering on its
 * usual loopback port, offered as its address ({@code http://localhost:6333}) and never filled in, and what a backend
 * reads itself (the Algolia CLI's application IDs, the Azure CLI's tenants). Signed in as Test connection signs in:
 * the collections or indexes the credentials see, offered for the connection's list, and what a backend lists besides
 * (Milvus's databases, Chroma's tenant and databases). Reads only.
 */
final class StoreSuggestions {

  /** How many names of the connection's list a note names that the server doesn't have. */
  private static final int MAX_MISSING = 3;

  private StoreSuggestions() {}

  static Suggestions suggest(StoreBackend backend, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx,
      Suggestions.Scope scope) {
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.LOCAL) {
      backend.local(cfg, out);
      return out.build();
    }
    try {
      backend.endpoint(cfg);
    } catch (ConnectorException e) {
      return out.note(e.getMessage()).build();
    }
    try {
      backend.service(new Call(cfg, secrets, ctx), out);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
    }
    return out.build();
  }

  /**
   * {@code http://localhost:<port>} for the Server URL when something answers on that port of the loopback address:
   * a TCP connection, nothing sent. An option only: a person setting up a remote server picks it only on purpose.
   */
  static void localUrl(Suggestions.Builder out, String what, int port) {
    if (LocalPorts.listening(port)) {
      out.options("/url", "this computer", List.of(new Option("http://localhost:" + port, null,
          what + "'s usual port " + port + " answers here")));
    }
  }

  /**
   * The names the credentials see, offered for the connection's list ({@link StoreBackend#listField}) with a short
   * fact each, and a note: how many there are, and the names the list holds without {@code *} that don't exist. Names
   * that only stood in for a listing the credentials may not make ({@code listedBy}) are never offered.
   */
  static void offerList(StoreBackend backend, ConnectionConfig cfg, List<ObjectNode> found, Suggestions.Builder out) {
    if (!found.isEmpty() && found.stream().allMatch(o -> o.has("listedBy"))) {
      out.note(backend.cannotList(cfg));
      return;
    }
    List<Option> options = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (ObjectNode o : found) {
      String name = o.path("name").asString("");
      if (!name.isEmpty() && names.add(name)) {
        options.add(new Option(name, null, detail(o)));
      }
    }
    String field = "/" + backend.listField();
    out.options(field, backend.name(), options);
    String what = names.isEmpty() ? "No " + backend.listNouns() + " yet."
        : names.size() == 1 ? "1 " + backend.listNoun() + ": " + options.getFirst().value() + "."
        : names.size() + " " + backend.listNouns() + ": choose the ones agents may use.";
    out.note("Signed in to " + backend.signedIn(cfg) + ". " + what);
    List<String> missing = new ArrayList<>();
    for (String listed : cfg.strings(field)) {
      if (!listed.contains("*") && !names.contains(listed) && missing.size() < MAX_MISSING) {
        missing.add(listed);
      }
    }
    if (!missing.isEmpty()) {
      out.note(backend.name() + " has no " + (missing.size() == 1 ? backend.listNoun() + " named "
          : backend.listNouns() + " named ") + String.join(", ", missing) + ": check the "
          + (missing.size() == 1 ? "name." : "names."));
    }
  }

  /** One short fact of a listed collection or index: its vectors, its size, what it is. */
  static String detail(JsonNode o) {
    List<String> parts = new ArrayList<>();
    if (o.path("dimension").isNumber()) {
      parts.add(o.get("dimension").asInt() + " dimensions");
    }
    String metric = o.path("metric").asString("");
    if (!metric.isBlank()) {
      parts.add(metric.toLowerCase(Locale.ROOT));
    }
    if (o.path("documents").isNumber()) {
      long n = o.get("documents").asLong();
      parts.add(String.format(Locale.US, "%,d", n) + (n == 1 ? " document" : " documents"));
    }
    if (o.path("replicaOf").isString()) {
      parts.add("replica of " + o.get("replicaOf").asString());
    }
    if (o.has("ready") && !o.path("ready").asBoolean(true)) {
      parts.add("not ready");
    }
    if (o.path("multiTenant").asBoolean(false)) {
      parts.add("multi-tenant");
    }
    return parts.isEmpty() ? null : String.join(", ", parts);
  }

  /** {@code cfg} with {@code value} at the top-level field {@code key}: a backend lists where it would sign in. */
  static ConnectionConfig with(ConnectionConfig cfg, String key, String value) {
    ObjectNode config = cfg.config() instanceof ObjectNode o ? o.deepCopy() : StoreBackend.F.objectNode();
    config.put(key, value);
    return new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(), config,
        cfg.secretFields(), cfg.metadata());
  }
}
