package ai.msrv.gaterook.connectors.dynamodb;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the DynamoDB form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}). On this computer: the
 * profiles of {@code ~/.aws/config} (the preferred one filled in when the credentials come from a profile), what keeps
 * the chosen profile from being used, and the regions with the profile's region filled in. Signed in as Test
 * connection signs in (the same credentials and read session): the tables of the region for Tables. A profile that
 * runs a command for its credentials, or can't be used, is never signed in with; the suggestion's sessions are
 * dropped when it is done.
 */
final class DynamoSuggestions {

  static final String PROFILE = "/credentials/profile";
  static final String REGION = "/region";
  static final String TABLES = "/tables";
  /** Said after what is wrong with a profile: why the form lists nothing from AWS. */
  static final String NO_SIGN_IN = "The setup helpers don't sign in with this profile, so nothing is listed from AWS.";
  /** Pages of {@code ListTables} a suggestion reads: 200 names, as many as a field offers. */
  private static final int PAGES = 2;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private DynamoSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      CloudCredentials cloud, CloudLocal local, DynamoApi api) {
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, cloud, api, out);
    } else {
      local(cfg, cloud, local, out);
    }
    return out.build();
  }

  private static void local(ConnectionConfig cfg, CloudCredentials cloud, CloudLocal local, Suggestions.Builder out) {
    boolean fromProfile = "profile".equals(text(cfg, "/credentials/source"));
    String profile = fromProfile ? text(cfg, PROFILE) : null;
    local.aws(out, fromProfile ? PROFILE : null, profile, false, REGION);
    if (!fromProfile) {
      return;
    }
    if (local.awsProfiles().isEmpty()) {
      out.note("There are no AWS profiles on this computer: run aws configure, or choose Access key.");
    } else if (profile != null) {
      // Which files are read doesn't depend on the region: any valid one makes the spec.
      cloud.profileWarnings(new AwsSpec(AwsSpec.Source.PROFILE, null, null, profile, null, null, 900, "us-east-1"))
          .forEach(out::note);
    }
  }

  private static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, CloudCredentials cloud,
      DynamoApi api, Suggestions.Builder out) {
    DynamoSettings s;
    try {
      s = DynamoSettings.of(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    if (s.credentials().source() == AwsSpec.Source.PROFILE) {
      List<String> warnings = cloud.profileWarnings(s.credentials());
      if (!warnings.isEmpty()) {
        warnings.forEach(out::note);
        out.note(NO_SIGN_IN);
        return;
      }
    }
    // Its own credentials, dropped afterwards: a form asks again with every change.
    ConnectionConfig own = own(cfg);
    try {
      CloudCredentials.CallerIdentity identity;
      try {
        AwsCredentialsProvider provider = CloudCredentials.tainting(cloud.aws(new CloudCredentials.Owner(own.id(),
            own.version(), own.name()), s.credentials(), s.readPolicy(), secrets), ctx::taint);
        identity = cloud.callerIdentity(provider, s.region());
      } catch (ConnectorException e) {
        out.note(e.getMessage());
        return;
      } catch (SdkException e) {
        out.note("The AWS credentials couldn't be loaded: " + DynamoApi.firstLine(e.getMessage()) + ".");
        return;
      }
      List<Option> tables = new ArrayList<>();
      boolean complete;
      try {
        complete = tables(new DynamoApi.Call(own, s, secrets, ctx), api, tables);
      } catch (ConnectorException e) {
        out.note("Signed in as " + identity.arn() + ". The tables can't be listed: " + e.getMessage());
        return;
      }
      out.field(TABLES, new Suggestions.Field(tables, null, "the account", complete));
      if (tables.isEmpty()) {
        out.note("Signed in as " + identity.arn() + ". The credentials see no tables in " + s.region() + ".");
        return;
      }
      out.note("Signed in as " + identity.arn() + ". " + tables.size() + (complete ? "" : "+")
          + (tables.size() == 1 ? " table" : " tables") + " in " + s.region() + ": choose the ones agents may use.");
      List<String> names = tables.stream().map(Option::value).toList();
      List<String> missing = s.tables().stream().filter(t -> t.indexOf('*') < 0 && !names.contains(t)).toList();
      if (complete && !missing.isEmpty()) {
        out.note("No table " + String.join(", ", missing) + " in " + s.region() + ".");
      }
    } finally {
      cloud.evict(own.id());
    }
  }

  /** The tables of the region, up to {@link #PAGES} pages; whether that is all of them. */
  private static boolean tables(DynamoApi.Call c, DynamoApi api, List<Option> out) throws ConnectorException {
    String start = null;
    for (int page = 0; page < PAGES; page++) {
      ObjectNode req = F.objectNode().put("Limit", DynamoDbConnector.LIST_PAGE);
      if (start != null) {
        req.put("ExclusiveStartTableName", start);
      }
      JsonNode r = api.call(c, "ListTables", req, c.settings().readPolicy(), false);
      for (JsonNode name : r.path("TableNames")) {
        if (!name.asString("").isEmpty()) {
          out.add(Option.of(name.asString()));
        }
      }
      start = r.path("LastEvaluatedTableName").asString(null);
      if (start == null) {
        return true;
      }
      if (c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
    }
    return false;
  }

  /** The draft under an owner of its own: the credentials a suggestion makes are dropped afterwards. */
  static ConnectionConfig own(ConnectionConfig cfg) {
    return new ConnectionConfig(cfg.id() + "#suggest", cfg.name(), cfg.type(), cfg.env(), cfg.version(), cfg.config(),
        cfg.secretFields(), cfg.metadata());
  }

  private static String text(ConnectionConfig cfg, String pointer) {
    String v = cfg.string(pointer);
    return v == null || v.isBlank() ? null : v.strip();
  }
}
