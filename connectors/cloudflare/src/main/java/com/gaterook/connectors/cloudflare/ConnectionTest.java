package com.gaterook.connectors.cloudflare;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.TestResult;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection of Cloudflare: each step timed and shown; a failure of the token stops the test with what happened
 * and what to do, the other steps become hints.
 *
 * <ol>
 *   <li>the token: {@code GET /user/tokens/verify}, or {@code GET /accounts/{id}/tokens/verify} for an account-owned
 *       token; it must be active;</li>
 *   <li>the accounts it can see ({@code GET /accounts}): the connection's must be one of them for the account
 *       tools;</li>
 *   <li>the zones of the account it can see ({@code GET /zones?account.id=…}), and each listed zone among them;</li>
 *   <li>one read per family (DNS, Workers, KV, Pages, tunnels, Logpush, analytics): which tools will work;</li>
 *   <li>the token's own permissions, when the token may read them: changes it could make, a token that creates
 *       tokens.</li>
 * </ol>
 */
final class ConnectionTest {

  /** Time kept for the last steps: a probe doesn't start after it. */
  static final Duration LAST_PROBE = Duration.ofMillis(1_200);
  /** Permission groups that can change something, as Cloudflare names them in a token's policies. */
  private static final java.util.regex.Pattern WRITES = java.util.regex.Pattern.compile("(?i).*\\b(write|edit|purge"
      + "|admin)\\b.*");

  private final Call call;
  private final List<TestResult.Check> checks = new ArrayList<>();
  private final List<TestResult.Hint> hints = new ArrayList<>();

  ConnectionTest(Call call) {
    this.call = call;
  }

  /** What {@link #verify} found: the token's ID, whether the account owns it, its expiry. */
  record Token(String id, boolean accountOwned, String expiresOn) {}

  TestResult run() {
    CloudflareApi api = call.api();
    CloudflareSettings s = call.settings();
    long started = System.nanoTime();
    Token token;
    try {
      token = verify();
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("token", false, ms(started), e.getMessage()));
      return failure(e);
    }
    long latency = ms(started);
    checks.add(new TestResult.Check("token", true, latency, (token.accountOwned() ? "Account" : "User")
        + " API token " + token.id() + " is active" + (token.expiresOn() == null ? "" : " until "
        + token.expiresOn())));
    expiry(token);

