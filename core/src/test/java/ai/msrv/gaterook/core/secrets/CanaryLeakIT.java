package ai.msrv.gaterook.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.platform.TestHttp;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The canary test of the "the agent never sees a secret" invariant (ARCHITECTURE.md §9, report 12 §8.5), on a fake
 * connector: unique {@code cnry-…} values are stored, resolved through {@link ScopedSecretResolver}, and echoed back by
 * the fake in raw and encoded forms, together with a derived token it tainted itself. The test then looks for every
 * canary form in what leaves the core: the masked result and error (what the invoker hands to MCP), the {@code /api/**}
 * responses, the events, the log file (with SQL binds and Spring MVC at debug level), every byte under the gateway
 * home ({@code gateway.db}, {@code -wal}, the keyset) and the {@code toString()}/Jackson views of the secret objects.
 * The file key Electron sends to {@code /internal/keyset/protect} is a canary too.
 *
 * <p>The invocation step here is the test's own: {@code ConnectorInvoker} and the {@code tool_call} audit arrive with
 * M1.4/M1.5 and extend this test to the MCP path.
 */
class CanaryLeakIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Logger INVOKER_LOG = LoggerFactory.getLogger("ai.msrv.gaterook.core.invocation.ConnectorInvoker");

  private static ConfigurableApplicationContext core;
  private static Path home;
  private static FileAppender<ILoggingEvent> tap;

  // Characters that every encoding changes, so each form is a distinct string to find; '!', '~' and 'ä' are where
  // encodeURIComponent, RFC 3986 and ASCII-only JSON differ from Java's URLEncoder and Jackson.
  private static final String PASSWORD = canary("pw") + "/+=&\"!~\u00e4";
  private static final String TOKEN = canary("tok");
  private static final String REPLACED = canary("put") + "?#%";
  /** A numeric secret (a PIN, an account number): echoed as a JSON number, not a string. */
  private static final String PIN = "7" + String.format("%014d", new SecureRandom().nextLong(100_000_000_000_000L));
  private static final String USERNAME = "svc-app";

  @BeforeAll
  static void start() {
    home = TestGateway.newHome("canary");
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .initializers(ctx -> ctx.getBeanFactory().registerSingleton("testConnectionOwners",
            new SecretsIT.TestOwners()))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + TestGateway.LAUNCH_SECRET,
            "--server.port=0", "--logging.file.name=" + home.resolve("logs/core.log"),
            "--logging.level.org.hibernate.orm.jdbc.bind=TRACE", "--logging.level.org.hibernate.SQL=DEBUG",
            "--logging.level.org.springframework.web=DEBUG", "--logging.level.ai.msrv.gaterook=DEBUG");
    tap = tap(core.getEnvironment().getRequiredProperty("logging.pattern.file"), home.resolve("logs/tap.log"));
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
      core = null;
    }
    if (tap != null) {
      ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(tap);
      tap.stop();
      tap = null;
    }
  }

  /**
   * A file appender with the core's configured file pattern ({@code %mask(…)}). Boot sets up Logback once per JVM:
   * when another test's cached context did it first, {@code logging.file.name} above is ignored, so the test taps
   * the root logger itself to see every line the core writes.
   */
  private static FileAppender<ILoggingEvent> tap(String pattern, Path file) {
    LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(logback);
    encoder.setPattern(pattern);
    encoder.start();
    FileAppender<ILoggingEvent> appender = new FileAppender<>();
    appender.setContext(logback);
    appender.setName("canary-tap");
    appender.setFile(file.toString());
    appender.setEncoder(encoder);
    appender.start();
    logback.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    return appender;
  }

  @Test
  void noCanaryFormLeavesTheCore() throws Exception {
    SecretService secrets = core.getBean(SecretService.class);
    SecretOwner owner = SecretOwner.connection("billing-api");
    secrets.createLocal(owner, "/credentials/login", "billing-api/login",
        ("{\"username\":\"" + USERNAME + "\",\"password\":" + JSON.writeValueAsString(PASSWORD) + "}").toCharArray());
    SecretInfo token = secrets.createLocal(owner, "/credentials/token", "billing-api/token", TOKEN.toCharArray());
    secrets.createLocal(owner, "/credentials/pin", "billing-api/pin", PIN.toCharArray());

    // Replace over HTTP, as the UI does: the value is in the request body, never in a response.
    TestHttp http = http();
    List<String> responses = new ArrayList<>();
    HttpResponse<String> put = http.signed("PUT", "/api/secrets/" + token.id() + "/value",
        "{\"value\":" + JSON.writeValueAsString(REPLACED) + ",\"testBefore\":true,\"reason\":\"canary\"}");
    assertThat(put.statusCode()).isEqualTo(200);
    responses.add(put.body());
    HttpResponse<String> malformed = http.signed("PUT", "/api/secrets/" + token.id() + "/value",
        "{\"value\":" + JSON.writeValueAsString(REPLACED) + ",\"testBefore\":\"maybe\"");
    assertThat(malformed.statusCode()).isEqualTo(400);
    responses.add(malformed.body());
    HttpResponse<String> unknown = http.signed("PUT", "/api/secrets/sec_missing/value",
        "{\"value\":" + JSON.writeValueAsString(REPLACED) + "}");
    assertThat(unknown.statusCode()).isEqualTo(404);
    responses.add(unknown.body());
    // The key that decrypts master.keyset, as Electron sends it when OS protection is switched on (and off again).
    byte[] fileKeyBytes = new byte[32];
    new SecureRandom().nextBytes(fileKeyBytes);
    String fileKey = Base64.getUrlEncoder().withoutPadding().encodeToString(fileKeyBytes);
    for (String body : List.of("{\"fileKey\":\"" + fileKey + "\"}", "{\"fileKey\":null}")) {
      HttpResponse<String> protect = http.signed("POST", "/internal/keyset/protect", body);
      assertThat(protect.statusCode()).as(body).isEqualTo(204);
      responses.add(protect.body());
    }
    for (String target : List.of("/api/secrets", "/api/secrets/short-lived", "/api/master-key")) {
      HttpResponse<String> response = http.signedGet(target);
      assertThat(response.statusCode()).as(target).isEqualTo(200);
      responses.add(response.body());
    }

    // One call of the fake, as the invoker will run it: resolver + taint → connector → masker.
    Masker masker = core.getBean(Masker.class);
    MaskPolicy policy = MaskPolicy.defaults(Env.DEV);
    Taint taint = new Taint();
    String mcpResult;
    String mcpError;
    String derived;
    try (ScopedSecretResolver resolver = secrets.resolver(owner, Set.of("/credentials/login", "/credentials/token",
        "/credentials/pin"), taint)) {
      EchoConnector fake = new EchoConnector();
      ToolResult raw = fake.echo(resolver, context(taint));
      derived = fake.derived;
      assertThat(JSON.writeValueAsString(raw)).as("the fake really echoes the canaries").contains(REPLACED)
          .contains(derived).contains("\"account\":" + PIN).contains(uriComponent(PASSWORD));
      Masker.MaskedResult masked = masker.result(raw, taint, policy);
      assertThat(masked.result().maskedCount()).as("fields: the text; the link's uri and name; five structured "
          + "values, two property names and a number; two column names; three cells; meta").isEqualTo(17);
      mcpResult = JSON.writeValueAsString(masked.result());
      INVOKER_LOG.info("tool_call tc_canary ok: {}", mcpResult);

      ConnectorException failure = fake.failWithDerivedToken(resolver);
      assertThat(failure.getMessage()).contains(derived);
      mcpError = masker.text(failure.getMessage(), taint, policy).text();
      INVOKER_LOG.warn("tool_call tc_canary failed: {}", mcpError);

      // The secret objects themselves.
      SecretResolver.Login login = resolver.login("/credentials/login");
      SecretResolver.Secret secret = resolver.secret("/credentials/token");
      responses.add(login.toString());
      responses.add(secret.toString());
      responses.add(resolver.toString());
      responses.add(secrets.get(token.id()).toString());
      assertThatThrownBy(() -> JSON.writeValueAsString(secret)).hasMessageContaining("never serialized");
      assertThatThrownBy(() -> JSON.writeValueAsString(login)).hasMessageContaining("never serialized");
    }
    // A connection string in a log line without any taint: the %mask converter's url-credentials rule.
    LoggerFactory.getLogger("ai.msrv.gaterook.connectors.fake").info("connecting to postgres://{}:{}@db.internal/app",
        USERNAME, URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8));

    List<String> forms = forms(PASSWORD, TOKEN, REPLACED, derived, PIN, fileKey);
    forms.add(Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
    forms.add(Base64.getEncoder().withoutPadding().encodeToString(fileKeyBytes));
    forms.add(HexFormat.of().formatHex(fileKeyBytes));
    assertThat(mcpResult).contains(Masker.MASK);
    assertNoCanary("MCP result", mcpResult, forms);
    assertThat(mcpError).contains(Masker.MASK);
    assertNoCanary("MCP error", mcpError, forms);
    for (String response : responses) {
      assertNoCanary("response or toString", response, forms);
    }
    try (EventBus.Subscription events = core.getBean(EventBus.class).subscribe(OptionalLong.of(0))) {
      Optional<EventBus.GatewayEvent> event;
      while ((event = events.next(Duration.ofMillis(100))).isPresent()) {
        assertNoCanary("event " + event.get().type(), JSON.writeValueAsString(event.get()), forms);
      }
    }

    stop();
    String logText = Files.readString(home.resolve("logs/tap.log"));
    assertThat(logText).as("the log has SQL binds, Spring MVC's bodies and the invoker's lines")
        .contains("tool_call tc_canary").contains("binding parameter").contains("postgres://" + USERNAME + ":")
        .contains("Protect[fileKey=‹redacted›]");
    try (Stream<Path> files = Files.walk(home)) {
      List<Path> all = files.filter(Files::isRegularFile).toList();
      assertThat(all).extracting(p -> p.getFileName().toString()).contains("gateway.db", "master.keyset", "tap.log");
      for (Path file : all) {
        assertNoCanary(home.relativize(file).toString(), new String(Files.readAllBytes(file),
            StandardCharsets.ISO_8859_1), forms);
      }
    }
  }

  // ── the fake ───────────────────────────────────────────────────────────────────────────────────────────────

  /** Echoes its secrets back in every shape a careless connector or a hostile resource could. */
  static final class EchoConnector {
    String derived;

    ToolResult echo(SecretResolver secrets, ExecContext ctx) throws Exception {
      String password;
      try (SecretResolver.Login login = secrets.login("/credentials/login")) {
        password = login.password().unsafeString();
      }
      String token;
      try (SecretResolver.Secret secret = secrets.secret("/credentials/token")) {
        token = secret.unsafeString();
      }
      long pin;
      try (SecretResolver.Secret secret = secrets.secret("/credentials/pin")) {
        pin = Long.parseLong(secret.unsafeString());
      }
      // A credential the connector derives itself (an STS session token, an OAuth access token): it must taint it.
      derived = "drv" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
      ctx.taint(derived);

      String text = String.join("\n",
          "raw " + password + " " + token,
          "base64 " + b64(password) + " " + b64(token),
          "base64url " + b64url(password) + " " + b64url(token),
          "url " + URLEncoder.encode(password, StandardCharsets.UTF_8) + " "
              + URLEncoder.encode(token, StandardCharsets.UTF_8),
          "json " + JSON.writeValueAsString(password),
          "uri-component " + uriComponent(password),
          "ascii-json " + asciiJson(password),
          "php-json " + asciiJson(password).replace("/", "\\/"),
          "Authorization: Basic " + b64(USERNAME + ":" + password),
          "Authorization: Bearer " + derived);
      ObjectNode structured = JsonNodeFactory.instance.objectNode();
      structured.putObject("echo").put("raw", password).put("url", URLEncoder.encode(token, StandardCharsets.UTF_8))
          .putArray("nested").add(b64(token)).addObject().put("deeper", derived);
      structured.put("note", "session " + derived + " for " + USERNAME);
      // Channels other than string values: a hash keyed by tokens (Redis, MongoDB) and a numeric secret.
      structured.putObject("sessions").put(token, "alice").put(derived, "bob");
      structured.put("account", pin);
      var rows = JsonNodeFactory.instance.arrayNode();
      rows.addArray().add("password").add(password).add(pin);
      rows.addArray().add("token_b64url").add(b64url(token)).add("plain");
      ToolResult.Table table = new ToolResult.Table(List.of(
          new ToolResult.Column("name", "text", null, null, null),
          new ToolResult.Column("value", "text", null, null, null),
          // A column named by what the query returned, from a table named after the derived token.
          new ToolResult.Column(token, "int8", "public", "t_" + derived, null)), rows, null);
      ObjectNode meta = JsonNodeFactory.instance.objectNode().put("debug", token);
      // A presigned link carries the derived session token; its name echoes the stored one.
      ToolResult.Content link = new ToolResult.Content("resource_link", null,
          "https://billing.example/export.csv?X-Amz-Security-Token=" + URLEncoder.encode(derived, StandardCharsets.UTF_8)
              + "&X-Amz-Expires=900", "text/csv", "export-" + token + ".csv");
      return new ToolResult(List.of(ToolResult.Content.text(text), link), structured, false, table, false, null,
          List.of(), 0, 0, meta);
    }

    ConnectorException failWithDerivedToken(SecretResolver secrets) throws Exception {
      String password;
      try (SecretResolver.Login login = secrets.login("/credentials/login")) {
        password = login.password().unsafeString();
      }
      return ConnectorException.resourceError("401 from billing: token " + derived + " rejected for " + USERNAME
          + " (password " + URLEncoder.encode(password, StandardCharsets.UTF_8) + ")", null);
    }
  }

  /** The two context methods the fake uses; the invoker of M1.4 implements the rest. */
  private static ExecContext context(Taint taint) {
    return (ExecContext) Proxy.newProxyInstance(ExecContext.class.getClassLoader(), new Class<?>[] {ExecContext.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "taint" -> {
            taint.add((CharSequence) args[0]);
            yield null;
          }
          case "callId" -> "tc_canary";
          case "env" -> Env.DEV;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

  private static String canary(String kind) {
    byte[] bytes = new byte[12];
    new SecureRandom().nextBytes(bytes);
    return "cnry-" + kind + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static List<String> forms(String... values) throws Exception {
    List<String> forms = new ArrayList<>();
    for (String value : values) {
      Map<String, String> encodings = new LinkedHashMap<>();
      encodings.put("raw", value);
      encodings.put("base64", b64(value));
      encodings.put("base64url", b64url(value));
      encodings.put("url", URLEncoder.encode(value, StandardCharsets.UTF_8));
      encodings.put("json", JSON.writeValueAsString(value).substring(1, JSON.writeValueAsString(value).length() - 1));
      encodings.put("uri-component", uriComponent(value));
      encodings.put("ascii-json", asciiJson(value).substring(1, asciiJson(value).length() - 1));
      forms.addAll(encodings.values());
    }
    return forms;
  }

  private static void assertNoCanary(String where, String text, List<String> forms) {
    for (String form : forms) {
      assertThat(text.contains(form)).as("a canary form leaked into " + where + ": " + form).isFalse();
    }
    assertThat(text).as(where).doesNotContain("cnry-pw-").doesNotContain("cnry-tok-").doesNotContain("cnry-put-")
        .doesNotContain(PIN);
  }

  /** JavaScript's {@code encodeURIComponent}: keeps letters, digits and {@code -_.!~*'()}. */
  private static String uriComponent(String value) {
    StringBuilder out = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if (c < 0x80 && (Character.isLetterOrDigit(c) || "-_.!~*'()".indexOf(c) >= 0)) {
        out.append((char) c);
      } else {
        out.append('%').append(String.format("%02X", c));
      }
    }
    return out.toString();
  }

  /** A JSON string as Python's {@code json.dumps} writes it: non-ASCII as lower-case {@code \\uXXXX}. */
  private static String asciiJson(String value) throws Exception {
    StringBuilder out = new StringBuilder();
    for (char c : JSON.writeValueAsString(value).toCharArray()) {
      out.append(c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c));
    }
    return out.toString();
  }

  private static String b64(String value) {
    return Base64.getEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String b64url(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static TestHttp http() {
    int port = ((WebServerApplicationContext) core).getWebServer().getPort();
    return new TestHttp(port, TestGateway.launchSecret());
  }
}
