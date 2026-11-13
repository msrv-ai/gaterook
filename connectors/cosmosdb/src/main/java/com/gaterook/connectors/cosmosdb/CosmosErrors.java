package com.gaterook.connectors.cosmosdb;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Cosmos DB's refusals as what the agent can do about them. Cosmos DB answers {@code {"code", "message"}}, where the
 * message may hold the query's syntax errors as JSON and ends with diagnostics ({@code ActivityId}, request times)
 * that are left out; the emulator also answers {@code {"errors": […]}} or {@code {"Errors": […]}}. Every text of the
 * server goes through {@link OutputSanitizer}. A change that Cosmos DB refused changed nothing and says so; one whose
 * answer is a server error may have run, and is reported as unknown. Nothing is retried, a throttled request (429)
 * included.
 */
final class CosmosErrors {

  /** Substatus of a cross-partition query the gateway can't run without an SDK's query pipeline. */
  static final int CROSS_PARTITION_NOT_SERVABLE = 1004;

  private CosmosErrors() {}

  static ConnectorException of(CosmosHttp.Answer a, CosmosHttp.Request r, CosmosHttp.Call c) {
    int status = a.status();
    int sub = a.substatus();
    String said = message(a.body());
    String lower = said.toLowerCase(Locale.ROOT);
    boolean change = r.change();
    String nothing = change ? " Nothing changed." : "";
    String name = c.cfg().name();
    boolean key = c.settings().auth() == CosmosSettings.Auth.KEY;
    return switch (status) {
      case 400 -> {
        if (sub == CROSS_PARTITION_NOT_SERVABLE || lower.contains("directly served by the gateway")) {
          yield ConnectorException.badInput(gatewayRefused(a.body()));
        }
        if (lower.contains("consistency") && (lower.contains("stronger") || lower.contains("weaker")
            || lower.contains("not supported"))) {
          yield ConnectorException.badInput("Cosmos DB refused the connection's read consistency ("
              + c.settings().readConsistency() + "): a read can't be stronger than the account's default. Choose a "
              + "weaker one for " + name + ", or the account's default.");
        }
        if (lower.contains("partitionkey extracted from document doesn't match") || sub == 1001) {
          yield ConnectorException.badInput("partitionKey doesn't match the item's partition key value." + nothing
              + " Give the value the item holds at the container's partition key path.");
        }
        if (lower.contains("partition key") && (lower.contains("required") || lower.contains("missing"))) {
          yield ConnectorException.badInput("Cosmos DB needs the item's partition key value: " + said + "." + nothing);
        }
        yield ConnectorException.badInput("Cosmos DB refused " + r.what() + " (400): " + said + "." + nothing);
      }
      case 401 -> ConnectorException.unavailable("Cosmos DB refused the " + (key ? "account key" : "Microsoft Entra "
          + "ID token") + " of " + name + " (401): " + said + "." + nothing + " " + (key
          ? "Check the key on the account's Keys page (regenerating a key replaces it) and this computer's clock."
          : "Check the service principal and this computer's clock."), null);
      case 403 -> forbidden(said, lower, sub, change, key, name, r);
      case 404 -> ConnectorException.badInput(notFound(said, r) + nothing);
      case 408 -> change
          ? ConnectorException.outcomeUnknown("Cosmos DB timed out during the change (408), so whether it ran is not "
              + "known. Read the item before trying again.", null)
          : ConnectorException.timeout("Cosmos DB timed out on " + r.what() + " (408): narrow the query.");
      case 409 -> ConnectorException.resourceError("An item with this id already exists in this partition, or the "
          + "item breaks a unique key of the container." + nothing + " " + (said.isEmpty() ? "" : said + ". ")
          + "Use replace or upsert to change an existing item.", null);
      case 412 -> ConnectorException.resourceError("The item changed since its ETag was read, or the patch's "
          + "condition doesn't hold." + nothing + " Read the item again and propose the change against it.", null);
      case 413 -> ConnectorException.badInput("The item is larger than Cosmos DB takes (2 MB of JSON)." + nothing);
      case 429 -> {
        String after = a.header("x-ms-retry-after-ms");
        yield ConnectorException.resourceError("Cosmos DB throttled the request (429: the container's request units "
            + "are used up)." + nothing + " Try again " + (after == null ? "in a moment" : "after " + retryAfter(after))
            + ", or read fewer items." + (change ? " GateRook never retries a change by itself." : ""), null);
      }
      case 449 -> ConnectorException.resourceError("Cosmos DB asked to try the change again (449)." + nothing
          + " Propose it again.", null);
      default -> status >= 500
          ? (change ? ConnectorException.outcomeUnknown("Cosmos DB failed with " + status + " during the change, so "
              + "whether it ran is not known. Read the item before trying again.", null)
              : ConnectorException.unavailable("Cosmos DB failed with " + status + (said.isEmpty() ? "" : ": " + said)
                  + ".", null))
          : ConnectorException.resourceError("Cosmos DB refused " + r.what() + " (" + status + ")" + (said.isEmpty()
              ? "" : ": " + said) + "." + nothing, null);
    };
  }