    // Accounts the token can see.
    ObjectNode details = Answers.F.objectNode();
    ArrayNode accounts = details.putArray("accounts");
    String accountName = null;
    boolean accountVisible = false;
    long t = System.nanoTime();
    try {
      Map<String, String> q = new LinkedHashMap<>();
      q.put("per_page", "50");
      for (JsonNode a : api.get(call, api.uri(List.of("accounts"), q), "accounts").result()) {
        accounts.addObject().put("id", a.path("id").asString("")).put("name", a.path("name").asString(""));
        if (s.accountId().equals(a.path("id").asString(""))) {
          accountVisible = true;
          accountName = a.path("name").asString("");
        }
      }
      checks.add(new TestResult.Check("accounts", true, ms(t), accounts.size() + (accounts.size() == 1 ? " account"
          : " accounts") + (accountVisible ? " · " + s.accountId() + (accountName.isBlank() ? "" : " (" + accountName
          + ")") : " · not " + s.accountId())));
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("accounts", false, ms(t), e.getMessage()));
    }
    if (!accountVisible) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "cloudflare.account-hidden", "/accountId",
          "The token doesn't see account " + s.accountId() + ": Workers, Workers Logs, KV, Pages, tunnels and "
              + "account Logpush jobs won't work. Check the Account ID and the token's account resources."));
    }

    // Zones of the account.
    ArrayNode zones = details.putArray("zones");
    ArrayNode known = Answers.F.arrayNode();
    String firstZone = null;
    t = System.nanoTime();
    try {
      Map<String, String> q = new LinkedHashMap<>();
      q.put("account.id", s.accountId());
      q.put("order", "name");
      q.put("per_page", "50");
      CloudflareApi.Answer a = api.get(call, api.uri(List.of("zones"), q), "zones");
      TreeSet<String> seen = new TreeSet<>();
      for (JsonNode z : a.result()) {
        String name = CloudflareSettings.normalize(z.path("name").asString(""));
        if (name == null) {
          continue;
        }
        seen.add(name);
        if (s.zoneAllowed(name)) {
          zones.addObject().put("id", z.path("id").asString("")).put("name", name)
              .put("status", z.path("status").asString(""));
          known.addObject().put("id", z.path("id").asString("")).put("name", name);
          if (firstZone == null) {
            firstZone = z.path("id").asString("");
          }
        }
      }
      int total = a.info().path("total_count").asInt(seen.size());
      checks.add(new TestResult.Check("zones", true, ms(t), total + (total == 1 ? " zone" : " zones")
          + (s.zones().isEmpty() ? "" : " · " + zones.size() + " of " + s.zones().size() + " listed")));
      for (String listed : s.zones()) {
        if (!seen.contains(listed) && total <= 50) {
          hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "cloudflare.zone-hidden", "/zones",
              "The token doesn't see zone " + listed + " in account " + s.accountId() + ": calls for it will fail."));
        }
      }
      if (zones.isEmpty()) {
        hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "cloudflare.no-zones", "/zones",
            "The token sees no zone of this account that the connection lists: DNS, cache, analytics and zone "
                + "Logpush tools have nothing to work on. Give it Zone · Zone · Read."));
      }
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("zones", false, ms(t), e.getMessage()));
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "cloudflare.zones-denied", "/credentials/token",
          "The token can't list zones: " + e.getMessage()));
    }

    // One read per family.
    List<String> readable = new ArrayList<>();
    List<String> denied = new ArrayList<>();
    String a = s.accountId();
    if (firstZone != null) {
      Map<String, String> q = new LinkedHashMap<>();
      q.put("per_page", "1");
      probe("DNS", api.uri(List.of("zones", firstZone, "dns_records"), q), "cloudflare_dns_records", readable,
          denied);
    }
    if (accountVisible) {
      probe("Workers", api.uri("accounts", a, "workers", "scripts"), "cloudflare_workers", readable, denied);
      probe("Workers KV", api.uri(List.of("accounts", a, "storage", "kv", "namespaces"), Map.of("per_page", "5")),
          "cloudflare_kv", readable, denied);
      probe("Pages", api.uri(List.of("accounts", a, "pages", "projects"), Map.of("per_page", "1")), "cloudflare_pages",
          readable, denied);
      probe("tunnels", api.uri(List.of("accounts", a, "cfd_tunnel"), Map.of("per_page", "1")), "cloudflare_tunnels",
          readable, denied);
      probe("Logpush", api.uri("accounts", a, "logpush", "jobs"), "cloudflare_logpush_jobs", readable, denied);
    }
    if (firstZone != null && call.ctx().remaining().compareTo(LAST_PROBE) > 0) {
      long p = System.nanoTime();
      Instant now = call.ctx().clock().instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      String q = "query GateRookTest { viewer { zones(filter: {zoneTag: " + Analytics.literal(firstZone)
          + "}) { httpRequestsAdaptiveGroups(filter: {datetime_geq: " + Analytics.literal(now.minusSeconds(600)
          .toString()) + ", datetime_leq: " + Analytics.literal(now.toString()) + "}, limit: 1) { count } } } }";
      try {
        api.graphql(call, q, "analytics");
        readable.add("analytics");
        checks.add(new TestResult.Check("analytics", true, ms(p), "cloudflare_analytics can read the zone's "
            + "analytics"));
      } catch (ConnectorException e) {
        denied.add("analytics");
        checks.add(new TestResult.Check("analytics", false, ms(p), e.getMessage()));
      }
    }
    if (!denied.isEmpty()) {
      hints.add(new TestResult.Hint(TestResult.Severity.INFO, "cloudflare.families-denied", "/credentials/token",
          "Not readable with this token: " + String.join(", ", denied) + ". Their tools will be refused by "
              + "Cloudflare; add read permissions for them if agents need them."));
    }

    // The token's own permissions, when it may read them.
    TestResult.Level level = TestResult.Level.UNKNOWN;
    List<String> writes = new ArrayList<>();
    if (call.ctx().remaining().compareTo(LAST_PROBE) > 0) {
      long p = System.nanoTime();
      URI own = token.accountOwned() ? api.uri("accounts", a, "tokens", token.id())
          : api.uri("user", "tokens", token.id());
      try {
        JsonNode policies = api.get(call, own, "API token").result().path("policies");
        for (JsonNode policy : policies) {
          if (!"allow".equals(policy.path("effect").asString("allow"))) {
            continue;
          }
          for (JsonNode g : policy.path("permission_groups")) {
            String name = g.path("name").asString("");
            if (WRITES.matcher(name).matches() && !writes.contains(name)) {
              writes.add(name);
            }
          }
        }
        level = writes.isEmpty() ? TestResult.Level.READ : TestResult.Level.WRITE;
        checks.add(new TestResult.Check("permissions", true, ms(p), writes.isEmpty() ? "Read permissions only"
            : "Can change: " + String.join(", ", writes)));
        if (writes.stream().anyMatch(w -> w.toLowerCase(java.util.Locale.ROOT).contains("api tokens"))) {
          hints.add(new TestResult.Hint(TestResult.Severity.DANGER, "cloudflare.creates-tokens",
              "/credentials/token", "This token can create or change API tokens (" + String.join(", ", writes
              .stream().filter(w -> w.toLowerCase(java.util.Locale.ROOT).contains("api tokens")).toList())
              + "): a leaked copy could mint broader ones. Use a token without API Tokens permissions."));
        }
        if (!writes.isEmpty()) {
          hints.add(new TestResult.Hint(call.cfg().env() == Env.PROD ? TestResult.Severity.WARNING
              : TestResult.Severity.INFO, "cloudflare.can-write", "/credentials/token", "This token can change "
              + "resources (" + String.join(", ", writes) + "). GateRook's change tools wait for approval; keep only "
              + "the Edit permissions agents should be able to request."));
        }
      } catch (ConnectorException e) {
        hints.add(new TestResult.Hint(TestResult.Severity.INFO, "cloudflare.permissions-unknown",
            "/credentials/token", "GateRook can't read this token's permissions, so what it may change is unknown "
                + "until a change is tried. Changes wait for approval either way."));
      }
    }

    ObjectNode metadata = Answers.F.objectNode();
    metadata.put("serverVersion", "Cloudflare API v4");
    metadata.put("tokenKind", token.accountOwned() ? "account" : "user");
    metadata.set("zones", known);
    if (accountName != null) {
      metadata.putObject("account").put("id", s.accountId()).put("name", accountName);
    }
    ArrayNode readableNode = details.putArray("readable");
    readable.forEach(readableNode::add);
    String identity = (token.accountOwned() ? "account token " : "user token ") + token.id()
        + (accountName == null || accountName.isBlank() ? "" : " · " + accountName);
    TestResult result = TestResult.success(latency, "Cloudflare API v4", identity,
        new TestResult.Permissions(level, readable.isEmpty() ? "Nothing beyond the token itself"
            : "Reads " + String.join(", ", readable), level == TestResult.Level.WRITE ? "Can change: "
            + String.join(", ", writes) : level == TestResult.Level.READ ? "None" : "Unknown", List.copyOf(writes),
            null))
        .withChecks(checks)
        .withMetadata(metadata)
        .withDetails(details);
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  /** Health: the token is still active; one request. */
  HealthResult health() {
    long started = System.nanoTime();
    try {
      verify();
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  /** The token, as a user token or, when Cloudflare doesn't know it as one, as the account's. */
  Token verify() throws ConnectorException {
    CloudflareApi api = call.api();
    JsonNode r;
    boolean account = false;
    try {
      r = api.get(call, api.uri("user", "tokens", "verify"), "API token").result();
    } catch (CloudflareApi.CloudflareException e) {
      if (e.status != 401 && e.status != 403 && e.status != 400) {
        throw e;
      }
      try {
        r = api.get(call, api.uri("accounts", call.settings().accountId(), "tokens", "verify"), "API token").result();
        account = true;
      } catch (CloudflareApi.CloudflareException second) {
        if (second.status / 100 == 5) {
          throw second;
        }
        throw new CloudflareApi.CloudflareException(ConnectorException.Kind.RESOURCE_ERROR, "Cloudflare doesn't "
            + "accept this API token, as a user token (" + e.getMessage() + ") or as a token of account "
            + call.settings().accountId() + " (" + second.getMessage() + "). Check the token and, for an account "
            + "API token, the Account ID.", second.status, second.code);
      }
    }
    String status = r.path("status").asString("");
    String id = r.path("id").asString("");
    if (!status.equals("active")) {
      throw ConnectorException.resourceError("The API token is " + (status.isEmpty() ? "not active" : status)
          + ". Activate it or create a new one in the Cloudflare dashboard.", null);
    }
    if (!id.matches("[0-9a-f]{32}")) {
      throw ConnectorException.resourceError("Cloudflare verified the token but gave no token ID GateRook can read.",
          null);
    }
    String expires = r.path("expires_on").asString(null);
    return new Token(id, account, expires == null || expires.isBlank() ? null : expires);
  }

  private void expiry(Token token) {
    if (token.expiresOn() == null) {
      if (call.cfg().env() == Env.PROD) {
        hints.add(new TestResult.Hint(TestResult.Severity.INFO, "cloudflare.no-expiry", "/credentials/token",
            "The token never expires. A token with an expiry date and client IP filtering limits what a leaked copy "
                + "can do."));
      }
      return;
    }
    try {
      Instant end = OffsetDateTime.parse(token.expiresOn()).toInstant();
      Duration left = Duration.between(call.ctx().clock().instant(), end);
      if (left.compareTo(Duration.ofDays(14)) < 0) {
        hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "cloudflare.expires-soon", "/credentials/token",
            "The token expires on " + token.expiresOn() + ": create its successor in time."));
      }
    } catch (DateTimeParseException e) {
      // Shown as Cloudflare wrote it; no hint.
    }
  }

  private void probe(String family, URI uri, String tool, List<String> readable, List<String> denied) {
    if (call.ctx().remaining().compareTo(LAST_PROBE) <= 0) {
      checks.add(new TestResult.Check(family, false, 0, "Not checked: the test ran out of time"));
      return;
    }
    long started = System.nanoTime();
    try {
      call.api().get(call, uri, family);
      readable.add(family);
      checks.add(new TestResult.Check(family, true, ms(started), tool + " can read"));
    } catch (ConnectorException e) {
      denied.add(family);
      checks.add(new TestResult.Check(family, false, ms(started), e.getMessage()));
    }
  }

  private TestResult failure(ConnectorException e) {
    String code = switch (e.kind()) {
      case TIMEOUT -> "cloudflare.timeout";
      case RESOURCE_UNAVAILABLE -> "cloudflare.unreachable";
      case BAD_INPUT -> "cloudflare.invalid";
      default -> "cloudflare.token";
    };
    Throwable cause = e.getCause();
    return TestResult.failure(code, e.getMessage(), cause == null ? null : cause.getClass().getSimpleName() + ": "
        + cause.getMessage()).withChecks(checks);
  }

  private static long ms(long startedNanos) {
    return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
  }
}
