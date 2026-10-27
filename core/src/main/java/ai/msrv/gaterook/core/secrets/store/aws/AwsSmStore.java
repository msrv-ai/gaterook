package ai.msrv.gaterook.core.secrets.store.aws;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.AwsSessionPolicy;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import ai.msrv.gaterook.core.secrets.store.RemoteValue;
import ai.msrv.gaterook.core.secrets.store.SecretJson;
import ai.msrv.gaterook.core.secrets.store.SecretRef;
import ai.msrv.gaterook.core.secrets.store.SecretStore;
import ai.msrv.gaterook.core.secrets.store.StoreCall;
import ai.msrv.gaterook.core.secrets.store.StoreException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.DecryptionFailureException;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.InvalidRequestException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An AWS Secrets Manager store (report 12 §4.3, §6, §7.6): SDK v2 {@code secretsmanager} over the URL-connection
 * client (no Netty, no Apache), credentials from {@link CloudCredentials} — an access key or a profile, then
 * {@code AssumeRole} with the store's ExternalId and a session policy that allows only {@code GetSecretValue} on the
 * store's references, so even a broad role reads nothing else. The endpoint is explicit ({@code AWS_ENDPOINT_URL*}
 * can't move signed requests), and an interceptor stops any operation but {@code GetSecretValue} before the network.
 * Session keys of every request go to the call's taint.
 *
 * <p>{@code SecretString} is a {@code java.lang.String} in the SDK and can't be zeroed (report 12 §6); its bytes are
 * copied out at once and the response is dropped.
 */
public final class AwsSmStore implements SecretStore {

  /** The secret field of the access key's secret half. */
  public static final String SECRET_KEY = "/credentials/secretAccessKey";

  private static final Pattern REGION = Pattern.compile("[a-z]{2}(?:-[a-z]+)+-[0-9]+");
  private static final Set<String> OPERATIONS = Set.of("GetSecretValue");

  /**
   * @param endpointOverride test endpoint ({@code gateway.cloud.aws.secretsmanager-endpoint}, never in a release)
   */
  public record Settings(String region, AwsSpec credentials, int cacheTtlSeconds, URI endpointOverride) {

    public static Settings of(JsonNode config, URI endpointOverride) {
      String region = config.path("region").asString("").strip();
      if (!REGION.matcher(region).matches()) {
        throw StoreException.invalid("Enter the region of the secrets, like eu-central-1.");
      }
      AwsSpec spec;
      try {
        spec = AwsSpec.fromConfig(config, "/credentials", region);
      } catch (IllegalArgumentException e) {
        throw StoreException.invalid(e.getMessage());
      }
      int ttl = config.path("cacheTtlSeconds").asInt(60);
      if (ttl < 0 || ttl > 300) {
        throw StoreException.invalid("Keep a resolved value for 0 to 300 seconds.");
      }
      return new Settings(region, spec, ttl, endpointOverride);
    }
  }

  private final Settings settings;
  private final CloudCredentials cloud;
  private final CloudCredentials.Owner owner;
  private final Supplier<? extends SecretResolver> signIn;
  private final Supplier<List<SecretRef>> references;
  private final SdkHttpClient http;

  /**
   * @param references the references bound to this store now: the session policy names exactly them
   */
  public AwsSmStore(Settings settings, CloudCredentials cloud, CloudCredentials.Owner owner,
      Supplier<? extends SecretResolver> signIn, Supplier<List<SecretRef>> references) {
    this.settings = settings;
    this.cloud = cloud;
    this.owner = owner;
    this.signIn = signIn;
    this.references = references;
    this.http = UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
        .socketTimeout(Duration.ofSeconds(10)).build();
  }

  @Override
  public Duration cacheTtl() {
    return Duration.ofSeconds(settings.cacheTtlSeconds());
  }

  @Override
  public StoreKind kind() {
    return StoreKind.AWS_SM;
  }

  public Settings settings() {
    return settings;
  }

