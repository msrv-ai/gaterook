package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cloudcontrol.CloudControlClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.sts.model.StsException;

/**
 * What one AWS call works with: its connection and settings, the environment's Cloud policy as {@code classify} saw
 * it, the clients, the credentials service, the call's secrets and context. Every request carries the call's own
 * credentials — the session of AssumeRole with the generated read-only policy when the connection has a role — and
 * every key and token they resolve to joins the call's taint.
 */
record Call(ConnectionConfig cfg, Settings settings, AwsPolicy policy, Clients clients, CloudCredentials cloud,
    SecretResolver secrets, ExecContext ctx) {

  /** Cloud Control requests per minute of one account and region the gateway takes (5 TPS, report 09 §6). */
  static final int CLOUD_CONTROL_PER_MINUTE = 300;
  /** {@code GetMetricData} per minute (10 TPS); the other CloudWatch reads share 5 TPS. */
  static final int GET_METRIC_DATA_PER_MINUTE = 600;
  static final int CLOUDWATCH_PER_MINUTE = 300;
  /** Time kept for building the answer: no new page starts after it. */
  static final Duration LAST_PAGE = Duration.ofMillis(1_500);
  /** What an SDK call leaves the rest of the call: the core's answer must still fit before the deadline. */
  static final Duration MARGIN = Duration.ofMillis(300);

  CloudControlClient cloudControl(String region) {
    return clients.cloudControl(cfg, region, cloud.endpoints().service("aws.cloudcontrol"));
  }

  CloudWatchClient cloudWatch(String region) {
    return clients.cloudWatch(cfg, region, cloud.endpoints().service("aws.monitoring"));
  }

  /** The session policy of this call's AssumeRole, or {@code null} when nothing would carry it. */
  String sessionPolicy() throws ConnectorException {
    if (!settings.enforcedByAws()) {
      return null;
    }
    try {
      return SessionPolicyFactory.readOnly(policy.sessionServices());
    } catch (IllegalStateException e) {
      throw ConnectorException.badInput(e.getMessage());
    }
  }

  /** The call's credentials: every secret key and session token they resolve to joins the call's taint. */
  AwsCredentialsProvider credentials() throws ConnectorException {
    AwsCredentialsProvider base = cloud.aws(new CloudCredentials.Owner(cfg.id(), cfg.version(), cfg.name()),
        settings.credentials(), sessionPolicy(), secrets);
    return CloudCredentials.tainting(base, ctx::taint);
  }

  /**
   * Resolves {@code provider} now — the AssumeRole of a role connection — so a refusal of STS is told as such: the
   * trust policy, the ExternalId, the key.
   */
  static void resolve(AwsCredentialsProvider provider) throws ConnectorException {
    try {
      provider.resolveCredentials();
    } catch (SdkException e) {
      StsException sts = sts(e);
      if (sts != null) {
        throw explainSts(sts);
      }
      throw ConnectorException.unavailable("AWS credentials couldn't be loaded: " + e.getMessage(), e);
    }
  }

  /** The override of one request: the call's credentials and the time left before its deadline. */
  AwsRequestOverrideConfiguration override() throws ConnectorException {
    Duration left = ctx.remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout("AWS didn't answer in time.");
    }
    return AwsRequestOverrideConfiguration.builder().credentialsProvider(credentials()).apiCallTimeout(left).build();
  }

  /** One request from the quota every connection of the account and region shares, or a refusal. */
  void quota(String region, String api, int perMinute) throws ConnectorException {
    String key = ("aws:" + settings.quotaAccount() + ":" + region + ":" + api).toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9:._/-]", "-");
    if (!ctx.acquire(key, perMinute)) {
      throw ConnectorException.resourceError("GateRook's share of " + api + " calls for this account and region ("
          + perMinute + " per minute) is used up. Try again in a few seconds.", null);
    }
  }

  /** Whether another page fits before the deadline. */
  boolean timeForAnotherPage() {
    return ctx.remaining().compareTo(LAST_PAGE) > 0;
  }

  /** The bytes an answer may take; a quarter is left for masking and the core's own fields (as in Logs and GCP). */
  long budget() {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
  }

  static long utf8(String s) {
    return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
  }

  /** One request of the SDK. */
  @FunctionalInterface
  interface SdkCall<T> {
    T run() throws ConnectorException;
  }

  /**
   * One SDK request, its failure told in words: the operation, what AWS said and what to change.
   *
   * @param service {@code Cloud Control} or {@code CloudWatch}, for the texts
   */
  <T> T aws(String service, String operation, SdkCall<T> request) throws ConnectorException {
    try {
      return request.run();
    } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException e) {
      throw ConnectorException.timeout(service + " " + operation + " didn't answer in time.");
    } catch (StsException e) {
      // The call's credentials failed (AssumeRole while signing), not the service: say what to fix in STS terms.
      throw explainSts(e);
    } catch (AwsServiceException e) {
      throw explain(service, operation, e);
    } catch (SecurityException e) {
      throw ConnectorException.denied(e.getMessage());
    } catch (SdkException e) {
      StsException sts = sts(e);
      if (sts != null) {
        throw explainSts(sts);
      }
      String m = e.getMessage() == null ? "" : e.getMessage();
      throw ConnectorException.unavailable("Can't reach AWS " + service + ": " + m, e);
    }
  }

  private static StsException sts(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof StsException s) {
        return s;
      }
    }
    return null;
  }

  /** AssumeRole failed while signing: the trust policy, the ExternalId or the key — as Test connection says it. */
  static ConnectorException explainSts(StsException e) {
    String code = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
    return switch (code) {
      case "InvalidClientTokenId", "SignatureDoesNotMatch" -> ConnectorException.unavailable(
          "AWS refused the access key: it is wrong or deactivated. Replace it under Credentials.", e);
      case "AccessDenied" -> ConnectorException.unavailable("AWS denied AssumeRole: allow sts:AssumeRole for this "
          + "user and add the external ID to the role's trust policy.", e);
      case "PackedPolicyTooLarge" -> ConnectorException.unavailable("The session policy is too large for STS: allow "
          + "fewer services on this environment.", e);
      case "ExpiredToken" -> ConnectorException.unavailable("The AWS session expired; try again.", e);
      default -> ConnectorException.unavailable("AWS STS refused the request: " + e.getMessage(), e);
    };
  }

  private ConnectorException explain(String service, String operation, AwsServiceException e) {
    String code = e.awsErrorDetails() == null || e.awsErrorDetails().errorCode() == null ? ""
        : e.awsErrorDetails().errorCode();
    String message = e.awsErrorDetails() == null || e.awsErrorDetails().errorMessage() == null ? e.getMessage()
        : e.awsErrorDetails().errorMessage();
    String svc = service.equals("Cloud Control") ? "cloudformation" : "cloudwatch";
    return switch (code) {
      case "ThrottlingException", "Throttling", "TooManyRequestsException", "RequestLimitExceeded",
          "LimitExceededException", "LimitExceeded" -> ConnectorException.resourceError("AWS is throttling "
          + operation + ", try again in a few seconds.", e);
      case "AccessDeniedException", "AccessDenied", "UnauthorizedOperation", "UnauthorizedException" ->
          ConnectorException.resourceError("AWS denied " + operation + ": " + message + ". The role needs "
              + svc + ":" + operation + (svc.equals("cloudformation") ? " and the read permissions of the type's "
              + "handler (for example rds:DescribeDBInstances)" : "") + ".", e);
      case "UnrecognizedClientException", "InvalidClientTokenId", "InvalidSignatureException",
          "SignatureDoesNotMatch", "ExpiredTokenException", "ExpiredToken", "InvalidCredentialsException" ->
          ConnectorException.unavailable("AWS refused the credentials: " + message + ". Check them under "
              + "Credentials, then test again.", e);
      case "TypeNotFoundException" -> ConnectorException.badInput("AWS doesn't know this type in the region: "
          + message);
      case "UnsupportedActionException" -> ConnectorException.badInput(service + " can't " + (operation.equals(
          "ListResources") ? "list" : "read") + " this type: " + message + (operation.equals("ListResources")
          ? " Pass identifier to read one resource." : ""));
      case "ResourceNotFoundException" -> ConnectorException.resourceError("Not found: " + message, e);
      case "InvalidRequestException", "ValidationException", "InvalidParameterValue", "InvalidParameterValueException",
          "InvalidParameterCombination", "InvalidParameterCombinationException", "MissingParameter",
          "MissingRequiredParameterException", "InvalidNextToken", "InvalidNextTokenException" ->
          ConnectorException.badInput(service + " refused the request: " + message);
      case "PrivateTypeException" -> ConnectorException.badInput("Only public AWS types are read: " + message);
      case "GeneralServiceException", "HandlerFailureException", "HandlerInternalFailureException",
          "NotStabilizedException" -> ConnectorException.resourceError("The handler of the type failed: " + message
          + (message != null && message.toLowerCase(Locale.ROOT).contains("not authorized")
          ? " The role needs the read permissions of the type's handler." : ""), e);
      default -> e.statusCode() >= 500
          ? ConnectorException.unavailable(service + " " + operation + " failed: " + message, e)
          : ConnectorException.resourceError(service + " " + operation + ": " + message, e);
    };
  }
}
