package com.gaterook.connectors.aws;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the AWS API tools ({@code aws_api_read}, {@code aws_api_change}) do with an operation, decided from the model's
 * facts and this code, never from the catalog alone ("This is code, not policy", as in {@link Services}). In order,
 * first match wins:
 *
 * <ol>
 *   <li>refused: operations that issue credentials or tokens or return secret values (all of STS, SSO, SSO OIDC,
 *       Sign-In, Cognito Identity and EKS Auth; unauthenticated end-user flows; names that say so, such as
 *       {@code GetAuthorizationToken} or {@code CreatePresignedDomainUrl}; the explicit table), the data plane of
 *       stores that have their own tools or hold customer content ({@link #DATA_PLANE}, {@code @aws.api#dataPlane}),
 *       and what the request layer can't send (streaming bodies, endpoint discovery, required checksums);</li>
 *   <li>a read: {@code @smithy.api#readonly}, else a read verb ({@code Describe}, {@code List}, {@code Get},
 *       {@code BatchGet}, {@code Search}, {@code Lookup}), else one of the reads {@link #READS} names;</li>
 *   <li>a change: everything else, which a person approves exactly as it will be sent.</li>
 * </ol>
 */
final class ApiRules {

  /** How a call goes: at once, or after a person approved its exact request. */
  enum Access { READ, CHANGE }

  /** A refusal of an operation: the rule's id (after {@code <env>.cloud.}), what the agent is told and a hint. */
  record Denial(String rule, String reason, String hint) {}

  private static final Pattern READ_VERB = Pattern.compile("^(Describe|List|Get|BatchGet|Search|Lookup)([A-Z0-9].*)?$");
  /**
   * Names of operations that hand out a credential, a token or a URL that works as one: {@code GetAuthorizationToken},
   * {@code GetClusterCredentialsWithIAM}, {@code CreateAccessKey}, {@code CreatePresignedDomainUrl},
   * {@code GenerateEmbedUrlForAnonymousUser}, {@code GetInstanceAccessDetails}. A WAF Classic change token is not one.
   */
  private static final Pattern CREDENTIAL_NAME = Pattern.compile("^(Get|Create|Generate|Request|Issue|Download|Reset)"
      + "\\w*?(Credentials?|Token|Password|AccessKey|KeyPair|Presigned\\w*Url|Presigned\\w*URL|EmbedUrl\\w*|"
      + "StreamingURL|StreamingUrl|LoginUrl|AccessDetails|StreamKey)(For\\w+|With\\w+)?$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern ASSUME_ROLE = Pattern.compile("^Assume\\w*Role\\w*$");

  /** Services whose every operation issues credentials or tokens. */
  static final Map<String, String> CREDENTIAL_SERVICES = Map.of(
      "sts", "STS issues credentials",
      "sso", "the IAM Identity Center portal API issues role credentials",
      "sso-oidc", "SSO OIDC issues tokens",
      "signin", "Sign-In issues tokens",
      "cognito-identity", "Cognito Identity issues credentials for identities",
      "eks-auth", "EKS Auth issues pod credentials");

  /** Operations that return a secret value, issue a credential, or set one the agent would know. */
  static final Map<String, Set<String>> CREDENTIAL_OPERATIONS = Map.ofEntries(
      Map.entry("secretsmanager", Set.of("GetSecretValue", "BatchGetSecretValue")),
      Map.entry("ssm", Set.of("GetParameter", "GetParameters", "GetParametersByPath", "GetParameterHistory",
          "StartSession")),
      Map.entry("kms", Set.of("Decrypt", "GenerateDataKey", "DeriveSharedSecret", "Decapsulate", "Encapsulate")),
      Map.entry("ec2", Set.of("GetPasswordData", "GetConsoleOutput", "GetConsoleScreenshot")),
      Map.entry("iam", Set.of("CreateLoginProfile", "UpdateLoginProfile", "ChangePassword",
          "CreateVirtualMFADevice")),
      Map.entry("iot", Set.of("CreateKeysAndCertificate", "CreateProvisioningClaim")),
      Map.entry("gamelift", Set.of("GetInstanceAccess", "GetComputeAccess")),
      Map.entry("apigateway", Set.of("CreateApiKey")),
      Map.entry("appsync", Set.of("CreateApiKey")),
      Map.entry("cognito-idp", Set.of("AdminInitiateAuth", "AdminRespondToAuthChallenge")),
      Map.entry("emr-serverless", Set.of("GetDashboardForJobRun")),
      Map.entry("redshift", Set.of("GetClusterCredentials", "GetClusterCredentialsWithIAM")),
      Map.entry("redshift-serverless", Set.of("GetCredentials")),
      Map.entry("codeartifact", Set.of("GetAuthorizationToken")),
      Map.entry("ecr", Set.of("GetAuthorizationToken")),
      Map.entry("ecr-public", Set.of("GetAuthorizationToken")),
      Map.entry("lightsail", Set.of("GetInstanceAccessDetails", "GetRelationalDatabaseMasterUserPassword",
          "DownloadDefaultKeyPair")));

  /** Data plane: services and operations whose answers are the content of a store, a model or a stream. */
  static final Map<String, String> DATA_PLANE_SERVICES = Map.ofEntries(
      Map.entry("dynamodbstreams", "DynamoDB stream records"),
      Map.entry("kinesis-video-media", "video streams"),
      Map.entry("kinesis-video-archived-media", "video streams"),
      Map.entry("kinesis-video-signaling", "video signaling"),
      Map.entry("kinesis-video-webrtc-storage", "video streams"),
      Map.entry("sagemaker-runtime", "model invocations"),
      Map.entry("sagemaker-runtime-http2", "model invocations"),
      Map.entry("sagemaker-a2i-runtime", "human review data"),
      Map.entry("sagemaker-featurestore-runtime", "feature records"),
      Map.entry("bedrock-runtime", "model invocations"),
      Map.entry("bedrock-agent-runtime", "agent and knowledge base invocations"),
      Map.entry("bedrock-agentcore", "agent runtime calls and workload tokens"),
      Map.entry("bedrock-data-automation-runtime", "document processing"),
      Map.entry("lex-runtime", "bot conversations"),
      Map.entry("lex-runtime-v2", "bot conversations"),
      Map.entry("personalize-runtime", "recommendations"),
      Map.entry("personalize-events", "event data"),
      Map.entry("rds-data", "SQL statements"),
      Map.entry("redshift-data", "SQL statements"),
      Map.entry("timestream-query", "queries"),
      Map.entry("timestream-write", "records"),
      Map.entry("neptunedata", "graph queries"),
      Map.entry("cloudsearch-domain", "search documents"),
      Map.entry("appconfigdata", "configuration data"),
      Map.entry("iot-data", "device shadows and messages"),
      Map.entry("iot-jobs-data", "device jobs"),
      Map.entry("mediastore-data", "media objects"),
      Map.entry("ebs", "snapshot blocks"),
      Map.entry("apigatewaymanagementapi", "WebSocket connections"),
      Map.entry("connectparticipant", "contact chats"),
      Map.entry("workmailmessageflow", "email messages"),
      Map.entry("chime-sdk-messaging", "chat messages"),
      Map.entry("forecastquery", "forecasts"),
      Map.entry("keyspacesstreams", "stream records"),
      Map.entry("payment-cryptography-data", "cryptographic operations"),
      Map.entry("cloudtrail-data", "audit events"),
      Map.entry("s3vectors", "vector data"));

  /** Data-plane operations of control-plane services, with the tool to use instead where there is one. */
  static final Map<String, Map<String, String>> DATA_PLANE_OPERATIONS = Map.ofEntries(
      Map.entry("dynamodb", ops("dynamodb_query, dynamodb_scan, dynamodb_partiql or dynamodb_execute", "GetItem",
          "BatchGetItem", "Query", "Scan", "ExecuteStatement", "BatchExecuteStatement", "ExecuteTransaction",
          "TransactGetItems", "TransactWriteItems", "PutItem", "UpdateItem", "DeleteItem", "BatchWriteItem")),
      Map.entry("kinesis", ops(null, "GetRecords", "GetShardIterator", "SubscribeToShard")),
      Map.entry("logs", ops("logs_search", "GetLogEvents", "FilterLogEvents", "StartQuery", "GetQueryResults",
          "StartLiveTail", "GetLogRecord", "GetLogObject")),
      Map.entry("lambda", ops(null, "Invoke", "InvokeAsync", "InvokeWithResponseStream")),
      Map.entry("athena", ops(null, "GetQueryResults", "StartQueryExecution", "StartCalculationExecution",
          "StartSession")),
      Map.entry("ecr", ops(null, "GetDownloadUrlForLayer")),
      Map.entry("stepfunctions", ops(null, "GetActivityTask")),
      Map.entry("swf", ops(null, "PollForActivityTask", "PollForDecisionTask")),
      Map.entry("appconfig", ops(null, "GetConfiguration", "GetHostedConfigurationVersion")),
      Map.entry("glacier", ops(null, "GetJobOutput")),
      Map.entry("kendra", ops(null, "Query", "Retrieve")),
      Map.entry("qbusiness", ops(null, "Chat", "ChatSync", "SearchRelevantContent")));

  /** Reads the model doesn't mark and whose verb isn't a read verb: simulations, tests, validations, estimates. */
  static final Map<String, Set<String>> READS = Map.of(
      "iam", Set.of("SimulatePrincipalPolicy", "SimulateCustomPolicy"),
      "route53", Set.of("TestDNSAnswer"),
      "events", Set.of("TestEventPattern"),
      "logs", Set.of("TestMetricFilter", "TestTransformer"),
      "cloudformation", Set.of("ValidateTemplate", "EstimateTemplateCost"),
      "rds", Set.of("DownloadDBLogFilePortion"),
      "config", Set.of("SelectResourceConfig", "SelectAggregateResourceConfig"));

  /**
   * What a change does beyond its name, for the person who approves it: a receive from a queue is no read, since it
   * hides the messages and counts the receive (§5.2 of the cloud research: queue peeks that change state).
   */
  static final Map<String, Map<String, String>> EFFECTS = Map.of(
      "sqs", Map.of("ReceiveMessage", "hides the messages it returns from other consumers for the visibility timeout "
          + "and raises their receive count; on a queue with a redrive policy, a message past maxReceiveCount moves to "
          + "its dead-letter queue"));

  /** What {@code op} does beyond its name, or {@code null}. */
  static String effect(String service, ApiModel.Operation op) {
    return EFFECTS.getOrDefault(service, Map.of()).get(op.name());
  }

  /** Reads by their verb that change state or start work: a person approves them. */
  static final Map<String, Set<String>> CHANGES = Map.of(
      "elasticbeanstalk", Set.of("RetrieveEnvironmentInfo"));

  private ApiRules() {}

  private static Map<String, String> ops(String instead, String... names) {
    Map<String, String> out = new java.util.HashMap<>();
    for (String n : names) {
      out.put(n, instead == null ? "" : instead);
    }
    return Map.copyOf(out);
  }

  /** Why the AWS API tools never send {@code op} of {@code service}, or {@code null}. */
  static Denial denial(String service, ApiModel.Operation op) {
    String name = op.name();
    String what = service + " " + name;
    String credentials = CREDENTIAL_SERVICES.get(service);
    if (credentials != null) {
      return new Denial("credential_issuing", what + " isn't sent: " + credentials + ", and agents never get "
          + "credentials.", null);
    }
    if (op.noAuth()) {
      return new Denial("credential_issuing", what + " is an unauthenticated end-user call (sign-in, tokens, user "
          + "sessions); agents don't make it.", null);
    }
    if (CREDENTIAL_OPERATIONS.getOrDefault(service, Set.of()).contains(name)
        || (CREDENTIAL_NAME.matcher(name).matches() && !name.endsWith("ChangeToken"))
        || ASSUME_ROLE.matcher(name).matches()) {
      return new Denial("credential_issuing", what + " returns a secret value or issues a credential, so it isn't "
          + "sent. Agents see names and metadata, never secrets.", null);
    }
    String store = DATA_PLANE_SERVICES.get(service);
    if (store != null) {
      return new Denial("data_plane", what + " reads or writes " + store + ", the data plane, which the AWS API tools "
          + "doesn't reach.", null);
    }
    String instead = DATA_PLANE_OPERATIONS.getOrDefault(service, Map.of()).get(name);
    if (instead != null) {
      return new Denial("data_plane", what + " is a data-plane call, which the AWS API tools don't make.",
          instead.isEmpty() ? null : "Use " + instead + " instead.");
    }
    if (op.dataPlane()) {
      return new Denial("data_plane", what + " is a data-plane call (the model marks it so), which the AWS API tools "
          + "don't make.", null);
    }
    if (!op.streaming().isEmpty()) {
      return new Denial("unsupported", what + " streams its " + (op.streaming().contains("in") ? "input" : "output")
          + ", which the AWS API tools don't send or read.", null);
    }
    if (op.discovery()) {
      return new Denial("unsupported", what + " needs endpoint discovery, which the AWS API tools don't do.", null);
    }
    if (op.checksum() || op.unsignedPayload()) {
      return new Denial("unsupported", what + " needs a request checksum or an unsigned payload, which the AWS API "
          + "tools don't send.", null);
    }
    return null;
  }

  /** Whether {@code op} reads (runs at once) or changes something (a person approves it first). */
  static Access access(String service, ApiModel.Operation op) {
    String name = op.name();
    if (CHANGES.getOrDefault(service, Set.of()).contains(name)) {
      return Access.CHANGE;
    }
    if (op.readonly() || READ_VERB.matcher(name).matches() || READS.getOrDefault(service, Set.of()).contains(name)) {
      return Access.READ;
    }
    return Access.CHANGE;
  }

  /** Whether {@code op}'s IAM action is covered by the read wildcards of the per-service session policy. */
  static boolean coveredByReadWildcards(ApiModel.Operation op) {
    return READ_VERB.matcher(op.action()).matches();
  }

  /** The read wildcards of one service's session policy ({@code ecs:Describe*} …). */
  static List<String> readWildcards(String iam) {
    return List.of(iam + ":Describe*", iam + ":List*", iam + ":Get*", iam + ":BatchGet*", iam + ":Search*",
        iam + ":Lookup*");
  }

  /** The operations of {@code service} this class refuses whatever their verb: the session policy denies them too. */
  static Set<String> deniedOperations(String service) {
    java.util.Set<String> out = new java.util.TreeSet<>(CREDENTIAL_OPERATIONS.getOrDefault(service, Set.of()));
    out.addAll(DATA_PLANE_OPERATIONS.getOrDefault(service, Map.of()).keySet());
    return out;
  }
}