  @Override
  public RemoteValue read(SecretRef reference, StoreCall call) {
    SecretRef.AwsSm ref = (SecretRef.AwsSm) reference;
    byte[] value = null;
    try (SecretsManagerClient sm = client(ref, call)) {
      GetSecretValueRequest.Builder request = GetSecretValueRequest.builder().secretId(ref.secretId());
      if (ref.versionId() != null) {
        request.versionId(ref.versionId());
      } else if (ref.versionStage() != null) {
        request.versionStage(ref.versionStage());
      }
      GetSecretValueResponse response = sm.getSecretValue(request.build());
      boolean binary = response.secretString() == null;
      value = binary ? response.secretBinary().asByteArray() : response.secretString().getBytes(StandardCharsets.UTF_8);
      RemoteValue v = value(ref, value, binary);
      return new RemoteValue(v.value(), response.versionId(), response.createdDate(), v.type(), v.keys());
    } catch (StoreException e) {
      throw e;
    } catch (SecurityException e) {
      throw StoreException.invalid(e.getMessage());
    } catch (ResourceNotFoundException e) {
      throw StoreException.notFound("No secret " + ref.display() + " in AWS Secrets Manager (" + ref.region()
          + (ref.pinned() ? ", " + (ref.versionId() != null ? "version " + ref.versionId() : ref.versionStage()) : "")
          + ").");
    } catch (DecryptionFailureException e) {
      throw StoreException.denied(ref.display() + " can't be decrypted: the role needs kms:Decrypt on the secret's "
          + "key.");
    } catch (InvalidRequestException e) {
      throw StoreException.gone(ref.display() + " is scheduled for deletion, or the request doesn't fit it. Restore "
          + "the secret or point to another one.");
    } catch (SecretsManagerException e) {
      String code = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
      if ("AccessDeniedException".equals(code)) {
        throw StoreException.denied("The gateway's role can't read " + ref.display() + ": allow "
            + "secretsmanager:GetSecretValue on it.");
      }
      throw StoreException.unavailable("AWS Secrets Manager refused the request: " + code, e);
    } catch (SdkClientException e) {
      throw StoreException.unavailable("Can't reach AWS Secrets Manager in " + ref.region() + ": "
          + e.getMessage(), e);
    } catch (SdkException e) {
      throw StoreException.unavailable("AWS Secrets Manager failed: " + e.getMessage(), e);
    } finally {
      if (value != null) {
        Arrays.fill(value, (byte) 0);
      }
    }
  }

  private static RemoteValue value(SecretRef ref, byte[] value, boolean binary) {
    boolean object = !binary && SecretJson.isObject(value);
    List<String> keys = object ? SecretJson.pickTop(value, null).keys() : List.of();
    if (ref.key() == null) {
      return new RemoteValue(value.clone(), null, null, binary ? "binary" : object ? "json" : "string", keys);
    }
    if (!object) {
      throw StoreException.notFound(ref.display() + " isn't JSON, so it has no key \"" + ref.key()
          + "\". Remove #" + ref.key() + " from the reference.");
    }
    SecretJson.Picked picked = SecretJson.pickTop(value, ref.key());
    if (picked.value() == null) {
      throw StoreException.notFound(ref.display() + " has no key \"" + ref.key() + "\". Keys: "
          + String.join(", ", picked.keys()));
    }
    return new RemoteValue(picked.value(), null, null, picked.string() ? "string" : "json", picked.keys());
  }

  @Override
  public SecretStores.TestResult test(List<SecretRef> refs, StoreCall call) {
    long t0 = System.nanoTime();
    List<SecretStores.Check> checks = new ArrayList<>();
    List<SecretStores.Hint> hints = new ArrayList<>();
    List<SecretStores.Permission> permissions = new ArrayList<>();
    ObjectNode identity = JsonNodeFactory.instance.objectNode();
    identity.put("region", settings.region());
    identity.put("identity", settings.credentials().identity());
    if (settings.credentials().externalId() != null) {
      identity.put("externalId", settings.credentials().externalId());
    }
    try {
      long s = System.nanoTime();
      CloudCredentials.CallerIdentity who;
      try {
        who = cloud.callerIdentity(provider(refs, call), settings.region());
      } catch (ConnectorException e) {
        throw StoreException.unavailable(e.getMessage(), e);
      }
      identity.put("account", who.account());
      identity.put("arn", who.arn());
      checks.add(new SecretStores.Check("identity", true, ms(s), who.arn()));
      for (String w : cloud.profileWarnings(settings.credentials())) {
        hints.add(new SecretStores.Hint("warning", "store.profile", w));
      }
      String role = settings.credentials().roleArn();
      if (role == null) {
        hints.add(new SecretStores.Hint("warning", "store.no-role", "Without a role the access key reads as it is. "
            + "Assume a read-only role, so a session policy limits each session to the references."));
      } else if (role.toLowerCase(Locale.ROOT).contains("admin") || role.toLowerCase(Locale.ROOT)
          .contains("poweruser")) {
        hints.add(new SecretStores.Hint("warning", "store.broad-role", "The role looks broad. The session policy "
            + "limits it to reading the references, but give the gateway a role that can only read secrets."));
      }
      SecretRef first = refs.stream().filter(r -> r instanceof SecretRef.AwsSm).findFirst().orElse(null);
      if (first != null) {
        s = System.nanoTime();
        try (RemoteValue v = read(first, call)) {
          permissions.add(new SecretStores.Permission(first.uri(), List.of("secretsmanager:GetSecretValue")));
          checks.add(new SecretStores.Check("read", true, ms(s), first.display() + " resolves"));
        }
      }
      return new SecretStores.TestResult(true, ms(t0), null, identity, permissions, hints, checks, null, null);
    } catch (StoreException e) {
      checks.add(new SecretStores.Check("failed", false, ms(t0), e.getMessage()));
      return new SecretStores.TestResult(false, ms(t0), null, identity, permissions, hints, checks, e.getMessage(),
          e.reason().name().toLowerCase(Locale.ROOT));
    }
  }

