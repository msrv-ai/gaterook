package ai.msrv.gaterook.connectors.cloudauth;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.AzureCloud.Audience;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.connectors.support.Pem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Entra ID access tokens for the Azure connectors, one per owner and audience ({@link Audience}): Resource Manager, Log
 * Analytics, Storage, Key Vault, Service Bus, Azure AI Search. Owned by {@link CloudCredentials#azure()}.
 *
 * <ul>
 *   <li><b>Azure CLI</b>: {@code az account get-access-token --output json --resource <audience> [--tenant <t>]},
 *       run by GateRook as an argv without a shell, the program resolved to an absolute path, with a small
 *       environment, stdin closed, 20 s. Only the exact command the person allowed in Test connection runs
 *       ({@link CliCommand#fingerprint}): another program, other arguments, another tenant ask again.</li>
 *   <li><b>Device sign-in</b>: the OAuth device authorization grant; Test connection shows the code, the person signs
 *       in in a browser. The refresh token stays in memory, shared by connections of one cloud, tenant and client,
 *       until the core stops.</li>
 *   <li><b>Service principal</b>: the client credentials grant with a client secret, or with a JWT assertion signed
 *       by the certificate's RSA key (PS256, {@code x5t#S256}: Entra ID's documented format).</li>
 * </ul>
 *
 * <p>Never managed identity (the metadata address), environment variables, workload identity files or other programs'
 * token caches. Tokens live in memory only; callers hand every token to the call's taint. A token's tenant
 * ({@code tid}) is read from its claims so a connection can refuse a sign-in of another tenant.
 */
public final class AzureCredentials implements AutoCloseable {

  /** How long before its expiry a token is replaced: the CLI hands out tokens valid at least 5 minutes. */
  static final Duration REFRESH_BEFORE = Duration.ofMinutes(5);
  static final Duration CLI_TIMEOUT = Duration.ofSeconds(20);
  static final Duration MAX_REQUEST = Duration.ofSeconds(15);
  private static final int MAX_OUTPUT = 1 << 20;
  private static final int MAX_BODY = 1 << 20;
  private static final Pattern AADSTS = Pattern.compile("AADSTS(\\d{5,7})");
  private static final DateTimeFormatter CLI_LOCAL_TIME = DateTimeFormatter.ofPattern(
      "yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();

  /**
   * One access token. {@code toString} never shows its value.
   *
   * @param tenantId    the {@code tid} claim, or {@code null} for an opaque token
   * @param account     who it is for: the user's sign-in name or the application ID
   * @param application whether it is an application's token (a service principal) rather than a person's
   */
  public record AccessToken(String value, Instant expiresAt, String tenantId, String objectId, String account,
      boolean application) {
    @Override
    public String toString() {
      return "AccessToken[" + account + ", tenant " + tenantId + ", until " + expiresAt + "]";
    }
  }

  /**
   * What a CLI sign-in runs, as the person reads it in the confirmation and as its fingerprint covers it.
   *
   * @param args        the arguments, the audience written as {@code <resource>}
   * @param environment the names of the variables the command gets (their values come from this process)
   */
  public record CliCommand(Path program, List<String> args, List<String> environment, String fingerprint) {
    /** The argv for one audience. */
    List<String> argv(String resource) {
      List<String> argv = new ArrayList<>();
      argv.add(program.toString());
      for (String a : args) {
        argv.add(a.equals("<resource>") ? resource : a);
      }
      return argv;
    }

    /** {@code /opt/homebrew/bin/az account get-access-token --output json --resource <resource>}. */
    public String commandLine() {
      StringBuilder out = new StringBuilder();
      List<String> argv = new ArrayList<>();
      argv.add(program.toString());
      argv.addAll(args);
      for (String a : argv) {
        if (!out.isEmpty()) {
          out.append(' ');
        }
        out.append(a.isEmpty() || a.chars().anyMatch(c -> " \t'\"\\$`".indexOf(c) >= 0)
            ? "'" + a.replace("'", "'\\''") + "'" : a);
      }
      return out.toString();
    }
  }

  /** Where a device sign-in stands. */
  public sealed interface DeviceSignIn {
    /** Signed in: calls can get tokens. */
    record SignedIn(String account, String tenantId) implements DeviceSignIn {}

    /** Waiting for the person to enter {@code userCode} at {@code verificationUri}. */
    record Pending(String userCode, URI verificationUri, Instant expiresAt) implements DeviceSignIn {}
  }

  private final CloudEndpoints endpoints;
  private final Clock clock;
  private final HttpClient http;
  private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
  private final Map<String, SignIn> signIns = new ConcurrentHashMap<>();
  private final Map<String, PendingCode> pending = new ConcurrentHashMap<>();

  AzureCredentials(CloudEndpoints endpoints, Clock clock) {
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  // ── Where tokens go ─────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The base URL of an Azure API: its test endpoint ({@code azure.arm}, {@code azure.logs}) in a development build,
   * else the cloud's own.
   */
  public URI base(AzureCloud cloud, Audience audience) {
    URI test = endpoints.service(serviceKey(audience));
    if (test != null) {
      return test;
    }
    return switch (audience) {
      case ARM -> cloud.arm();
      case LOG_ANALYTICS -> cloud.logAnalytics();
      default -> throw new IllegalArgumentException(audience + " has no single base URL");
    };
  }

  private static String serviceKey(Audience audience) {
    return switch (audience) {
      case ARM -> "azure.arm";
      case LOG_ANALYTICS -> "azure.logs";
      case STORAGE -> "azure.storage";
      case KEY_VAULT -> "azure.keyvault";
      case SERVICE_BUS -> "azure.servicebus";
      case SEARCH -> "azure.search";
    };
  }

  /**
   * {@code https://<host>}: a data-plane host of {@code audience} in {@code cloud} (a storage account's service, a
   * vault, a namespace), checked by {@link AzureCloud#accepts}. In a development build with a test endpoint for the
   * audience, {@code <test endpoint>/<host>}: the host becomes the first path segment, so one fake serves every
   * account.
   *
   * @throws IllegalArgumentException for a host the audience's tokens may not go to
   */
  public URI dataPlane(AzureCloud cloud, Audience audience, String host) {
    URI test = endpoints.service(serviceKey(audience));
    if (test != null) {
      String b = test.toString();
      return URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + "/" + host);
    }
    URI real = URI.create("https://" + host);
    if (!cloud.accepts(audience, real)) {
      throw new IllegalArgumentException("Not a " + audience.title() + " host of " + cloud.id() + ": " + host);
    }
    return real;
  }

  /**
   * Whether a token for {@code audience} may go to {@code url}: the cloud's host of that audience
   * ({@link AzureCloud#accepts}), or a test endpoint of the same API when the tokens come from a test sign-in
   * endpoint ({@code azure.login}): a token of a fake authority is good for a fake API only. A release has neither.
   */
  public boolean sendsTokensTo(AzureCloud cloud, Audience audience, URI url) {
    if (cloud.accepts(audience, url)) {
      return endpoints.service("azure.login") == null;
    }
    if (endpoints.service("azure.login") == null) {
      return false;
    }
    for (Map.Entry<String, URI> e : endpoints.services().entrySet()) {
      URI test = e.getValue();
      if (e.getKey().startsWith("azure.") && !e.getKey().equals("azure.login") && test.getHost() != null
          && test.getHost().equalsIgnoreCase(url.getHost()) && test.getPort() == url.getPort()
          && test.getScheme().equalsIgnoreCase(url.getScheme())) {
        return true;
      }
    }
    return false;
  }

  /** The authority of {@code cloud}: its test endpoint in a development build, else the real one. */
  private URI authority(AzureCloud cloud) {
    URI test = endpoints.service("azure.login");
    return test != null ? test : cloud.authority();
  }

  private URI tokenEndpoint(AzureSpec spec) {
    return endpoint(authority(spec.cloud()), "/" + spec.tenantId() + "/oauth2/v2.0/token");
  }

  private static URI endpoint(URI base, String path) {
    String b = base.toString();
    return URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + path);
  }

  // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A token of {@code owner} for {@code audience}: the cached one while it has more than five minutes left, else a
   * new one from the spec's source.
   *
   * @param budget how long getting a new token may take (the call's remaining time)
   * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} with what to do (sign in, replace the secret),
   *                            {@code DENIED} when the CLI command isn't the one the person allowed
   */
  public AccessToken token(CloudCredentials.Owner owner, AzureSpec spec, Audience audience, SecretResolver secrets,
      Duration budget) throws ConnectorException {
    String secretVersion = spec.secretField() == null ? "" : String.valueOf(secrets.meta(spec.secretField()).version());
    String key = owner.id() + "|" + owner.version() + "|" + spec + "|" + audience + "|" + secretVersion;
    Entry entry = tokens.computeIfAbsent(key, k -> new Entry(owner, spec, audience));
    synchronized (entry) {
      AccessToken current = entry.token;
      if (current != null && clock.instant().isBefore(current.expiresAt().minus(REFRESH_BEFORE))) {
        return current;
      }
      AccessToken fresh = switch (spec.source()) {
        case CLI -> cliToken(spec, audience, budget);
        case DEVICE_CODE -> refreshToken(spec, audience, budget);
        case CLIENT_SECRET -> clientSecretToken(spec, audience, secrets, budget);
        case CLIENT_CERTIFICATE -> certificateToken(spec, audience, secrets, budget);
      };
      entry.token = fresh;
      entry.issuedAt = clock.instant();
      return fresh;
    }
  }

  /** Drops the cached token of {@code owner} for {@code audience}: after a 401 the next call asks again. */
  public void invalidate(String ownerId, Audience audience) {
    tokens.values().removeIf(e -> e.owner.id().equals(ownerId) && e.audience == audience);
  }

  // ── Azure CLI ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The CLI command of {@code spec}, the program looked up now so that the fingerprint names the file that will run.
   *
   * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} when the Azure CLI isn't installed
   */
  public CliCommand cliCommand(AzureSpec spec) throws ConnectorException {
    Path program = cliProgram();
    List<String> args = new ArrayList<>(List.of("account", "get-access-token", "--output", "json", "--resource",
        "<resource>"));
    if (spec.tenantId() != null) {
      args.add("--tenant");
      args.add(spec.tenantId());
    }
    List<String> environment = cliEnvironment().keySet().stream().sorted().toList();
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("program", program.toString());
    ArrayNode a = n.putArray("args");
    args.forEach(a::add);
    ArrayNode e = n.putArray("env");
    environment.forEach(e::add);
    n.put("cloud", spec.cloud().id());
    String fingerprint = HexFormat.of().formatHex(sha256(JSON.writeValueAsBytes(n)));
    return new CliCommand(program, List.copyOf(args), environment, fingerprint);
  }

  private AccessToken cliToken(AzureSpec spec, Audience audience, Duration budget) throws ConnectorException {
    CliCommand command = cliCommand(spec);
    if (!command.fingerprint().equals(spec.trustedCli())) {
      throw ConnectorException.denied(spec.trustedCli() == null
          ? "GateRook runs the Azure CLI only after you allow the command: test the connection and choose Allow."
          : "The Azure CLI command changed (" + command.program() + "). Test the connection to review it again.");
    }
    Duration limit = budget.compareTo(CLI_TIMEOUT) < 0 ? budget : CLI_TIMEOUT;
    if (limit.isZero() || limit.isNegative()) {
      throw ConnectorException.timeout("No time was left to run the Azure CLI.");
    }
    ProcessBuilder pb = new ProcessBuilder(command.argv(spec.cloud().resource(audience)))
        .directory(Path.of(System.getProperty("user.home")).toFile());
    Map<String, String> env = pb.environment();
    env.clear();
    env.putAll(cliEnvironment());
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      throw ConnectorException.unavailable("The Azure CLI couldn't start: " + e.getMessage(), e);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Thread stdout = Thread.ofVirtual().start(() -> read(process.getInputStream(), out, MAX_OUTPUT));
    Thread stderr = Thread.ofVirtual().start(() -> read(process.getErrorStream(), err, 8192));
    try {
      process.getOutputStream().close();
      if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
        kill(process);
        throw ConnectorException.timeout("The Azure CLI didn't answer within " + Math.max(1, limit.toSeconds())
            + " s. Run az account get-access-token in a terminal once to check the sign-in.");
      }
      stdout.join(1000);
      stderr.join(1000);
    } catch (InterruptedException e) {
      kill(process);
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The Azure CLI was stopped.");
    } catch (IOException e) {
      kill(process);
      throw ConnectorException.unavailable("The Azure CLI failed: " + e.getMessage(), e);
    }
    byte[] printed = out.toByteArray();
    try {
      if (process.exitValue() != 0) {
        throw cliFailure(OutputSanitizer.decode(err.toByteArray()), spec);
      }
      JsonNode answer;
      try {
        answer = JSON.readTree(printed);
      } catch (RuntimeException e) {
        throw ConnectorException.unavailable("The Azure CLI printed something that isn't a token (JSON).", null);
      }
      String value = answer.path("accessToken").asString("");
      if (value.isBlank()) {
        throw ConnectorException.unavailable("The Azure CLI returned no access token.", null);
      }
      Instant expires = cliExpiry(answer);
      return accessToken(value, expires, answer.path("tenant").asString(null));
    } finally {
      java.util.Arrays.fill(printed, (byte) 0);
    }
  }

  /** {@code expires_on} (POSIX seconds, CLI 2.54 and later), else {@code expiresOn} (local time), else 10 minutes. */
  private Instant cliExpiry(JsonNode answer) {
    JsonNode epoch = answer.get("expires_on");
    if (epoch != null && (epoch.isNumber() || epoch.asString("").matches("\\d{9,11}"))) {
      return Instant.ofEpochSecond(epoch.isNumber() ? epoch.asLong() : Long.parseLong(epoch.asString()));
    }
    String local = answer.path("expiresOn").asString("");
    if (!local.isBlank()) {
      try {
        return LocalDateTime.parse(local, CLI_LOCAL_TIME).atZone(ZoneId.systemDefault()).toInstant();
      } catch (DateTimeParseException e) {
        // An unreadable time counts as a short one.
      }
    }
    return clock.instant().plus(Duration.ofMinutes(10));
  }

  private static ConnectorException cliFailure(String stderr, AzureSpec spec) {
    String first = stderr.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("WARNING"))
        .findFirst().orElse("");
    Matcher code = AADSTS.matcher(stderr);
    if (code.find()) {
      return ConnectorException.unavailable(aadsts(code.group(1), spec, "The Azure CLI's sign-in"), null);
    }
    String lower = stderr.toLowerCase(Locale.ROOT);
    if (lower.contains("az login") || lower.contains("not logged in") || lower.contains("please run")) {
      return ConnectorException.unavailable("The Azure CLI isn't signed in" + (spec.tenantId() == null ? ""
          : " to " + spec.tenantId()) + ". Run az login" + (spec.tenantId() == null ? "" : " --tenant "
          + spec.tenantId()) + " in a terminal, then test again.", null);
    }
    return ConnectorException.unavailable("The Azure CLI failed" + (first.isEmpty() ? "." : ": " + cut(first, 300)),
        null);
  }

  /** The variables the CLI gets: what it needs to find its sign-in and Python, nothing of the core. */
  private static Map<String, String> cliEnvironment() {
    Map<String, String> out = new LinkedHashMap<>();
    Map<String, String> own = System.getenv();
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    List<String> names = new ArrayList<>(List.of("PATH", "AZURE_CONFIG_DIR", "TMPDIR", "LANG"));
    if (windows) {
      names.addAll(List.of("SystemRoot", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP", "PATHEXT",
          "ComSpec", "HOMEDRIVE", "HOMEPATH"));
    }
    for (String name : names) {
      String value = own.get(name);
      if (value == null && windows) {
        // Windows names are case-insensitive; System.getenv() already folds them, the map's keys may not.
        value = System.getenv(name);
      }
      if (value != null && !value.isEmpty()) {
        out.put(name, value);
      }
    }
    out.put("HOME", System.getProperty("user.home"));
    return out;
  }

  /**
   * The {@code az} program: a test build's override, else {@code PATH}, then where the installers put it — a desktop
   * app doesn't inherit the shell's {@code PATH}.
   */
  private Path cliProgram() throws ConnectorException {
    if (endpoints.azureCli() != null) {
      Path p = endpoints.azureCli();
      if (Files.isRegularFile(p) && Files.isExecutable(p)) {
        return p.toAbsolutePath().normalize();
      }
      throw ConnectorException.unavailable("The Azure CLI isn't installed on this computer.", null);
    }
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    String name = windows ? "az.cmd" : "az";
    List<Path> dirs = new ArrayList<>();
    String path = System.getenv("PATH");
    if (path != null) {
      for (String d : path.split(java.io.File.pathSeparator)) {
        if (!d.isBlank()) {
          dirs.add(Path.of(d));
        }
      }
    }
    String home = System.getProperty("user.home");
    if (windows) {
      dirs.add(Path.of("C:\\Program Files\\Microsoft SDKs\\Azure\\CLI2\\wbin"));
      dirs.add(Path.of("C:\\Program Files (x86)\\Microsoft SDKs\\Azure\\CLI2\\wbin"));
    } else {
      for (String d : new String[] {"/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", home + "/.local/bin",
          home + "/bin"}) {
        dirs.add(Path.of(d));
      }
    }
    for (Path d : dirs) {
      Path candidate = d.resolve(name);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    throw ConnectorException.unavailable("The Azure CLI (az) isn't installed on this computer: not found in PATH"
        + (windows ? " or Program Files." : ", /opt/homebrew/bin or /usr/local/bin.") + " Install it, run az login, "
        + "then test again.", null);
  }

  // ── Device sign-in ──────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The device sign-in of {@code spec}: signed in, or the code to enter. A pending code is asked about once per call
   * (the person chose "I signed in"); an expired one is replaced by a new code.
   *
   * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} when Entra ID refused the sign-in, with why
   */
  public DeviceSignIn deviceSignIn(AzureSpec spec, Duration budget) throws ConnectorException {
    String key = signInKey(spec);
    SignIn done = signIns.get(key);
    if (done != null) {
      return new DeviceSignIn.SignedIn(done.account, done.tenantId);
    }
    synchronized (pending) {
      PendingCode p = pending.get(key);
      if (p != null && clock.instant().isBefore(p.expiresAt)) {
        Form form = new Form().put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .put("client_id", spec.publicClientId()).put("device_code", p.deviceCode);
        TokenAnswer answer = post(tokenEndpoint(spec), form, budget, "Entra ID");
        if (answer.ok()) {
          pending.remove(key);
          SignIn signIn = signIn(answer, spec);
          signIns.put(key, signIn);
          return new DeviceSignIn.SignedIn(signIn.account, signIn.tenantId);
        }
        switch (answer.error()) {
          case "authorization_pending", "slow_down" -> {
            return new DeviceSignIn.Pending(p.userCode, p.verificationUri, p.expiresAt);
          }
          case "authorization_declined" -> {
            pending.remove(key);
            throw ConnectorException.unavailable("The Azure sign-in was declined in the browser. Test again to get "
                + "a new code.", null);
          }
          case "expired_token", "code_expired", "bad_verification_code" -> pending.remove(key);
          default -> {
            pending.remove(key);
            throw answer.failure(spec, "The Azure sign-in");
          }
        }
      }
      Form form = new Form().put("client_id", spec.publicClientId())
          .put("scope", spec.cloud().scope(Audience.ARM) + " offline_access openid profile");
      TokenAnswer answer = post(endpoint(authority(spec.cloud()), "/" + spec.tenantId() + "/oauth2/v2.0/devicecode"),
          form, budget, "Entra ID");
      if (!answer.ok()) {
        throw answer.failure(spec, "Starting the Azure sign-in");
      }
      JsonNode body = answer.body();
      String deviceCode = body.path("device_code").asString("");
      String userCode = body.path("user_code").asString("");
      String uri = body.path("verification_uri").asString("");
      if (deviceCode.isBlank() || userCode.isBlank() || !uri.startsWith("https://")) {
        throw ConnectorException.unavailable("Entra ID answered the device sign-in without a code.", null);
      }
      Instant expires = clock.instant().plusSeconds(Math.max(60, Math.min(1800, number(body.get("expires_in"),
          900))));
      PendingCode code = new PendingCode(deviceCode, userCode, URI.create(uri), expires);
      pending.put(key, code);
      return new DeviceSignIn.Pending(code.userCode, code.verificationUri, code.expiresAt);
    }
  }

  /** Forgets the device sign-in of {@code spec}'s cloud, tenant and client: the next Test connection shows a code. */
  public void signOut(AzureSpec spec) {
    signIns.remove(signInKey(spec));
    pending.remove(signInKey(spec));
    tokens.values().removeIf(e -> e.spec.source() == AzureSpec.Source.DEVICE_CODE
        && signInKey(e.spec).equals(signInKey(spec)));
  }

  private AccessToken refreshToken(AzureSpec spec, Audience audience, Duration budget) throws ConnectorException {
    String key = signInKey(spec);
    SignIn signIn = signIns.get(key);
    if (signIn == null) {
      throw ConnectorException.unavailable("Nobody is signed in to Azure for this connection: open it and choose "
          + "Test connection to sign in with a code. Device sign-ins last until GateRook restarts.", null);
    }
    synchronized (signIn) {
      Form form = new Form().put("grant_type", "refresh_token").put("client_id", spec.publicClientId())
          .put("refresh_token", signIn.refreshToken).put("scope", spec.cloud().scope(audience) + " offline_access");
      TokenAnswer answer = post(tokenEndpoint(spec), form, budget, "Entra ID");
      if (!answer.ok()) {
        if (answer.error().equals("invalid_grant")) {
          signIns.remove(key, signIn);
        }
        throw answer.failure(spec, "The Azure sign-in");
      }
      String rotated = answer.body().path("refresh_token").asString("");
      if (!rotated.isBlank()) {
        signIn.refreshToken = rotated;
      }
      signIn.issuedAt = clock.instant();
      return answer.accessToken(this);
    }
  }

  private SignIn signIn(TokenAnswer answer, AzureSpec spec) throws ConnectorException {
    String refresh = answer.body().path("refresh_token").asString("");
    if (refresh.isBlank()) {
      throw ConnectorException.unavailable("Entra ID signed in without a refresh token (offline_access): the "
          + "tenant's policy may not allow it. Use a service principal.", null);
    }
    AccessToken first = answer.accessToken(this);
    String account = first.account();
    JsonNode id = claims(answer.body().path("id_token").asString(""));
    String name = firstText(id, "preferred_username", "upn", "email", "name");
    if (name != null) {
      account = name;
    }
    SignIn s = new SignIn(spec, refresh, account, first.tenantId());
    s.issuedAt = clock.instant();
    return s;
  }

  private static String signInKey(AzureSpec spec) {
    return spec.cloud().id() + "|" + spec.tenantId() + "|" + spec.publicClientId();
  }

  // ── Service principal ───────────────────────────────────────────────────────────────────────────────────────

  private AccessToken clientSecretToken(AzureSpec spec, Audience audience, SecretResolver secrets, Duration budget)
      throws ConnectorException {
    try (SecretResolver.Secret secret = secrets.secret(spec.secretField())) {
      Form form = new Form().put("grant_type", "client_credentials").put("client_id", spec.clientId())
          .put("scope", spec.cloud().scope(audience)).put("client_secret", secret.unsafeString());
      TokenAnswer answer = post(tokenEndpoint(spec), form, budget, "Entra ID");
      if (!answer.ok()) {
        throw answer.failure(spec, "The service principal's sign-in");
      }
      return answer.accessToken(this);
    }
  }

  private AccessToken certificateToken(AzureSpec spec, Audience audience, SecretResolver secrets, Duration budget)
      throws ConnectorException {
    URI endpoint = tokenEndpoint(spec);
    String assertion;
    try (SecretResolver.Secret pem = secrets.secret(spec.secretField())) {
      assertion = assertion(new String(pem.chars()), spec.clientId(), endpoint, clock.instant());
    }
    Form form = new Form().put("grant_type", "client_credentials").put("client_id", spec.clientId())
        .put("scope", spec.cloud().scope(audience))
        .put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
        .put("client_assertion", assertion);
    TokenAnswer answer = post(endpoint, form, budget, "Entra ID");
    if (!answer.ok()) {
      throw answer.failure(spec, "The service principal's sign-in");
    }
    return answer.accessToken(this);
  }

  /**
   * The client assertion of a certificate credential (Microsoft identity platform "certificate credentials"): header
   * {@code {"alg":"PS256","typ":"JWT","x5t#S256":<SHA-256 of the certificate, base64url>}}, claims {@code aud} (the
   * token endpoint), {@code iss} = {@code sub} = the client ID, a fresh {@code jti}, valid for 10 minutes.
   *
   * @throws ConnectorException {@code BAD_INPUT} when the PEM has no RSA key and matching certificate
   */
  static String assertion(String pem, String clientId, URI audience, Instant now) throws ConnectorException {
    PrivateKey key;
    List<X509Certificate> certificates;
    try {
      key = Pem.privateKey(pem);
      certificates = Pem.certificates(pem);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The certificate field can't be used: " + e.getMessage()
          + ". Paste the PEM with the private key and the certificate.");
    }
    if (!(key instanceof RSAPrivateCrtKey rsa)) {
      throw ConnectorException.badInput("The certificate's key must be RSA: Entra ID accepts RSA certificates for "
          + "service principals.");
    }
    X509Certificate certificate = null;
    for (X509Certificate c : certificates) {
      if (c.getPublicKey() instanceof RSAPublicKey pub && pub.getModulus().equals(rsa.getModulus())) {
        certificate = c;
        break;
      }
    }
    if (certificate == null) {
      throw ConnectorException.badInput(certificates.isEmpty()
          ? "The certificate field has a private key but no certificate: paste both (-----BEGIN CERTIFICATE-----)."
          : "The certificate in the field doesn't belong to its private key.");
    }
    try {
      ObjectNode header = JsonNodeFactory.instance.objectNode();
      header.put("alg", "PS256").put("typ", "JWT").put("x5t#S256", URL64.encodeToString(sha256(
          certificate.getEncoded())));
      ObjectNode claims = JsonNodeFactory.instance.objectNode();
      long seconds = now.getEpochSecond();
      claims.put("aud", audience.toString()).put("iss", clientId).put("sub", clientId)
          .put("jti", UUID.randomUUID().toString()).put("nbf", seconds - 60).put("iat", seconds)
          .put("exp", seconds + 600);
      String signingInput = URL64.encodeToString(JSON.writeValueAsBytes(header)) + "."
          + URL64.encodeToString(JSON.writeValueAsBytes(claims));
      Signature signature = Signature.getInstance("RSASSA-PSS");
      signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
      signature.initSign(rsa);
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + URL64.encodeToString(signature.sign());
    } catch (GeneralSecurityException e) {
      throw ConnectorException.badInput("The certificate's key can't sign the sign-in: " + e.getMessage());
    }
  }

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code application/x-www-form-urlencoded} fields, in order. */
  private static final class Form {
    private final Map<String, String> fields = new LinkedHashMap<>();

    Form put(String name, String value) {
      fields.put(name, value);
      return this;
    }

    String encoded() {
      StringBuilder b = new StringBuilder();
      fields.forEach((k, v) -> {
        if (!b.isEmpty()) {
          b.append('&');
        }
        b.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
            .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
      });
      return b.toString();
    }
  }

  /** What an Entra ID endpoint answered: a token, a device code, or an OAuth error. */
  private record TokenAnswer(int status, JsonNode body) {
    boolean ok() {
      return status / 100 == 2 && body.path("error").asString("").isEmpty();
    }

    String error() {
      return body.path("error").asString("");
    }

    AccessToken accessToken(AzureCredentials owner) throws ConnectorException {
      String value = body.path("access_token").asString("");
      if (value.isBlank()) {
        throw ConnectorException.unavailable("Entra ID answered without an access token.", null);
      }
      long seconds = number(body.get("expires_in"), 3600);
      return owner.accessToken(value, owner.clock.instant().plusSeconds(Math.max(60, seconds)), null);
    }

    ConnectorException failure(AzureSpec spec, String what) {
      String description = body.path("error_description").asString("");
      Matcher code = AADSTS.matcher(description);
      if (code.find()) {
        return ConnectorException.unavailable(aadsts(code.group(1), spec, what), null);
      }
      String error = error();
      if (status >= 500) {
        return ConnectorException.unavailable("Entra ID failed (" + status + "). Try again in a minute.", null);
      }
      return ConnectorException.unavailable(what + " was refused" + (error.isEmpty() ? " (" + status + ")" : " ("
          + error + ")") + (description.isBlank() ? "." : ": " + firstLine(description)), null);
    }
  }

  private TokenAnswer post(URI uri, Form form, Duration budget, String what) throws ConnectorException {
    Duration limit = budget.compareTo(MAX_REQUEST) < 0 ? budget : MAX_REQUEST;
    if (limit.isZero() || limit.isNegative()) {
      throw ConnectorException.timeout(what + " didn't answer in time.");
    }
    HttpRequest request = HttpRequest.newBuilder(uri)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .timeout(limit)
        .POST(HttpRequest.BodyPublishers.ofString(form.encoded()))
        .build();
    try {
      HttpResponse<InputStream> r = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (InputStream in = r.body()) {
        body = in.readNBytes(MAX_BODY + 1);
      }
      if (body.length > MAX_BODY) {
        throw ConnectorException.unavailable(what + " answered with more than 1 MB.", null);
      }
      JsonNode tree;
      try {
        tree = body.length == 0 ? JsonNodeFactory.instance.objectNode() : JSON.readTree(body);
      } catch (RuntimeException e) {
        throw ConnectorException.unavailable(what + " answered " + r.statusCode() + " with something that isn't "
            + "JSON.", null);
      } finally {
        java.util.Arrays.fill(body, (byte) 0);
      }
      return new TokenAnswer(r.statusCode(), tree);
    } catch (HttpTimeoutException e) {
      throw ConnectorException.timeout(what + " didn't answer in time.");
    } catch (IOException e) {
      throw ConnectorException.unavailable("Can't reach " + uri.getHost() + " to sign in to Azure: "
          + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(what + " was cancelled.");
    }
  }

  // ── Claims, messages ────────────────────────────────────────────────────────────────────────────────────────

  private AccessToken accessToken(String value, Instant expires, String tenantHint) {
    JsonNode claims = claims(value);
    String tenant = firstText(claims, "tid");
    if (tenant == null) {
      tenant = tenantHint;
    }
    boolean application = "app".equals(claims.path("idtyp").asString(""))
        || (firstText(claims, "upn", "unique_name", "preferred_username") == null && firstText(claims, "appid",
            "azp") != null && claims.path("scp").isMissingNode());
    String account = application ? firstText(claims, "appid", "azp")
        : firstText(claims, "upn", "unique_name", "preferred_username", "email", "oid");
    JsonNode exp = claims.get("exp");
    if (exp != null && exp.isNumber()) {
      Instant fromToken = Instant.ofEpochSecond(exp.asLong());
      if (fromToken.isBefore(expires)) {
        expires = fromToken;
      }
    }
    return new AccessToken(value, expires, tenant == null ? null : tenant.toLowerCase(Locale.ROOT),
        firstText(claims, "oid"), account == null ? "unknown" : account, application);
  }

  /** The claims of a JWT, read without checking its signature (Entra ID signed it; the API checks it). */
  static JsonNode claims(String jwt) {
    String[] parts = jwt == null ? new String[0] : jwt.split("\\.");
    if (parts.length < 2) {
      return JsonNodeFactory.instance.objectNode();
    }
    try {
      JsonNode n = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
      return n.isObject() ? n : JsonNodeFactory.instance.objectNode();
    } catch (RuntimeException e) {
      return JsonNodeFactory.instance.objectNode();
    }
  }

  private static String firstText(JsonNode node, String... fields) {
    for (String f : fields) {
      String v = node.path(f).asString("");
      if (!v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  /** What an Entra ID error code means and what to do (the common ones). */
  static String aadsts(String code, AzureSpec spec, String what) {
    String tenant = spec.tenantId() == null ? "the tenant" : spec.tenantId();
    return switch (code) {
      case "7000215" -> "Entra ID refused the client secret: it is wrong (use the secret's value, not its ID). "
          + "Replace it under Credentials.";
      case "7000222" -> "The client secret has expired. Create a new one for the app registration and replace it "
          + "under Credentials.";
      case "700016" -> "Tenant " + tenant + " has no application " + spec.clientId() + ". Check the client ID and the "
          + "tenant.";
      case "90002", "90072", "900023" -> "Entra ID doesn't know the tenant " + tenant + ". Check the tenant ID.";
      case "700027", "700024" -> "Entra ID refused the certificate: upload its public certificate to the app "
          + "registration, and check that this computer's clock is right.";
      case "53003" -> what + " was blocked by a Conditional Access policy of " + tenant + ".";
      case "50076", "50079" -> what + " needs multifactor authentication again. Sign in again (az login or Test "
          + "connection).";
      case "70043", "700082", "50173", "50133", "70008" -> what + " has expired. Sign in again (az login or Test "
          + "connection), then test again.";
      case "65001", "65004" -> "The application needs consent in " + tenant + ": an administrator grants it in "
          + "Entra ID.";
      case "50034", "50020" -> "The account isn't in tenant " + tenant + ". Sign in with an account of that tenant.";
      case "50126" -> "Entra ID refused the user name or password.";
      default -> what + " was refused by Entra ID (AADSTS" + code + ").";
    };
  }

  private static String firstLine(String text) {
    String line = text.lines().findFirst().orElse("").strip();
    return cut(OutputSanitizer.sanitize(line), 300);
  }

  private static long number(JsonNode n, long dflt) {
    if (n == null || n.isNull()) {
      return dflt;
    }
    if (n.isNumber()) {
      return n.asLong();
    }
    try {
      return Long.parseLong(n.asString().strip());
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void kill(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private static void read(InputStream in, ByteArrayOutputStream out, int max) {
    byte[] buf = new byte[8192];
    try (in) {
      int n;
      while ((n = in.read(buf)) >= 0) {
        if (out.size() < max) {
          out.write(buf, 0, Math.min(n, max - out.size()));
        }
      }
    } catch (IOException ignored) {
      // The process went away; what was read is what we have.
    } finally {
      java.util.Arrays.fill(buf, (byte) 0);
    }
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }

  // ── Secrets › Short-lived, cache ────────────────────────────────────────────────────────────────────────────

  /** What is issued now on behalf of connections, for Secrets › Short-lived. No values. */
  List<IssuedCredential> issued() {
    List<IssuedCredential> out = new ArrayList<>();
    for (Entry e : tokens.values()) {
      AccessToken t = e.token;
      if (t == null || e.issuedAt == null) {
        continue;
      }
      String mechanism = switch (e.spec.source()) {
        case CLI -> "Azure CLI sign-in";
        case DEVICE_CODE -> "Azure device sign-in";
        case CLIENT_SECRET, CLIENT_CERTIFICATE -> "Azure service principal";
      };
      out.add(new IssuedCredential(e.owner.label(), mechanism + " · " + e.audience.title(), t.account(), e.issuedAt,
          Duration.between(e.issuedAt, t.expiresAt()).isNegative() ? Duration.ZERO
              : Duration.between(e.issuedAt, t.expiresAt())));
    }
    out.sort(Comparator.comparing(IssuedCredential::owner).thenComparing(IssuedCredential::mechanism));
    return out;
  }

  /** Drops the tokens of {@code ownerId}; device sign-ins belong to the cloud, tenant and client and stay. */
  void evict(String ownerId) {
    tokens.values().removeIf(e -> e.owner.id().equals(ownerId));
  }

  @Override
  public void close() {
    tokens.clear();
    signIns.clear();
    pending.clear();
    http.shutdownNow();
  }

  /** One owner's token for one audience; refreshed under the entry's lock. */
  private static final class Entry {
    final CloudCredentials.Owner owner;
    final AzureSpec spec;
    final Audience audience;
    AccessToken token;
    Instant issuedAt;

    Entry(CloudCredentials.Owner owner, AzureSpec spec, Audience audience) {
      this.owner = owner;
      this.spec = spec;
      this.audience = audience;
    }
  }

  /** A completed device sign-in: its refresh token rotates on every use. */
  private static final class SignIn {
    final AzureSpec spec;
    volatile String refreshToken;
    final String account;
    final String tenantId;
    volatile Instant issuedAt;

    SignIn(AzureSpec spec, String refreshToken, String account, String tenantId) {
      this.spec = spec;
      this.refreshToken = refreshToken;
      this.account = account;
      this.tenantId = tenantId;
    }
  }

  private record PendingCode(String deviceCode, String userCode, URI verificationUri, Instant expiresAt) {}
}