  /** Whether a 403 is the refusal of Cosmos DB's data-plane roles (substatus 5300 to 5399). */
  static boolean roleRefusal(CosmosHttp.Answer a) {
    int sub = a.substatus();
    return a.status() == 403 && (sub >= 5300 && sub <= 5399 || message(a.body()).toLowerCase(Locale.ROOT)
        .contains("rbac"));
  }

  private static ConnectorException forbidden(String said, String lower, int sub, boolean change, boolean key,
      String name, CosmosHttp.Request r) {
    String nothing = change ? " Nothing changed." : "";
    if (sub >= 5300 && sub <= 5399 || lower.contains("rbac")) {
      return ConnectorException.resourceError("The service principal of " + name + " may not do this (403, Cosmos DB's"
          + " data-plane roles): " + said + "." + nothing + " Assign it " + (change ? "Cosmos DB Built-in Data "
          + "Contributor" : "Cosmos DB Built-in Data Reader") + " on the database or container, with az cosmosdb sql "
          + "role assignment create.", null);
    }
    if (lower.contains("firewall") || lower.contains("public network access") || lower.contains("ip address")) {
      return ConnectorException.unavailable("The account's firewall blocks this computer (403): " + said + "."
          + nothing + " Allow its address in the account's networking settings, or connect through a private "
          + "endpoint.", null);
    }
    if (lower.contains("local authorization is disabled") || lower.contains("disablelocalauth")) {
      return ConnectorException.unavailable("The account accepts no keys (local authorization is disabled): use "
          + "Microsoft Entra ID for " + name + "." + nothing, null);
    }
    if (lower.contains("expired") || lower.contains("not valid at the current time") || lower.contains("x-ms-date")) {
      return ConnectorException.unavailable("Cosmos DB refused the request's time (403): " + said + "." + nothing
          + " Check this computer's clock.", null);
    }
    if (sub == 3 || lower.contains("writes are not allowed")) {
      return ConnectorException.resourceError("Cosmos DB doesn't take writes in this region now (403" + (sub > 0 ? "."
          + sub : "") + ", for example during a failover)." + nothing + " Try again later.", null);
    }
    if (change && key) {
      return ConnectorException.resourceError("This key can't write: Azure refuses changes made with a read-only key "
          + "(403" + (said.isEmpty() ? "" : ": " + said) + ")." + nothing + " Use the primary or secondary key, or "
          + "Microsoft Entra ID with Cosmos DB Built-in Data Contributor, for approved changes.", null);
    }
    return ConnectorException.resourceError("Cosmos DB refused " + r.what() + " (403)" + (said.isEmpty() ? ""
        : ": " + said) + "." + nothing, null);
  }

  private static String notFound(String said, CosmosHttp.Request r) {
    String lower = said.toLowerCase(Locale.ROOT);
    if (lower.contains("owner resource does not exist") || lower.contains("collection") || lower.contains("database")) {
      return "The database or container of " + r.what() + " doesn't exist: " + said + ". Call cosmosdb_list_databases "
          + "or cosmosdb_list_containers to see them.";
    }
    return "Cosmos DB has no " + r.what().replaceFirst("^the ", "") + (said.isEmpty() ? "." : ": " + said + ".");
  }

