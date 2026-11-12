package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import tools.jackson.databind.JsonNode;

/**
 * Help for the AWS CloudWatch form. On this computer: the profiles of {@code ~/.aws/config} with the region of the
 * chosen one (or of {@code AWS_PROFILE}, {@code AWS_REGION}) filled in, and what keeps a chosen profile from being
 * used. Signed in, with the draft's credentials and Test connection's read-only session policy: who, and the log
 * groups of the region with their retention and size. A profile that runs a command for its credentials isn't used
 * for suggestions.
 */
final class CloudWatchSuggestions {

  private CloudWatchSuggestions() {}

  static void local(ConnectionConfig cfg, CloudLocal local, CloudCredentials cloud, Suggestions.Builder out) {
    JsonNode c = cfg.config();
    String source = Optional.ofNullable(LogsSuggestions.text(c, "/credentials/source")).orElse("accessKey");
    String profile = LogsSuggestions.text(c, "/credentials/profile");
    local.aws(out, "/credentials/profile", profile, false, "/region");
    int profiles = local.awsProfiles().size();
    if (!source.equals("profile") && LogsSuggestions.text(c, "/credentials/accessKeyId") == null && profiles > 0) {
      out.note("Found " + LogsSuggestions.count(profiles, "profile", "profiles") + " in ~/.aws/config: choose AWS "
          + "profile under Credential source to sign in with one.");
    }
    if (source.equals("profile") && profile != null) {
      // Only the profile is read; a valid region is all the spec needs to say what's wrong with it.
      AwsSpec spec = new AwsSpec(AwsSpec.Source.PROFILE, null, null, profile, null, null, 900, "us-east-1");
      cloud.profileWarnings(spec).forEach(out::note);
    }
  }

  /**
   * Signed in as Test connection does: {@code sts:GetCallerIdentity}, then the log groups of the region
   * ({@code DescribeLogGroups}, at most {@link Suggestions#MAX_OPTIONS}).
   */
  static void service(ConnectionConfig cfg, Backend.Call call, Suggestions.Builder out) throws ConnectorException {
    JsonNode c = cfg.config();
    String region = LogsSuggestions.text(c, "/region");
    if (region == null || !Settings.REGION.matcher(region).matches()) {
      return;
    }
    AwsSpec spec = AwsSpec.fromConfig(c, "/credentials", region);
    if (spec.source() == AwsSpec.Source.PROFILE
        && LogsSuggestions.awsProfileRunsCommand(call.cloud().endpoints(), spec.profile())) {
      out.note("The profile " + spec.profile() + " runs a command to get credentials, which GateRook doesn't do for "
          + "suggestions: type the log groups, and Test connection checks them.");
      return;
    }
    List<String> configured = LogsSuggestions.strings(c, "/logGroups");
    CloudWatchBackend cw = new CloudWatchBackend(new Settings.CloudWatch(region, configured, spec, true), call);
    String arn = cw.identity().arn();
    CloudWatchBackend.Groups found;
    try {
      found = cw.logGroups(Suggestions.MAX_OPTIONS);
    } catch (ConnectorException e) {
      out.note("Signed in as " + arn + ". " + e.getMessage());
      return;
    }
    List<Option> options = new ArrayList<>();
    for (LogGroup g : found.groups()) {
      if (g.logGroupName() != null && Settings.LOG_GROUP.matcher(g.logGroupName()).matches()) {
        options.add(Option.of(g.logGroupName(), detail(g)));
      }
    }
    out.field("/logGroups", new Suggestions.Field(options, null, "CloudWatch Logs", found.complete()));
    if (options.isEmpty()) {
      out.note("Signed in as " + arn + ". There are no log groups in " + region + ": check the region.");
      return;
    }
    out.note("Signed in as " + arn + ". " + LogsSuggestions.count(options.size(), "log group", "log groups")
        + (found.complete() ? "" : " or more") + " in " + region + ": choose the ones agents may read.");
    if (found.complete()) {
      Set<String> names = options.stream().map(Option::value).collect(Collectors.toSet());
      List<String> missing = configured.stream().filter(g -> !g.startsWith("arn:") && !names.contains(g)).toList();
      if (!missing.isEmpty()) {
        out.note((missing.size() == 1 ? "Log group " + missing.getFirst() + " isn't" : "Log groups "
            + String.join(", ", missing) + " aren't") + " in " + region + ".");
      }
    }
  }

  /** {@code kept 30 days · 12 MB}. */
  private static String detail(LogGroup g) {
    String kept = g.retentionInDays() == null ? "kept forever" : "kept " + g.retentionInDays() + " days";
    return g.storedBytes() == null ? kept : kept + " · " + g.storedBytes() / (1024 * 1024) + " MB";
  }
}