  // ── Client ──────────────────────────────────────────────────────────────────────────────────────────────────

  private SecretsManagerClient client(SecretRef.AwsSm ref, StoreCall call) {
    List<SecretRef> scope = new ArrayList<>(references.get());
    scope.add(ref);
    Duration timeout = call.requestTimeout();
    return SecretsManagerClient.builder()
        .region(Region.of(ref.region()))
        .httpClient(http)
        .credentialsProvider(CloudCredentials.tainting(provider(scope, call), call.taint()))
        .endpointOverride(settings.endpointOverride() != null ? settings.endpointOverride() : endpoint(ref.region()))
        .overrideConfiguration(o -> o.addExecutionInterceptor(new OperationGuard()).apiCallTimeout(timeout)
            .retryStrategy(b -> b.maxAttempts(1)))
        .build();
  }

  private AwsCredentialsProvider provider(List<SecretRef> scope, StoreCall call) {
    SecretResolver secrets = signIn.get();
    try {
      return cloud.aws(owner, settings.credentials(), settings.credentials().roleArn() == null ? null
          : sessionPolicy(scope), secrets);
    } catch (ConnectorException e) {
      throw StoreException.unavailable(e.getMessage(), e);
    } finally {
      if (secrets instanceof AutoCloseable c) {
        try {
          c.close();
        } catch (Exception ignored) {
          // Zeroing never fails.
        }
      }
    }
  }

  /**
   * {@code GetSecretValue} on exactly the references (names match with AWS's six-character suffix), and
   * {@code kms:Decrypt} for secrets under a customer key; the role's own grants are the outer limit.
   */
  String sessionPolicy(List<SecretRef> scope) {
    String account = settings.credentials().roleArn() == null ? "*"
        : settings.credentials().roleArn().split(":")[4];
    Set<String> resources = new LinkedHashSet<>();
    for (SecretRef r : scope) {
      if (r instanceof SecretRef.AwsSm a) {
        resources.add(a.arn() != null ? a.arn() + "*" : "arn:" + partition(a.region()) + ":secretsmanager:"
            + a.region() + ":" + account + ":secret:" + a.name() + "-??????");
      }
    }
    if (resources.isEmpty()) {
      resources.add("arn:" + partition(settings.region()) + ":secretsmanager:" + settings.region() + ":" + account
          + ":secret:mcp-gateway-none");
    }
    return AwsSessionPolicy.builder().allow(List.of("secretsmanager:GetSecretValue"), List.copyOf(resources))
        .allow(List.of("kms:Decrypt"), List.of()).toJson();
  }

  private static String partition(String region) {
    return region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
  }

  private static URI endpoint(String region) {
    return URI.create("https://secretsmanager." + region + "." + (region.startsWith("cn-") ? "amazonaws.com.cn"
        : "amazonaws.com"));
  }

  private static long ms(long since) {
    return (System.nanoTime() - since) / 1_000_000;
  }

  @Override
  public void close() {
    cloud.evict(owner.id());
    http.close();
  }

  /** Only {@code GetSecretValue} leaves the core; anything else fails before the network. */
  static final class OperationGuard implements ExecutionInterceptor {
    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
      String operation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
      if (!OPERATIONS.contains(operation)) {
        throw new SecurityException("Blocked secretsmanager." + operation + ": MCP Gateway only reads secrets");
      }
    }
  }
}
