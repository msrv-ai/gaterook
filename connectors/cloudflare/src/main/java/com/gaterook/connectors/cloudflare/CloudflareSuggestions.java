package com.gaterook.connectors.cloudflare;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Help for the Cloudflare form ({@link com.gaterook.connector.api.Connector#suggest}).
 *
 * <p>On this computer: the account ID inside what was pasted into Account ID (a dashboard link), the account
 * {@code CLOUDFLARE_ACCOUNT_ID} names, and what a typed token is not (the Global API Key, an Authorization header).
 * Signed in with the draft's token, while Account ID may still be empty ({@code x-suggest}): the accounts the token can
 * see, the only one filled in, and the zones of the account, with a note for each list the token may not read. GETs
 * only, from GateRook's share of the account's requests.
 */
final class CloudflareSuggestions {

  /** Where the signed-in options come from. */
  static final String TOKEN = "the API token";
  /** Pages of 50 zones read at most. */
  static final int ZONE_PAGES = 4;
  /** One page of {@code GET /accounts}: a token sees few accounts. */
  static final int ACCOUNT_PAGE = 50;
  private static final Pattern ID_IN_TEXT = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])");
  /** The Global API Key: 37 hexadecimal characters, never an API token. */
  private static final Pattern GLOBAL_KEY = Pattern.compile("[0-9a-f]{37}");

  /** One account the token can see. */
  record Account(String id, String name) {

    /** {@code Acme (0123…cdef)}, or the ID alone. */
    String text() {
      return name == null ? id : name + " (" + id + ")";
    }
  }

  private CloudflareSuggestions() {}

  /**
   * The draft's settings as far as they go: an Account ID still empty or half typed is no account yet, a zone name
   * that isn't one is left out. Suggestions only; a connection's calls read {@link CloudflareSettings#parse}.
   */
  static CloudflareSettings draft(ConnectionConfig cfg) {
    String account = text(cfg.string("/accountId"));
    List<String> zones = new ArrayList<>();
    for (String z : cfg.strings("/zones")) {
      String name = CloudflareSettings.normalize(z);
      if (name != null && !zones.contains(name)) {
        zones.add(name);
      }
    }
    return new CloudflareSettings(account != null && Args.HEX32.matcher(account).matches() ? account : null, zones);
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  static Suggestions local(ConnectionConfig cfg, SecretResolver secrets, Function<String, String> env) {
    Suggestions.Builder out = Suggestions.builder();
    String typed = text(cfg.string("/accountId"));
    List<Option> options = new ArrayList<>();
    String source = null;
    String value = null;
    if (typed != null && !Args.HEX32.matcher(typed).matches()) {
      // A dashboard link (dash.cloudflare.com/<account>/…) or an ID in capitals: its ID as Cloudflare writes it.
      Matcher m = ID_IN_TEXT.matcher(typed);
      if (m.find()) {
        options.add(Option.of(m.group().toLowerCase(Locale.ROOT), "the ID in what you pasted"));
        source = "what you pasted";
      }
    }
    String named = text(env.apply("CLOUDFLARE_ACCOUNT_ID"));
    if (named != null && Args.HEX32.matcher(named.toLowerCase(Locale.ROOT)).matches()) {
      String id = named.toLowerCase(Locale.ROOT);
      if (options.stream().noneMatch(o -> o.value().equals(id))) {
        options.add(Option.of(id, "CLOUDFLARE_ACCOUNT_ID"));
      }
      source = source == null ? "CLOUDFLARE_ACCOUNT_ID" : source;
      value = typed == null ? id : null;
    }
    out.value("/accountId", source, value, options);
    if (secrets != null && secrets.isSet(CloudflareSettings.TOKEN)) {
      try (SecretResolver.Secret token = secrets.secret(CloudflareSettings.TOKEN)) {
        String t = token.unsafeString().strip();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
          out.note("Paste the API token without the word Bearer.");
        } else if (GLOBAL_KEY.matcher(t).matches()) {
          out.note("This looks like the Global API Key, which GateRook doesn't accept: create an API token under My "
              + "Profile → API Tokens.");
        }
      } catch (ConnectorException e) {
        // Not typed here: nothing to read.
      }
    }
    return out.build();
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  static Suggestions service(Call c) {
    Suggestions.Builder out = Suggestions.builder();
    List<Account> accounts;
    try {
      accounts = visible(c);
    } catch (ConnectorException e) {
      return out.note(e.getMessage()).build();
    }
    String configured = c.settings().accountId();
    out.value("/accountId", TOKEN, accounts.size() == 1 ? accounts.getFirst().id() : null,
        accounts.stream().map(a -> new Option(a.id(), a.name(), null)).toList());
    String account = configured != null ? configured : accounts.size() == 1 ? accounts.getFirst().id() : null;
    Account known = accounts.stream().filter(a -> a.id().equals(account)).findFirst().orElse(null);
    if (accounts.isEmpty()) {
      out.note("The API token sees no account: Workers, KV, Pages, tunnels and Logpush need a permission on it, such "
          + "as Account · Workers Scripts · Read." + (configured == null ? " Enter the Account ID." : ""));
    } else if (configured != null && known == null) {
      out.note("The API token doesn't see account " + configured + ": it sees " + String.join(", ", accounts.stream()
          .limit(3).map(Account::text).toList()) + (accounts.size() > 3 ? " and " + (accounts.size() - 3) + " more"
          : "") + ". Check the Account ID.");
    } else if (configured == null && accounts.size() > 1) {
      out.note("The API token sees " + accounts.size() + " accounts: choose the one agents may use.");
    }
    if (account != null) {
      zones(c, account, known, out);
    }
    return out.build();
  }

  /** The accounts the call's token can see, in Cloudflare's order. */
  static List<Account> visible(Call c) throws ConnectorException {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("per_page", String.valueOf(ACCOUNT_PAGE));
    List<Account> out = new ArrayList<>();
    for (JsonNode a : c.api().get(c, c.api().uri(List.of("accounts"), q), "accounts").result()) {
      String id = a.path("id").asString("");
      if (Args.HEX32.matcher(id).matches() && out.stream().noneMatch(x -> x.id().equals(id))) {
        out.add(new Account(id, text(a.path("name").asString(""))));
      }
    }
    return out;
  }

  /** The zones of the account the token can see, by name. */
  private static void zones(Call c, String account, Account known, Suggestions.Builder out) {
    List<Option> options = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    boolean complete = true;
    for (int page = 1; page <= ZONE_PAGES; page++) {
      if (c.ctx().remaining().compareTo(ConnectionTest.LAST_PROBE) <= 0) {
        complete = false;
        break;
      }
      Map<String, String> q = new LinkedHashMap<>();
      q.put("account.id", account);
      q.put("order", "name");
      q.put("per_page", "50");
      q.put("page", String.valueOf(page));
      CloudflareApi.Answer a;
      try {
        a = c.api().get(c, c.api().uri(List.of("zones"), q), "zones");
      } catch (ConnectorException e) {
        out.note(e instanceof CloudflareApi.CloudflareException ce && ce.status == 403
            ? "The API token can't list zones: give it Zone · Zone · Read." : e.getMessage());
        return;
      }
      for (JsonNode z : a.result()) {
        String name = CloudflareSettings.normalize(z.path("name").asString(""));
        if (name != null && seen.add(name)) {
          String status = z.path("status").asString("");
          options.add(Option.of(name, status.isEmpty() || status.equals("active") ? null : status));
        }
      }
      if (page >= a.info().path("total_pages").asInt(1)) {
        break;
      }
      complete = page < ZONE_PAGES;
    }
    String where = known == null ? "account " + account : known.text();
    if (options.isEmpty()) {
      out.note("The API token sees no zone of " + where + ": give it Zone · Zone · Read for the DNS, cache and "
          + "analytics tools.");
      return;
    }
    out.field("/zones", new Suggestions.Field(options, null, TOKEN, complete));
    out.note("The API token reads " + where + ": " + options.size() + (complete ? "" : "+")
        + (options.size() == 1 ? " zone" : " zones") + ". Choose the zones agents may use; empty means all of them.");
    if (complete) {
      List<String> missing = c.settings().zones().stream().filter(z -> !seen.contains(z)).toList();
      if (!missing.isEmpty()) {
        out.note("The API token doesn't see " + (missing.size() == 1 ? "zone " : "zones ") + String.join(", ", missing
            .stream().limit(3).toList()) + (missing.size() > 3 ? " and " + (missing.size() - 3) + " more" : "")
            + " in this account: calls for " + (missing.size() == 1 ? "it" : "them") + " will fail.");
      }
    }
  }

  private static String text(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
