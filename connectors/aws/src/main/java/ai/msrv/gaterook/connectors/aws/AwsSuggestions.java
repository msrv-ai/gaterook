package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the AWS form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}).
 *
 * <ul>
 *   <li>On this computer: the profiles of {@code ~/.aws/config} and {@code ~/.aws/credentials}, the preferred one
 *       filled in when the credentials come from a profile, what keeps the chosen profile from being used, and every
 *       region with the profile's region filled in.</li>
 *   <li>Signed in as Test connection signs in (the same credentials, with a role its read-only session): the
 *       credentials' account filled into Account ID, the account's IAM alias into Account alias, and the regions
 *       enabled for the account ({@code ec2:DescribeRegions}), also while Regions is still empty
 *       ({@code x-suggest}): the account is then asked in the region the AWS CLI would use. The alias and the regions
 *       are read as {@code aws_api_read} reads them, each under the read session of its service; the person's
 *       environment policy limits what agents see, not what the form shows the person.</li>
 * </ul>
 * A profile that runs a command for its credentials, or can't be used at all, is never signed in with: a note says
 * why nothing is listed. The sessions of a suggestion are dropped when it is done.
 */
final class AwsSuggestions {

  static final String PROFILE = "/credentials/profile";
  static final String REGIONS = "/regions";
  static final String ACCOUNT_ID = "/accountId";
  static final String ALIAS = "/accountAlias";
  /** Said after what is wrong with a profile: why the form lists nothing from AWS. */
  static final String NO_SIGN_IN = "The setup helpers don't sign in with this profile, so nothing is listed from AWS.";
  private static final String ACCOUNT = "the account";

  private final CloudCredentials cloud;
  private final Clients clients;
  private final HttpClient http;
  private final CloudLocal local;

