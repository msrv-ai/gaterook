package ai.msrv.gaterook.connectors.cloudauth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.auth.oauth2.UserCredentials;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.profiles.Profile;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Short-lived cloud credentials for the Logs, GCP and AWS connectors and for the core's GCP and AWS secret stores
 * (reports 07 §7, 09 §7, 12 §7.6; ARCHITECTURE.md §2.1).
 *
 * <ul>
 *   <li>{@link #gcp}: Google sign-in on this computer (ADC: user, service account or impersonated, never the metadata
 *       server or external accounts) or a service account key, then {@code ImpersonatedCredentials} for 900 s when a
 *       service account is named;</li>
 *   <li>{@link #aws}: an access key or a profile of this computer, then {@code AssumeRole} with ExternalId and the
 *       caller's session policy; never {@code DefaultCredentialsProvider} (environment, instance metadata).</li>
 * </ul>
 *
 * <p>Credentials live in memory only, cached per owner and config version (a new version, a rotated key or a changed
 * policy makes new ones; {@link #evict} drops them). Values the gateway derives — access tokens, session keys — are
 * secrets too: callers hand them to the call's taint through {@link #requestMetadata} and {@link #tainting}, so the
 * masker removes any echo. No Spring, no global state: {@code UrlConnectionHttpClient} and NetHttpTransport per
 * instance, explicit STS endpoints so {@code AWS_ENDPOINT_URL*} can't redirect signed requests.
 */
public final class CloudCredentials implements AutoCloseable {

  /** {@code https://www.googleapis.com/auth/cloud-platform}: the only scope Cloud Asset accepts (report 09 §1). */
  public static final String CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform";

  /** Google's token endpoint, and the older addresses of it that keys and sign-ins from before 2019 still name. */
  private static final Set<URI> GOOGLE_TOKEN_URIS = Set.of(URI.create("https://oauth2.googleapis.com/token"),
      URI.create("https://accounts.google.com/o/oauth2/token"),
      URI.create("https://www.googleapis.com/oauth2/v4/token"));
  /** The only impersonation endpoint an impersonated sign-in may name (path of the decoded URL). */
  private static final Pattern IMPERSONATION_PATH =
      Pattern.compile("/v1/projects/-/serviceAccounts/([^/]+):generateAccessToken");
  /** What this class asks STS for; federation and session tokens would be added with the code that uses them. */
  private static final Set<String> STS_OPERATIONS = Set.of("AssumeRole", "GetCallerIdentity");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Whose credentials these are.
   *
   * @param id      stable id (connection or store id): {@link #evict} key
   * @param version config version: a new version gets new credentials
   * @param label   what people call it ({@code prod-logs}): Secrets › Short-lived, AWS role session name
   */
  public record Owner(String id, long version, String label) {
    public Owner {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(label, "label");
    }
  }

  /** {@code GetCallerIdentity}: account, ARN ({@code …:assumed-role/gw-readonly/mcp-gateway-aws-stage}), user id. */
  public record CallerIdentity(String account, String arn, String userId) {}

  private final CloudEndpoints endpoints;
  private final Clock clock;
  private final SdkHttpClient awsHttp;
  private final Map<String, GcpEntry> gcp = new ConcurrentHashMap<>();
  private final Map<String, AwsEntry> aws = new ConcurrentHashMap<>();

  public CloudCredentials(CloudEndpoints endpoints, Clock clock) {
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.awsHttp = UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
        .socketTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * The endpoints these credentials go to; a connector asks it where its cloud API is
   * ({@link CloudEndpoints#service}), so a test API is only ever used together with test credentials.
   */
  public CloudEndpoints endpoints() {
    return endpoints;
  }

  // ── GCP ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Google credentials for {@code owner}: ADC or the key from {@code secrets}, impersonating when the spec says so.
   *
   * @param scopes OAuth scopes of the impersonated token or of the key ({@link #CLOUD_PLATFORM} for most APIs)
   * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} when there is no usable sign-in or key, with what to do
   */
  public GoogleCredentials gcp(Owner owner, GcpSpec spec, List<String> scopes, SecretResolver secrets)
      throws ConnectorException {
    String secretVersion = spec.source() == GcpSpec.Source.SERVICE_ACCOUNT_KEY
        ? String.valueOf(secrets.meta(spec.keyField()).version()) : "";
    String key = owner.id() + "|" + owner.version() + "|" + spec + "|" + scopes + "|" + secretVersion;
    GcpEntry cached = gcp.get(key);
    if (cached != null) {
      return cached.credentials();
    }
    GoogleCredentials source = gcpSource(spec, secrets);
    GoogleCredentials credentials;
    if (spec.impersonate() != null) {
      ImpersonatedCredentials.Builder builder = ImpersonatedCredentials.newBuilder()
          .setSourceCredentials(source.createScopedRequired() ? source.createScoped(List.of(CLOUD_PLATFORM)) : source)
          .setTargetPrincipal(spec.impersonate())
          .setDelegates(spec.delegates())
          .setScopes(List.copyOf(scopes))
          .setLifetime(spec.lifetimeSeconds());
      if (spec.quotaProject() != null) {
        builder.setQuotaProjectId(spec.quotaProject());
      }
      if (endpoints.gcpIamEndpoint() != null) {
        builder.setIamEndpointOverride(String.format(endpoints.gcpIamEndpoint(), spec.impersonate()));
      }
      credentials = builder.build();
    } else {
      credentials = source.createScopedRequired() ? source.createScoped(scopes) : source;
      if (spec.quotaProject() != null) {
        credentials = credentials.createWithQuotaProject(spec.quotaProject());
      }
    }
    GcpEntry entry = new GcpEntry(owner, spec, credentials, new IssuedClock());
    credentials.addChangeListener(changed -> entry.issued().at = clock.instant());
    GcpEntry raced = gcp.putIfAbsent(key, entry);
    return raced != null ? raced.credentials() : credentials;
  }

  /**
   * The request headers of a Google API call ({@code Authorization}, {@code x-goog-user-project}); refreshes the
   * token when needed and hands the token to {@code taint}.
   *
   * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} when no token can be had, with the IAM fix to apply
   */
  public static Map<String, List<String>> requestMetadata(GoogleCredentials credentials, URI uri,
      Consumer<CharSequence> taint) throws ConnectorException {
    try {
      Map<String, List<String>> headers = credentials.getRequestMetadata(uri);
      for (String value : headers.getOrDefault("Authorization", List.of())) {
        taint.accept(value.startsWith("Bearer ") ? value.substring(7) : value);
      }
      return headers;
    } catch (IOException e) {
      throw explainGoogle(e, credentials);
    }
  }

  private GoogleCredentials gcpSource(GcpSpec spec, SecretResolver secrets) throws ConnectorException {
    if (spec.source() == GcpSpec.Source.SERVICE_ACCOUNT_KEY) {
      try (SecretResolver.Secret key = secrets.secret(spec.keyField())) {
        byte[] json = key.bytes();
        try {
          return serviceAccount(json, "The service account key");
        } finally {
          java.util.Arrays.fill(json, (byte) 0);
        }
      }
    }
    Path file = adcFile();
    byte[] json;
    try {
      json = Files.readAllBytes(file);
    } catch (NoSuchFileException e) {
      throw ConnectorException.unavailable("No Google sign-in on this computer. Run gcloud auth "
          + "application-default login, then test again.", null);
    } catch (IOException e) {
      throw ConnectorException.unavailable("Can't read the Google sign-in at " + file + ": " + e.getMessage(), e);
    }
    String type;
    try {
      JsonNode tree = JSON.readTree(json);
      type = tree.path("type").asString("");
    } catch (RuntimeException e) {
      throw ConnectorException.unavailable("The Google sign-in at " + file + " isn't valid JSON. Run gcloud auth "
          + "application-default login again.", null);
    }
    try {
      return switch (type) {
        case "authorized_user" -> user(json, "The Google sign-in");
        case "service_account" -> serviceAccount(json, "The Google sign-in");
        case "impersonated_service_account" -> impersonatedSignIn(JSON.readTree(json), file);
        default -> throw ConnectorException.unavailable("The Google sign-in on this computer is of type \""
            + type + "\", which MCP Gateway doesn't use (it would run programs or ask the metadata server). Run "
            + "gcloud auth application-default login.", null);
      };
    } catch (IOException e) {
      throw ConnectorException.unavailable("The Google sign-in at " + file + " can't be used: " + e.getMessage()
          + ". Run gcloud auth application-default login again.", e);
    } finally {
      java.util.Arrays.fill(json, (byte) 0);
    }
  }

  /** A user sign-in whose refresh token goes to Google only. */
  private UserCredentials user(byte[] json, String what) throws ConnectorException, IOException {
    requireGoogleUniverse(json, what);
    JsonNode tokenUri = JSON.readTree(json).get("token_uri");
    if (tokenUri != null && !tokenUri.isNull()) {
      requireGoogleTokenUri(tokenUri.asString(), what);
    }
    UserCredentials user = UserCredentials.fromStream(new ByteArrayInputStream(json));
    return endpoints.gcpTokenUri() == null ? user : user.toBuilder().setTokenServerUri(endpoints.gcpTokenUri()).build();
  }

  /**
   * {@code gcloud auth application-default login --impersonate-service-account}: built here rather than by
   * {@code GoogleCredentials.fromStream}, which would take the file's IAM endpoint and its source's {@code token_uri}
   * as they are. The impersonation URL must be Google's, the source a user sign-in or a key that passes the same
   * checks as on their own.
   */
  private GoogleCredentials impersonatedSignIn(JsonNode tree, Path file) throws ConnectorException, IOException {
    String what = "The Google sign-in at " + file;
    String url = tree.path("service_account_impersonation_url").asString("");
    String target = null;
    try {
      URI uri = URI.create(url);
      Matcher path = uri.getPath() == null ? null : IMPERSONATION_PATH.matcher(uri.getPath());
      if ("https".equals(uri.getScheme()) && "iamcredentials.googleapis.com".equals(uri.getHost())
          && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null && uri.getQuery() == null
          && uri.getFragment() == null && path != null && path.matches()) {
        target = path.group(1);
      }
    } catch (IllegalArgumentException e) {
      // Not a URL: refused below.
    }
    if (target == null) {
      throw ConnectorException.unavailable(what + " impersonates through " + host(url) + " instead of "
          + "iamcredentials.googleapis.com, so MCP Gateway won't use it. Run gcloud auth application-default login "
          + "again.", null);
    }
    JsonNode source = tree.path("source_credentials");
    byte[] sourceJson = JSON.writeValueAsBytes(source);
    try {
      GoogleCredentials signIn = switch (source.path("type").asString("")) {
        case "authorized_user" -> user(sourceJson, what);
        case "service_account" -> serviceAccount(sourceJson, what);
        default -> throw ConnectorException.unavailable(what + " impersonates with a sign-in of type \""
            + source.path("type").asString("") + "\", which MCP Gateway doesn't use. Run gcloud auth "
            + "application-default login again.", null);
      };
      List<String> delegates = new ArrayList<>();
      tree.path("delegates").forEach(d -> delegates.add(d.asString()));
      ImpersonatedCredentials.Builder builder = ImpersonatedCredentials.newBuilder()
          .setSourceCredentials(signIn.createScopedRequired() ? signIn.createScoped(List.of(CLOUD_PLATFORM)) : signIn)
          .setTargetPrincipal(target)
          .setDelegates(delegates)
          .setScopes(new ArrayList<>())
          // google-auth's own default for this file type: gcloud users get the token lifetime they know.
          .setLifetime(3600);
      String quotaProject = tree.path("quota_project_id").asString("");
      if (!quotaProject.isBlank()) {
        builder.setQuotaProjectId(quotaProject);
      }
      if (endpoints.gcpIamEndpoint() != null) {
        builder.setIamEndpointOverride(String.format(endpoints.gcpIamEndpoint(), target));
      }
      return builder.build();
    } finally {
      java.util.Arrays.fill(sourceJson, (byte) 0);
    }
  }

  private static void requireGoogleTokenUri(String tokenUri, String what) throws ConnectorException {
    URI uri;
    try {
      uri = URI.create(tokenUri);
    } catch (IllegalArgumentException e) {
      uri = null;
    }
    if (uri == null || !GOOGLE_TOKEN_URIS.contains(uri)) {
      // A token_uri elsewhere would get the refresh token or a signed assertion.
      throw ConnectorException.unavailable(what + " sends its token requests to " + host(tokenUri)
          + " instead of oauth2.googleapis.com, so MCP Gateway won't use it", null);
    }
  }

  private static String host(String url) {
    try {
      String host = URI.create(url).getHost();
      return host == null ? "\"" + url + "\"" : host;
    } catch (IllegalArgumentException e) {
      return "\"" + url + "\"";
    }
  }

  /**
   * A key or sign-in of Google's own universe ({@code googleapis.com}): google-auth builds the IAM address of an
   * impersonation and the audience of a self-signed token from {@code universe_domain}, so another universe would send
   * them elsewhere — the same leak as a {@code token_uri} elsewhere ({@link #requireGoogleTokenUri}).
   */
  private static void requireGoogleUniverse(byte[] json, String what) throws ConnectorException {
    String universe;
    try {
      universe = JSON.readTree(json).path("universe_domain").asString("");
    } catch (RuntimeException e) {
      return; // Not JSON: the parser refuses it next.
    }
    if (!universe.isBlank() && !universe.equals("googleapis.com")) {
      throw ConnectorException.unavailable(what + " belongs to the universe " + host("https://" + universe)
          + " instead of googleapis.com, so MCP Gateway won't use it", null);
    }
  }

  private ServiceAccountCredentials serviceAccount(byte[] json, String what) throws ConnectorException {
    requireGoogleUniverse(json, what);
    ServiceAccountCredentials key;
    try {
      key = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(json));
    } catch (IOException | RuntimeException e) {
      throw ConnectorException.unavailable(what + " isn't a valid service account key (JSON)", null);
    }
    URI tokenUri = key.getTokenServerUri();
    if (tokenUri != null) {
      requireGoogleTokenUri(tokenUri.toString(), what);
    }
    return endpoints.gcpTokenUri() == null ? key : key.toBuilder().setTokenServerUri(endpoints.gcpTokenUri()).build();
  }

  private Path adcFile() {
    if (endpoints.gcpAdcFile() != null) {
      return endpoints.gcpAdcFile();
    }
    String env = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
    if (env != null && !env.isBlank()) {
      return Path.of(env);
    }
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("windows") && System.getenv("APPDATA") != null) {
      return Path.of(System.getenv("APPDATA"), "gcloud", "application_default_credentials.json");
    }
    return Path.of(System.getProperty("user.home"), ".config", "gcloud", "application_default_credentials.json");
  }

  private static ConnectorException explainGoogle(IOException e, GoogleCredentials credentials) {
    String text = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : " " + e.getCause().getMessage());
    if (credentials instanceof ImpersonatedCredentials impersonated
        && (text.contains("403") || text.contains("PERMISSION_DENIED"))) {
      return ConnectorException.unavailable("Google refused to issue a token for " + impersonated.getAccount()
          + ". Grant yourself roles/iam.serviceAccountTokenCreator on it, then test again.", e);
    }
    if (text.contains("invalid_grant") || text.contains("invalid_rapt") || text.contains("reauth")) {
      return ConnectorException.unavailable("The Google sign-in on this computer has expired. Run gcloud auth "
          + "application-default login, then test again.", e);
    }
    return ConnectorException.unavailable("Can't get a Google access token: " + e.getMessage(), e);
  }

  // ── AWS ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * AWS credentials for {@code owner}: the access key from {@code secrets} or a profile, then AssumeRole with the
   * spec's ExternalId for {@code sessionSeconds} restricted by {@code sessionPolicy}.
   *
   * @param sessionPolicy inline session policy JSON ({@link AwsSessionPolicy}), or {@code null} for the role as it is
   * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} when the profile can't be used
   */
  public AwsCredentialsProvider aws(Owner owner, AwsSpec spec, String sessionPolicy, SecretResolver secrets)
      throws ConnectorException {
    if (sessionPolicy != null && sessionPolicy.length() > AwsSessionPolicy.MAX_CHARACTERS) {
      throw ConnectorException.badInput("The session policy is longer than STS accepts");
    }
    String secretVersion = spec.source() == AwsSpec.Source.ACCESS_KEY
        ? String.valueOf(secrets.meta(spec.secretKeyField()).version()) : "";
    String key = owner.id() + "|" + owner.version() + "|" + spec + "|" + sessionPolicy + "|" + secretVersion;
    AwsEntry cached = aws.get(key);
    if (cached != null) {
      return cached.provider();
    }
    AwsCredentialsProvider base = awsBase(spec, secrets);
    AwsEntry entry;
    if (spec.roleArn() == null) {
      entry = new AwsEntry(owner, spec, base, null, null, new IssuedClock());
    } else {
      StsClient sts = stsClient(spec.region(), base);
      String sessionName = sessionName(owner.label());
      StsAssumeRoleCredentialsProvider assume = StsAssumeRoleCredentialsProvider.builder()
          .stsClient(sts)
          .asyncCredentialUpdateEnabled(false)
          .refreshRequest(r -> {
            r.roleArn(spec.roleArn()).roleSessionName(sessionName).durationSeconds(spec.sessionSeconds());
            if (spec.externalId() != null) {
              r.externalId(spec.externalId());
            }
            if (sessionPolicy != null) {
              r.policy(sessionPolicy);
            }
          })
          .build();
      IssuedClock issued = new IssuedClock();
      AwsCredentialsProvider recording = new RecordingProvider(assume, issued, clock);
      entry = new AwsEntry(owner, spec, recording, sts, assume, issued);
    }
    AwsEntry raced = aws.putIfAbsent(key, entry);
    if (raced != null) {
      entry.close();
      return raced.provider();
    }
    return entry.provider();
  }

  /** A view of {@code provider} that hands the secret key and session token of every resolution to {@code taint}. */
  public static AwsCredentialsProvider tainting(AwsCredentialsProvider provider, Consumer<CharSequence> taint) {
    return () -> {
      AwsCredentials credentials = provider.resolveCredentials();
      taint.accept(credentials.secretAccessKey());
      if (credentials instanceof AwsSessionCredentials session) {
        taint.accept(session.sessionToken());
      }
      return credentials;
    };
  }

  /**
   * {@code sts:GetCallerIdentity} with {@code provider}: the first step of Test connection and the proof that a role
   * really was assumed.
   *
   * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} with the fix (trust policy, ExternalId, wrong key)
   */
  public CallerIdentity callerIdentity(AwsCredentialsProvider provider, String region) throws ConnectorException {
    try (StsClient sts = stsClient(region, provider)) {
      GetCallerIdentityResponse identity = sts.getCallerIdentity();
      return new CallerIdentity(identity.account(), identity.arn(), identity.userId());
    } catch (StsException e) {
      throw explainAws(e);
    } catch (SdkException e) {
      throw ConnectorException.unavailable("Can't reach AWS STS in " + region + ": " + e.getMessage(), e);
    }
  }

  /**
   * What Test connection shows about a profile: that it runs a program ({@code credential_process}), and why it
   * can't be used at all — it doesn't exist, uses SSO (not supported yet), or takes its credentials from somewhere
   * the gateway never reads ({@code credential_source}, {@code web_identity_token_file}). The whole
   * {@code source_profile} chain is checked.
   */
  public List<String> profileWarnings(AwsSpec spec) {
    if (spec.source() != AwsSpec.Source.PROFILE) {
      return List.of();
    }
    ProfileCheck check = checkProfile(spec.profile());
    List<String> warnings = new ArrayList<>(check.warnings());
    if (check.refusal() != null) {
      warnings.add(check.refusal());
    }
    return warnings;
  }

  private AwsCredentialsProvider awsBase(AwsSpec spec, SecretResolver secrets) throws ConnectorException {
    if (spec.source() == AwsSpec.Source.ACCESS_KEY) {
      try (SecretResolver.Secret secret = secrets.secret(spec.secretKeyField())) {
        // The SDK wants a String: it can't be zeroed, it lives as long as the cached provider (report 12 §6).
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(spec.accessKeyId(), secret.unsafeString()));
      }
    }
    ProfileCheck check = checkProfile(spec.profile());
    if (check.refusal() != null) {
      throw ConnectorException.unavailable(check.refusal(), null);
    }
    return ProfileCredentialsProvider.builder().profileFile(profiles()).profileName(spec.profile()).build();
  }

  /** Warnings about a usable profile, or why it can't be used ({@code refusal}). */
  private record ProfileCheck(List<String> warnings, String refusal) {}

  /**
   * Walks {@code name} and its {@code source_profile} chain. {@code ProfileCredentialsProvider} would otherwise read
   * the instance metadata, the container's credentials or the process environment for {@code credential_source}, and
   * call STS on its own for a web identity: exactly the {@code DefaultCredentialsProvider} sources this class never
   * uses (report 09 §6).
   */
  private ProfileCheck checkProfile(String name) {
    ProfileFile file = profiles();
    List<String> warnings = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String current = name;
    String assumes = null;
    while (current != null) {
      if (!seen.add(current)) {
        return new ProfileCheck(warnings, "The profile " + name + " takes its keys from itself through "
            + "source_profile. Fix ~/.aws/config, or use an access key with a role.");
      }
      Optional<Profile> profile = file.profile(current);
      if (profile.isEmpty()) {
        return new ProfileCheck(warnings, current.equals(name)
            ? "There is no profile " + name + " in ~/.aws/config or ~/.aws/credentials on this computer."
            : "The profile " + name + " takes its keys from the profile " + current + ", which isn't on this "
                + "computer.");
      }
      Profile p = profile.get();
      if (p.property("sso_session").isPresent() || p.property("sso_start_url").isPresent()) {
        return new ProfileCheck(warnings,
            "SSO profiles aren't supported yet: use an access key with a role, or a profile with keys.");
      }
      Optional<String> source = p.property("credential_source");
      if (source.isPresent()) {
        return new ProfileCheck(warnings, "The profile " + current + " takes its credentials from "
            + describeSource(source.get()) + " (credential_source), which MCP Gateway never reads. Use a profile "
            + "with keys, or an access key with a role.");
      }
      if (p.property("web_identity_token_file").isPresent()) {
        return new ProfileCheck(warnings, "Web identity profiles (web_identity_token_file) aren't supported: use a "
            + "profile with keys, or an access key with a role.");
      }
      if (p.property("credential_process").isPresent() && warnings.isEmpty()) {
        warnings.add("This profile runs a command to get credentials.");
      }
      if (assumes == null && p.property("role_arn").isPresent()) {
        assumes = current;
      }
      current = p.property("source_profile").orElse(null);
    }
    if (assumes != null) {
      // The SDK would assume that role itself: through its own STS client (no guard, endpoints from the environment
      // and the profile), without the ExternalId and the session policy, and in the native image not at all (its STS
      // factory is loaded by reflection).
      return new ProfileCheck(warnings, "The profile " + assumes + " assumes a role itself (role_arn). Choose a "
          + "profile with keys and put the role into Role ARN of the connection: MCP Gateway then assumes it with "
          + "its ExternalId and read-only session policy.");
    }
    return new ProfileCheck(warnings, null);
  }

  private static String describeSource(String source) {
    return switch (source) {
      case "Ec2InstanceMetadata" -> "the instance metadata of this computer";
      case "EcsContainer" -> "the container this runs in";
      case "Environment" -> "environment variables";
      default -> source;
    };
  }

  private ProfileFile profiles() {
    if (endpoints.awsConfigFile() == null && endpoints.awsCredentialsFile() == null) {
      return ProfileFile.defaultProfileFile();
    }
    ProfileFile.Aggregator aggregator = ProfileFile.aggregator();
    if (endpoints.awsConfigFile() != null && Files.exists(endpoints.awsConfigFile())) {
      aggregator.addFile(ProfileFile.builder().content(endpoints.awsConfigFile())
          .type(ProfileFile.Type.CONFIGURATION).build());
    }
    if (endpoints.awsCredentialsFile() != null && Files.exists(endpoints.awsCredentialsFile())) {
      aggregator.addFile(ProfileFile.builder().content(endpoints.awsCredentialsFile())
          .type(ProfileFile.Type.CREDENTIALS).build());
    }
    return aggregator.build();
  }

  private StsClient stsClient(String region, AwsCredentialsProvider credentials) {
    return StsClient.builder()
        .region(Region.of(region))
        .httpClient(awsHttp)
        .credentialsProvider(credentials)
        // Explicit, so AWS_ENDPOINT_URL*, aws.endpointUrl and a profile's endpoint_url can't move signed requests.
        .endpointOverride(endpoints.awsStsEndpoint() != null ? endpoints.awsStsEndpoint() : stsEndpoint(region))
        .overrideConfiguration(o -> o.addExecutionInterceptor(new StsOperationGuard())
            .apiCallTimeout(Duration.ofSeconds(10)))
        .build();
  }

  static URI stsEndpoint(String region) {
    String domain = region.startsWith("cn-") ? "amazonaws.com.cn" : "amazonaws.com";
    return URI.create("https://sts." + region + "." + domain);
  }

  /** {@code mcp-gateway-<label>}, cut to STS's 64 characters of {@code [\w+=,.@-]}. */
  static String sessionName(String label) {
    String name = ("mcp-gateway-" + label).replaceAll("[^\\w+=,.@-]", "-");
    return name.length() > 64 ? name.substring(0, 64) : name;
  }

  private static ConnectorException explainAws(StsException e) {
    String code = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
    return switch (code) {
      case "InvalidClientTokenId", "SignatureDoesNotMatch" -> ConnectorException.unavailable(
          "AWS refused the access key: it is wrong or deactivated. Replace it under Credentials.", e);
      case "AccessDenied" -> ConnectorException.unavailable("AWS denied the role: allow sts:AssumeRole for this "
          + "user and add the external ID to the role's trust policy.", e);
      case "PackedPolicyTooLarge" -> ConnectorException.unavailable("The session policy is too large for STS: allow "
          + "fewer services.", e);
      case "ExpiredToken" -> ConnectorException.unavailable("The AWS session expired; test again.", e);
      default -> ConnectorException.unavailable("AWS STS refused the request: " + e.getMessage(), e);
    };
  }

  // ── Secrets › Short-lived, cache ────────────────────────────────────────────────────────────────────────────

  /** What is issued on behalf of connections now: Secrets › Short-lived. No values. */
  public List<IssuedCredential> issued() {
    List<IssuedCredential> out = new ArrayList<>();
    for (GcpEntry e : gcp.values()) {
      if (e.spec().impersonate() != null) {
        out.add(new IssuedCredential(e.owner().label(), "GCP impersonation", e.spec().impersonate(),
            e.issued().at, Duration.ofSeconds(e.spec().lifetimeSeconds())));
      }
    }
    for (AwsEntry e : aws.values()) {
      if (e.spec().roleArn() != null) {
        out.add(new IssuedCredential(e.owner().label(), "AWS AssumeRole", e.spec().roleArn(), e.issued().at,
            Duration.ofSeconds(e.spec().sessionSeconds()), e.spec().externalId()));
      }
    }
    out.sort(Comparator.comparing(IssuedCredential::owner).thenComparing(IssuedCredential::mechanism));
    return out;
  }

  /** Drops every credential of {@code ownerId}: its config, secret or policy changed, or it was removed. */
  public void evict(String ownerId) {
    gcp.values().removeIf(e -> e.owner().id().equals(ownerId));
    aws.entrySet().removeIf(e -> {
      if (e.getValue().owner().id().equals(ownerId)) {
        e.getValue().close();
        return true;
      }
      return false;
    });
  }

  @Override
  public void close() {
    gcp.clear();
    aws.values().forEach(AwsEntry::close);
    aws.clear();
    awsHttp.close();
  }

  /** When the owner's current credentials were issued (written by refresh listeners). */
  private static final class IssuedClock {
    volatile Instant at;
    /** Expiry of the AWS credentials last seen: a new one means STS issued new credentials. */
    volatile Instant lastExpiry;
  }

  private record GcpEntry(Owner owner, GcpSpec spec, GoogleCredentials credentials, IssuedClock issued) {}

  private record AwsEntry(Owner owner, AwsSpec spec, AwsCredentialsProvider provider, StsClient sts,
      StsAssumeRoleCredentialsProvider assume, IssuedClock issued) {
    void close() {
      if (assume != null) {
        assume.close();
      }
      if (sts != null) {
        sts.close();
      }
    }
  }

  /** Records when AssumeRole produced new credentials: their expiry moved. */
  private record RecordingProvider(AwsCredentialsProvider delegate, IssuedClock issued, Clock clock)
      implements AwsCredentialsProvider {
    @Override
    public AwsCredentials resolveCredentials() {
      AwsCredentials credentials = delegate.resolveCredentials();
      Instant expiry = credentials.expirationTime().orElse(null);
      if (issued.at == null || !Objects.equals(expiry, issued.lastExpiry)) {
        issued.lastExpiry = expiry;
        issued.at = clock.instant();
      }
      return credentials;
    }
  }

  /** STS clients may only ask for credentials: any other operation fails before it reaches the network. */
  static final class StsOperationGuard implements ExecutionInterceptor {
    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
      String operation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
      if (!STS_OPERATIONS.contains(operation)) {
        throw new SecurityException("Blocked " + attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME) + "."
            + operation + ": MCP Gateway only asks STS for credentials");
      }
    }
  }
}
