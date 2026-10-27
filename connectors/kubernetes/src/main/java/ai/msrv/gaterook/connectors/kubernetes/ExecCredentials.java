package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The credential command of a kubeconfig ({@code users[].user.exec}: {@code gke-gcloud-auth-plugin},
 * {@code aws eks get-token}, {@code kubelogin}), run by the connector and never by Fabric8, which would start it while
 * merging kubeconfig files (report 08 §6).
 *
 * <p>Only when the connection says so ({@code allowExecPlugin}) and only the exact command the person allowed in
 * Test connection ({@link #fingerprint}): an argv without a shell, the program found by an absolute path, an
 * environment of {@code PATH}, {@code HOME}, the kubeconfig's own variables and {@code KUBERNETES_EXEC_INFO}
 * ({@code interactive: false}), stdin closed, 20 s. The token it prints is cached until its
 * {@code expirationTimestamp} (or 10 minutes) and tainted in every call that uses it.
 */
final class ExecCredentials {

  static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final Duration NO_EXPIRY_CACHE = Duration.ofMinutes(10);
  private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);
  private static final int MAX_OUTPUT = 1 << 20;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What the kubeconfig asks to run, with the program resolved to a file. */
  record Spec(String apiVersion, Path command, List<String> args, Map<String, String> env, ObjectNode cluster,
      Path workDir) {

    /** The argv a person reads in the confirmation and in Setup changes. */
    String commandLine() {
      List<String> argv = new ArrayList<>();
      argv.add(command.toString());
      argv.addAll(args);
      StringBuilder out = new StringBuilder();
      for (String a : argv) {
        if (!out.isEmpty()) {
          out.append(' ');
        }
        out.append(a.isEmpty() || a.chars().anyMatch(c -> " \t'\"\\$`".indexOf(c) >= 0) ? "'" + a.replace("'",
            "'\\''") + "'" : a);
      }
      return out.toString();
    }
  }

  private final Spec spec;
  private final Clock clock;
  private String token;
  private Instant validUntil = Instant.EPOCH;

  ExecCredentials(Spec spec, Clock clock) {
    this.spec = spec;
    this.clock = clock;
  }

  Spec spec() {
    return spec;
  }

  /**
   * The SHA-256 of what runs: program path, arguments, environment variables (names and values: a changed value can
   * change what the command does) and the API version of the exchange.
   */
  static String fingerprint(Spec spec) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("apiVersion", spec.apiVersion());
    n.put("command", spec.command().toString());
    ArrayNode args = n.putArray("args");
    spec.args().forEach(args::add);
    ObjectNode env = n.putObject("env");
    spec.env().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> env.put(e.getKey(), e.getValue()));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(n));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Reads {@code user.exec}; the program is looked up now, so the fingerprint names the file that will run.
   *
   * @param cluster what {@code provideClusterInfo} hands the command ({@code server}, CA data), or {@code null}
   */
  static Spec spec(JsonNode exec, Path kubeconfigDir, ObjectNode cluster) throws ConnectorException {
    String apiVersion = exec.path("apiVersion").asString("");
    if (!apiVersion.equals("client.authentication.k8s.io/v1")
        && !apiVersion.equals("client.authentication.k8s.io/v1beta1")) {
      throw ConnectorException.badInput("The credential command of the kubeconfig uses apiVersion '" + apiVersion
          + "'; client.authentication.k8s.io/v1 and v1beta1 are supported.");
    }
    String command = exec.path("command").asString("");
    if (command.isBlank()) {
      throw ConnectorException.badInput("The credential command of the kubeconfig has no command.");
    }
    List<String> args = new ArrayList<>();
    for (JsonNode a : exec.path("args")) {
      args.add(a.asString());
    }
    Map<String, String> env = new java.util.LinkedHashMap<>();
    for (JsonNode e : exec.path("env")) {
      String name = e.path("name").asString("");
      if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
        throw ConnectorException.badInput("The credential command sets an environment variable with an invalid name.");
      }
      env.put(name, e.path("value").asString(""));
    }
    Path workDir = kubeconfigDir == null ? Path.of(System.getProperty("user.home")) : kubeconfigDir;
    return new Spec(apiVersion, resolve(command, kubeconfigDir), List.copyOf(args), Map.copyOf(env),
        exec.path("provideClusterInfo").asBoolean(false) ? cluster : null, workDir);
  }

  /**
   * An absolute path as it is; a relative one with a slash against the kubeconfig's folder (as kubectl does); a bare
   * name in {@code PATH}, then where installers put these plugins — a desktop app doesn't inherit the shell's PATH.
   */
  static Path resolve(String command, Path kubeconfigDir) throws ConnectorException {
    Path p = Path.of(command);
    if (p.isAbsolute()) {
      return executable(p, command);
    }
    if (command.contains("/") || command.contains("\\")) {
      Path base = kubeconfigDir == null ? Path.of(System.getProperty("user.home")) : kubeconfigDir;
      return executable(base.resolve(p).normalize(), command);
    }
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
    for (String d : new String[] {"/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", home + "/google-cloud-sdk/bin",
        home + "/.local/bin", home + "/bin"}) {
      dirs.add(Path.of(d));
    }
    for (Path d : dirs) {
      Path candidate = d.resolve(command);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    throw ConnectorException.unavailable("The credential command " + command + " of the kubeconfig isn't installed "
        + "on this computer (not found in PATH, /usr/local/bin or /opt/homebrew/bin).", null);
  }

  private static Path executable(Path p, String command) throws ConnectorException {
    if (!Files.isRegularFile(p) || !Files.isExecutable(p)) {
      throw ConnectorException.unavailable("The credential command " + command + " of the kubeconfig isn't an "
          + "executable file on this computer.", null);
    }
    return p.toAbsolutePath().normalize();
  }

  /** A valid token, running the command when the cached one expired, within {@code budget}. */
  synchronized String token(Duration budget) throws ConnectorException {
    if (token != null && clock.instant().isBefore(validUntil)) {
      return token;
    }
    return refresh(budget.compareTo(TIMEOUT) < 0 ? budget : TIMEOUT);
  }

  /** After a 401 with the cached token: the next {@link #token} runs the command again. */
  synchronized void invalidate() {
    validUntil = Instant.EPOCH;
  }

  synchronized String current() {
    return token;
  }

  private String refresh(Duration budget) throws ConnectorException {
    if (budget.isZero() || budget.isNegative()) {
      throw ConnectorException.timeout("No time was left to run the credential command of the kubeconfig.");
    }
    List<String> argv = new ArrayList<>();
    argv.add(spec.command().toString());
    argv.addAll(spec.args());
    ProcessBuilder pb = new ProcessBuilder(argv).directory(spec.workDir().toFile());
    Map<String, String> env = pb.environment();
    String path = System.getenv("PATH");
    env.clear();
    if (path != null) {
      env.put("PATH", path);
    }
    env.put("HOME", System.getProperty("user.home"));
    env.putAll(spec.env());
    ObjectNode info = JsonNodeFactory.instance.objectNode();
    info.put("apiVersion", spec.apiVersion()).put("kind", "ExecCredential");
    ObjectNode infoSpec = info.putObject("spec").put("interactive", false);
    if (spec.cluster() != null) {
      infoSpec.set("cluster", spec.cluster());
    }
    env.put("KUBERNETES_EXEC_INFO", JSON.writeValueAsString(info));
    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      throw ConnectorException.unavailable("The credential command " + spec.command().getFileName()
          + " couldn't start: " + e.getMessage(), e);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Thread stdout = Thread.ofVirtual().start(() -> read(process.getInputStream(), out, MAX_OUTPUT));
    Thread stderr = Thread.ofVirtual().start(() -> read(process.getErrorStream(), err, 8192));
    try {
      process.getOutputStream().close();
      if (!process.waitFor(budget.toMillis(), TimeUnit.MILLISECONDS)) {
        kill(process);
        throw ConnectorException.timeout("The credential command " + spec.command().getFileName()
            + " didn't answer within " + Math.max(1, budget.toSeconds()) + " s. Run it in a terminal once to sign "
            + "in.");
      }
      stdout.join(1000);
      stderr.join(1000);
    } catch (InterruptedException e) {
      kill(process);
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The credential command was stopped.");
    } catch (IOException e) {
      kill(process);
      throw ConnectorException.unavailable("The credential command failed: " + e.getMessage(), e);
    }
    if (process.exitValue() != 0) {
      String first = OutputSanitizer.decode(err.toByteArray()).lines().filter(l -> !l.isBlank()).findFirst()
          .orElse("");
      throw ConnectorException.unavailable("The credential command " + spec.command().getFileName() + " exited with "
          + process.exitValue() + (first.isEmpty() ? "." : ": " + cut(first, 300)), null);
    }
    return parse(out.toByteArray());
  }

  private String parse(byte[] stdout) throws ConnectorException {
    JsonNode cred;
    try {
      cred = JSON.readTree(stdout);
    } catch (RuntimeException e) {
      throw ConnectorException.unavailable("The credential command printed something that isn't an ExecCredential.",
          null);
    }
    JsonNode status = cred.path("status");
    String t = status.path("token").asString(null);
    if (t == null || t.isBlank()) {
      if (status.has("clientCertificateData")) {
        throw ConnectorException.unavailable("The credential command returns a client certificate; MCP Gateway "
            + "supports commands that return a token.", null);
      }
      throw ConnectorException.unavailable("The credential command returned no token.", null);
    }
    Instant until = clock.instant().plus(NO_EXPIRY_CACHE);
    String exp = status.path("expirationTimestamp").asString(null);
    if (exp != null) {
      try {
        until = OffsetDateTime.parse(exp).toInstant().minus(EXPIRY_MARGIN);
      } catch (DateTimeParseException e) {
        // Treat a timestamp we can't read as no expiry: the next 401 runs the command again.
      }
    }
    this.token = t;
    this.validUntil = until;
    return t;
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
    }
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