  AwsSuggestions(CloudCredentials cloud, Clients clients, HttpClient http, CloudLocal local) {
    this.cloud = cloud;
    this.clients = clients;
    this.http = http;
    this.local = local;
  }

  Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, out);
    } else {
      local(cfg, out);
    }
    return out.build();
  }

  // ── This computer ─────────────────────────────────────────────────────────────────────────────────────────────

  /** Profiles (filled in only for a profile source) and regions, and what keeps the chosen profile from working. */
  private void local(ConnectionConfig cfg, Suggestions.Builder out) {
    boolean fromProfile = "profile".equals(text(cfg, "/credentials/source"));
    String profile = fromProfile ? text(cfg, PROFILE) : null;
    local.aws(out, fromProfile ? PROFILE : null, profile, true, REGIONS);
    if (fromProfile) {
      profileNotes(local, cloud, profile, out);
    }
  }

  /** No profiles at all, or why the typed one can't be used (it doesn't exist, uses SSO, runs a command). */
  static void profileNotes(CloudLocal local, CloudCredentials cloud, String profile, Suggestions.Builder out) {
    if (local.awsProfiles().isEmpty()) {
      out.note("There are no AWS profiles on this computer: run aws configure, or choose Access key.");
    } else if (profile != null) {
      // Which files are read doesn't depend on the region: any valid one makes the spec.
      cloud.profileWarnings(new AwsSpec(AwsSpec.Source.PROFILE, null, null, profile, null, null, 900, "us-east-1"))
          .forEach(out::note);
    }
  }

  /**
   * Whether the suggestions must not sign in with these credentials: a profile that runs a command for them
   * ({@code credential_process}) or can't be used. Notes say so.
   */
  static boolean unusableProfile(CloudCredentials cloud, AwsSpec spec, Suggestions.Builder out) {
    if (spec.source() != AwsSpec.Source.PROFILE) {
      return false;
    }
    List<String> warnings = cloud.profileWarnings(spec);
    if (warnings.isEmpty()) {
      return false;
    }
    warnings.forEach(out::note);
    out.note(NO_SIGN_IN);
    return true;
  }

  // ── Signed in ─────────────────────────────────────────────────────────────────────────────────────────────────

  private void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Builder out) {
    // Regions are listed here (x-suggest): they may still be empty or half typed, so the account is asked in the
    // valid ones, else in the region the AWS CLI would use.
    List<String> typed = new ArrayList<>();
    for (String r : cfg.strings(REGIONS)) {
      String region = r == null ? "" : r.strip();
      if (Settings.REGION.matcher(region).matches() && !typed.contains(region)) {
        typed.add(region);
      }
    }
    Settings s;
    try {
      s = Settings.parse(withRegions(cfg, typed.isEmpty() ? List.of(cliRegion(cfg)) : typed));
    } catch (Settings.InvalidSettingsException e) {
      out.note(e.getMessage());
      return;
    }
    if (unusableProfile(cloud, s.credentials(), out)) {
      return;
    }
    // Its own credentials and clients, dropped afterwards: a form asks again with every change.
    ConnectionConfig own = own(withRegions(cfg, s.regions()));
    try {
      Call c = new Call(own, s, AwsPolicy.defaults(cfg.env()), clients, cloud, secrets, ctx);
      CloudCredentials.CallerIdentity id;
      try {
        AwsCredentialsProvider session = c.credentials();
        Call.resolve(session);
        id = cloud.callerIdentity(session, s.firstRegion());
      } catch (ConnectorException e) {
        out.note(e.getMessage());
        return;
      }
      String account = id.account();
      if (account != null && Settings.ACCOUNT.matcher(account).matches()) {
        out.value(ACCOUNT_ID, "the credentials", account, List.of(new Option(account, null, id.arn())));
      }
      alias(c, own, s, ctx, out);
      List<String> enabled = regions(c, own, s, ctx);
      StringBuilder note = new StringBuilder("Signed in as ").append(id.arn()).append('.');
      if (enabled != null) {
        out.options(REGIONS, ACCOUNT, regionOptions(enabled, s.regions()));
        note.append(' ').append(enabled.size()).append(enabled.size() == 1 ? " region is" : " regions are")
            .append(" enabled for the account: add the ones agents may read.");
      }
      out.note(note.toString());
      if (s.accountId() != null && account != null && !s.accountId().equals(account)) {
        out.note("The credentials are for account " + account + ", not " + s.accountId() + ": fix Account ID or "
            + "the credentials.");
      }
      if (enabled != null) {
        List<String> off = typed.stream().filter(r -> !enabled.contains(r)).toList();
        if (!off.isEmpty()) {
          out.note(String.join(" and ", off) + (off.size() == 1 ? " isn't" : " aren't") + " enabled for the "
              + "account: calls there fail.");
        }
      }
    } finally {
      cloud.evict(own.id());
      clients.evict(own.id());
    }
  }

  /** The region the AWS CLI would use with the form's profile (or its default one), else {@code us-east-1}. */
  private String cliRegion(ConnectionConfig cfg) {
    boolean fromProfile = "profile".equals(text(cfg, "/credentials/source"));
    String region = local.awsRegion(fromProfile ? text(cfg, PROFILE) : null);
    return region != null && Settings.REGION.matcher(region).matches() ? region : "us-east-1";
  }

  /** The draft with {@code regions} as its regions, for the requests of a suggestion only. */
  private static ConnectionConfig withRegions(ConnectionConfig cfg, List<String> regions) {
    if (!(cfg.config() instanceof ObjectNode config) || regions.equals(cfg.strings(REGIONS))) {
      return cfg;
    }
    ObjectNode copy = config.deepCopy();
    ArrayNode list = copy.putArray("regions");
    regions.forEach(list::add);
    return new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(), copy, cfg.secretFields(),
        cfg.metadata());
  }

  /** The account's IAM alias (an account has at most one); display only, so a refusal just offers nothing. */
  private void alias(Call c, ConnectionConfig own, Settings s, ExecContext ctx, Suggestions.Builder out) {
    JsonNode result = read(c, own, s, ctx, "iam", "ListAccountAliases");
    if (result == null) {
      return;
    }
    for (JsonNode alias : result.path("AccountAliases")) {
      String a = alias.asString("").strip();
      if (!a.isEmpty() && a.length() <= 63) {
        out.value(ALIAS, ACCOUNT, a, List.of(Option.of(a)));
        return;
      }
    }
  }

  /** The regions enabled for the account, or {@code null} when the credentials may not list them. */
  private List<String> regions(Call c, ConnectionConfig own, Settings s, ExecContext ctx) {
    JsonNode result = read(c, own, s, ctx, "ec2", "DescribeRegions");
    if (result == null) {
      return null;
    }
    List<String> out = new ArrayList<>();
    for (JsonNode r : result.path("Regions")) {
      String name = r.path("RegionName").asString("");
      if (Settings.REGION.matcher(name).matches() && !out.contains(name)) {
        out.add(name);
      }
    }
    return out;
  }

  /** One read of the AWS API catalog, as {@code aws_api_read} makes it; its {@code result}, or {@code null}. */
  private JsonNode read(Call c, ConnectionConfig own, Settings s, ExecContext ctx, String service, String operation) {
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("service", service).put("operation", operation);
    try {
      ApiRequest r = ApiRequest.of(ApiRequest.READ, args, own, s, null, ctx.callId());
      return new ApiCall(c, http).run(r).structuredContent().path("result");
    } catch (Refusal | ConnectorException e) {
      return null;
    }
  }

  /**
   * The enabled regions, those near the connection's first region first (its area, such as {@code eu-}), then in the
   * order of the AWS console's list; each with its name.
   */
  static List<Option> regionOptions(List<String> enabled, List<String> chosen) {
    String first = chosen.isEmpty() ? "" : chosen.getFirst();
    String area = first.contains("-") ? first.substring(0, first.indexOf('-') + 1) : first;
    List<String> known = List.copyOf(CloudLocal.AWS_REGIONS.keySet());
    List<String> sorted = new ArrayList<>(enabled);
    sorted.sort(Comparator.<String, Boolean>comparing(r -> area.isEmpty() || !r.startsWith(area))
        .thenComparing(r -> known.contains(r) ? known.indexOf(r) : Integer.MAX_VALUE)
        .thenComparing(Comparator.naturalOrder()));
    return sorted.stream().map(r -> new Option(r, null, CloudLocal.AWS_REGIONS.get(r))).toList();
  }

  /** The draft under an owner of its own: credentials and clients a suggestion makes are dropped afterwards. */
  static ConnectionConfig own(ConnectionConfig cfg) {
    return new ConnectionConfig(cfg.id() + "#suggest", cfg.name(), cfg.type(), cfg.env(), cfg.version(), cfg.config(),
        cfg.secretFields(), cfg.metadata());
  }

  private static String text(ConnectionConfig cfg, String pointer) {
    String v = cfg.string(pointer);
    return v == null || v.isBlank() ? null : v.strip();
  }
}