  /** The gateway's refusal of a cross-partition query, with the clauses its query plan names. */
  static String gatewayRefused(JsonNode body) {
    Set<String> features = new LinkedHashSet<>();
    JsonNode info = body.path("additionalErrorInfo");
    try {
      JsonNode plan = info.isString() ? CosmosHttp.JSON.readTree(info.asString()) : info;
      JsonNode q = plan.path("queryInfo");
      if (!q.path("top").isNull() && !q.path("top").isMissingNode()) {
        features.add("TOP");
      }
      if (q.path("orderBy").size() > 0) {
        features.add("ORDER BY");
      }
      if (!q.path("offset").isNull() && !q.path("offset").isMissingNode()) {
        features.add("OFFSET");
      }
      if (!q.path("limit").isNull() && !q.path("limit").isMissingNode()) {
        features.add("LIMIT");
      }
      if (q.path("aggregates").size() > 0) {
        features.add("aggregates");
      }
      if (!"None".equals(q.path("distinctType").asString("None"))) {
        features.add("DISTINCT");
      }
      if (q.path("groupByExpressions").size() > 0) {
        features.add("GROUP BY");
      }
    } catch (JacksonException e) {
      // Without the plan the refusal names no clause.
    }
    return "Cosmos DB's gateway doesn't run " + (features.isEmpty() ? "TOP, ORDER BY, OFFSET LIMIT, DISTINCT, GROUP BY "
        + "or aggregates" : String.join(", ", features)) + " across partitions: only an SDK's query pipeline combines "
        + "them. Give partitionKey to run the query in one partition, or leave the clause out and sort or count the "
        + "items yourself.";
  }

  /** Cosmos DB's message without its diagnostics: one sentence or the query's syntax errors, sanitized. */
  static String message(JsonNode body) {
    List<String> parts = new ArrayList<>();
    for (JsonNode e : body.path("errors")) {
      parts.add(syntaxError(e));
    }
    for (JsonNode e : body.path("Errors")) {
      parts.add(e.asString(""));
    }
    if (parts.isEmpty()) {
      String m = body.path("message").asString(body.path("Message").asString(""));
      int json = m.indexOf("{\"errors\"");
      if (json >= 0) {
        try {
          JsonNode inner = CosmosHttp.JSON.readTree(m.substring(json, m.lastIndexOf('}') + 1));
          for (JsonNode e : inner.path("errors")) {
            parts.add(syntaxError(e));
          }
        } catch (JacksonException | IndexOutOfBoundsException e) {
          // Read as text below.
        }
      }
      if (parts.isEmpty()) {
        parts.add(diagnosticsCut(m));
      }
    }
    String joined = String.join("; ", parts.stream().filter(p -> !p.isBlank()).toList());
    String out = OutputSanitizer.sanitize(joined.strip());
    while (out.endsWith(".")) {
      out = out.substring(0, out.length() - 1);
    }
    return out.length() <= 500 ? out : out.substring(0, 499) + "…";
  }

  private static String syntaxError(JsonNode e) {
    String m = e.path("message").asString("").strip();
    while (m.endsWith(".")) {
      m = m.substring(0, m.length() - 1);
    }
    JsonNode at = e.path("location").path("start");
    return at.isNumber() ? m + " (at character " + at.asInt() + ")" : m;
  }

  private static String diagnosticsCut(String m) {
    String t = m;
    for (String marker : new String[] {"\r\n", "\\r\\n", "\n", "ActivityId:", "RequestStartTime:", ", Request URI:"}) {
      int i = t.indexOf(marker);
      if (i >= 0) {
        t = t.substring(0, i);
      }
    }
    return t.strip();
  }

  private static String retryAfter(String ms) {
    try {
      long v = (long) Double.parseDouble(ms.strip());
      return v < 1_000 ? v + " ms" : (v + 999) / 1_000 + " s";
    } catch (NumberFormatException e) {
      return "a moment";
    }
  }
}
