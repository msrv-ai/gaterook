package ai.msrv.gaterook.connectors.cloudauth;

import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * AWS credentials of a connection, the {@code credentials} object of the AWS, CloudWatch and AWS SM forms (reports 07
 * §4, 09 §4, 12 §4.3): an access key (its secret half from the {@code SecretResolver}) or a profile of this computer,
 * optionally assuming {@link #roleArn} with {@link #externalId} for {@link #sessionSeconds} (900 s by default).
 *
 * @param secretKeyField JSON pointer of the {@code secretAccessKey} secret field
 * @param region         region of the STS endpoint (the connection's region or first region)
 */
public record AwsSpec(Source source, String accessKeyId, String secretKeyField, String profile, String roleArn,
    String externalId, int sessionSeconds, String region) {

  /** {@code credentials.source}. */
  public enum Source { ACCESS_KEY, PROFILE }

  private static final Pattern ACCESS_KEY_ID = Pattern.compile("(AKIA|ASIA)[A-Z0-9]{16}");
  private static final Pattern ROLE_ARN = Pattern.compile("arn:aws[a-z-]*:iam::\\d{12}:role/[\\w+=,.@/-]{1,512}");
  private static final Pattern EXTERNAL_ID = Pattern.compile("[\\w+=,.@:/-]{2,1224}");
  private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");

  public AwsSpec {
    Objects.requireNonNull(source, "source");
    if (region == null || !REGION.matcher(region).matches()) {
      throw new IllegalArgumentException("Not an AWS region: " + region);
    }
    if (source == Source.ACCESS_KEY) {
      if (accessKeyId == null || !ACCESS_KEY_ID.matcher(accessKeyId).matches()) {
        throw new IllegalArgumentException("The access key ID must look like AKIA… (20 characters)");
      }
      if (secretKeyField == null || secretKeyField.isBlank()) {
        throw new IllegalArgumentException("An access key needs its secret field");
      }
    } else if (profile == null || profile.isBlank()) {
      throw new IllegalArgumentException("A profile name is required");
    }
    if (roleArn != null && roleArn.isBlank()) {
      roleArn = null;
    }
    if (roleArn != null && !ROLE_ARN.matcher(roleArn).matches()) {
      throw new IllegalArgumentException("Not an IAM role ARN: " + roleArn);
    }
    if (externalId != null && externalId.isBlank()) {
      externalId = null;
    }
    if (externalId != null && !EXTERNAL_ID.matcher(externalId).matches()) {
      throw new IllegalArgumentException("The external ID has characters STS doesn't accept");
    }
    if (sessionSeconds < 900 || sessionSeconds > 3600) {
      throw new IllegalArgumentException("sessionSeconds must be 900-3600");
    }
  }

  /**
   * Reads {@code credentials} as the forms write it: {@code {source, accessKeyId, profile, roleArn, externalId,
   * sessionSeconds}}; the secret key is the secret field {@code <pointer>/secretAccessKey}.
   */
  public static AwsSpec fromConfig(JsonNode config, String pointer, String region) {
    JsonNode c = config.at(pointer);
    if (c.isMissingNode() || !c.isObject()) {
      throw new IllegalArgumentException("No AWS credentials at " + pointer);
    }
    Source source = switch (text(c, "source", "accessKey")) {
      case "accessKey" -> Source.ACCESS_KEY;
      case "profile" -> Source.PROFILE;
      default -> throw new IllegalArgumentException("Unknown AWS credentials source " + text(c, "source", ""));
    };
    return new AwsSpec(source, text(c, "accessKeyId", null),
        source == Source.ACCESS_KEY ? pointer + "/secretAccessKey" : null, text(c, "profile", null),
        text(c, "roleArn", null), text(c, "externalId", null), c.path("sessionSeconds").asInt(900), region);
  }

  /** What Secrets › Short-lived and Test connection show as the identity. */
  public String identity() {
    return roleArn != null ? roleArn : source == Source.PROFILE ? "profile " + profile : accessKeyId;
  }

  private static String text(JsonNode node, String field, String dflt) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || n.asString().isBlank() ? dflt : n.asString();
  }
}
