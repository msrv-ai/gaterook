package com.gaterook.core.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.core.GatewayApplication;
import com.gaterook.core.platform.ProblemException;
import com.gaterook.core.platform.TestGateway;
import com.gaterook.core.platform.boot.GatewayLauncher;
import com.gaterook.core.secrets.SecretInfo;
import com.gaterook.core.secrets.SecretOwner;
import com.gaterook.core.secrets.SecretService;
import com.gaterook.testfixtures.FakeConnectors;
import com.gaterook.testfixtures.TestCore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rotate at the source through the real tester with a connector whose fake source behaves by host: only the source's
 * own refusal, or a failure before anything was sent, may destroy the new value. No answer within the deadline, a
 * connection that dropped while the change ran or a failure inside the connector after the change leave the outcome
 * unknown, and the new value, which the source may already use, is kept.
 */
class RotationAtTheSourceIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** The tester's deadline for this core: the slow source answers after it. */
  private static final Duration DEADLINE = Duration.ofSeconds(1);

  private static ConfigurableApplicationContext core;

  @BeforeAll
  static void start() {
    Path home = TestGateway.newHome("rotation-at-the-source");
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .sources(FakeConnectors.class, RotatingSourceConfiguration.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + TestGateway.LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s",
            "--gateway.clients.user-home=" + home.resolve("user-home"),
            "--gateway.connections.test-timeout=" + DEADLINE.toMillis() + "ms");
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void aChangeWhoseAnswerWasLostIsKeptAndConfirmedByATestAtOnce() {
    SecretInfo password = connection("lost-db", "lost.test");

    SecretService.ReplaceResult rotated = secrets().rotate(password.id(), null);

    assertThat(rotated.version()).as("the source took it: the test with it passes").isEqualTo(2);
    assertThat(secrets().get(password.id()).state()).isEqualTo("stored");
    assertThat(RotatingSource.ACCEPTS.get("lost.test")).isEqualTo(stored(password));
  }

  @Test
  void aFailureInsideTheConnectorAfterTheChangeKeepsTheNewValue() {
    SecretInfo password = connection("crash-db", "crash.test");

    SecretService.ReplaceResult rotated = secrets().rotate(password.id(), null);

    assertThat(rotated.version()).isEqualTo(2);
    assertThat(RotatingSource.ACCEPTS.get("crash.test")).isEqualTo(stored(password));
  }

  /**
   * The source applies the change after the tester stopped waiting: the new value is kept, a Rotate before the change
   * arrives keeps it (destroying it then would lock the account out), and one after it confirms it.
   */
  @Test
  void aChangeThatArrivesAfterTheDeadlineIsNeitherLostNorDestroyed() throws Exception {
    SecretInfo password = connection("slow-db", "slow.test");
    String before = RotatingSource.ACCEPTS.get("slow.test");

    assertThatThrownBy(() -> secrets().rotate(password.id(), null)).isInstanceOfSatisfying(ProblemException.class,
        e -> {
          assertThat(e.title()).isEqualTo(SecretService.UNCONFIRMED_ROTATION);
          assertThat(e.getMessage()).contains("No answer from slow-db within", "keeps it as version 2");
        });
    assertThat(secrets().get(password.id()).state()).isEqualTo("unconfirmed");
    assertThatThrownBy(() -> secrets().rotate(password.id(), null)).as("the change hasn't arrived yet")
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.getMessage())
            .contains("accepts version 1 and not version 2 yet"));
    TestCore.await(() -> !before.equals(RotatingSource.ACCEPTS.get("slow.test")), "the late change");

    SecretService.ReplaceResult settled = secrets().rotate(password.id(), null);

    assertThat(settled.version()).isEqualTo(2);
    assertThat(secrets().get(password.id()).state()).isEqualTo("stored");
    assertThat(RotatingSource.ACCEPTS.get("slow.test")).isEqualTo(stored(password));
  }

  @Test
  void theSourcesRefusalOrAFailureBeforeAnythingWasSentDestroysTheNewValue() {
    for (String host : List.of("refused.test", "down.test")) {
      SecretInfo password = connection(host.replace(".test", "-db"), host);
      String before = RotatingSource.ACCEPTS.get(host);

      assertThatThrownBy(() -> secrets().rotate(password.id(), null)).as(host)
          .isInstanceOfSatisfying(ProblemException.class, e -> {
            assertThat(e.title()).isEqualTo("The source refused");
            assertThat(e.getMessage()).contains("The current value stays in use.");
          });

      assertThat(secrets().get(password.id())).as(host).satisfies(info -> {
        assertThat(info.version()).isEqualTo(1);
        assertThat(info.state()).as("no version is left waiting").isEqualTo("stored");
      });
      assertThat(RotatingSource.ACCEPTS.get(host)).isEqualTo(before);
    }
  }

  private static SecretService secrets() {
    return core.getBean(SecretService.class);
  }

  /** A connection of the rotating type on {@code host}; its source accepts the password it was created with. */
  private static SecretInfo connection(String name, String host) {
    String password = "pw-" + name + "-0123";
    RotatingSource.ACCEPTS.put(host, password);
    ConnectionService.Summary created = core.getBean(ConnectionService.class).create(name, "rotfake", Env.DEV,
        TestCore.config(host), Map.of("/credentials/password", SecretInput.local(password.toCharArray())), "test");
    return secrets().listForOwner(SecretOwner.connection(created.id())).stream()
        .filter(s -> s.field().equals("/credentials/password")).findFirst().orElseThrow();
  }

  /** The current value of {@code password}, read as a call of its connection would. */
  private static String stored(SecretInfo password) {
    try (var resolver = secrets().resolver(password.owner(), List.of(password.field()),
        new com.gaterook.core.platform.Taint())) {
      return resolver.secret(password.field()).unsafeString();
    } catch (com.gaterook.connector.api.SecretUnavailableException e) {
      throw new AssertionError(e);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class RotatingSourceConfiguration {
    @Bean
    Connector rotatingSource() {
      return new RotatingSource();
    }
  }

  /**
   * A database whose password the gateway can change, by host: {@code lost.test} takes the change and the answer is
   * lost, {@code crash.test} takes it and the connector then fails, {@code slow.test} takes it after the deadline,
   * {@code refused.test} refuses it and {@code down.test} can't be reached before anything is sent.
   */
  static final class RotatingSource implements Connector, SecretResolver.CredentialRotator {
    static final Map<String, String> ACCEPTS = new ConcurrentHashMap<>();

    private final ConnectorManifest manifest = ConnectorManifest.builder("rotfake", "1.0.0",
            ConnectorManifest.Kind.BUILT_IN).name("RotatingSource").group("Databases")
        .description("A source that can change its password, for tests.").configSchema(JSON.readTree("""
            {"type":"object","additionalProperties":false,"required":["connection","credentials"],"properties":{
             "connection":{"type":"object","additionalProperties":false,"required":["host"],"properties":{
               "host":{"type":"string","minLength":1},"port":{"type":"integer","minimum":1}}},
             "credentials":{"type":"object","additionalProperties":false,"required":["user","password"],
               "properties":{"user":{"type":"string"},
                 "password":{"type":"string","writeOnly":true,"format":"password"}}}}}"""))
        .tools(List.of()).build();

    @Override
    public ConnectorManifest manifest() {
      return manifest;
    }

    @Override
    public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
      return Classification.read();
    }

    @Override
    public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "", cfg.name(), call.tool());
    }

    @Override
    public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
      try {
        String password = secrets.secret("/credentials/password").unsafeString();
        return password.equals(ACCEPTS.get(cfg.string("/connection/host")))
            ? TestResult.success(1, "Fake 1.0", "app", null)
            : TestResult.failure("auth", "password authentication failed", null);
      } catch (com.gaterook.connector.api.SecretUnavailableException e) {
        return TestResult.failure("secret", e.getMessage(), null);
      }
    }

    @Override
    public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
        throws ConnectorException {
      throw ConnectorException.badInput("No tools.");
    }

    @Override
    public boolean canRotate(ConnectionConfig cfg, String field) {
      return "/credentials/password".equals(field);
    }

    @Override
    public void rotate(ConnectionConfig cfg, SecretResolver current, String field, char[] next, ExecContext ctx)
        throws ConnectorException {
      String host = cfg.string("/connection/host");
      String value = new String(next);
      switch (host) {
        case "lost.test" -> {
          ACCEPTS.put(host, value);
          throw ConnectorException.outcomeUnknown("The connection to " + cfg.name() + " failed while it changed "
              + "the password.", null);
        }
        case "crash.test" -> {
          ACCEPTS.put(host, value);
          throw new IllegalStateException("A failure after the change");
        }
        case "slow.test" -> {
          // Already on its way: the cancel doesn't stop it, it arrives after the deadline.
          long until = System.nanoTime() + DEADLINE.multipliedBy(3).toNanos();
          while (System.nanoTime() < until) {
            try {
              Thread.sleep(50);
            } catch (InterruptedException e) {
              // Cancelled by the tester: the change still arrives.
            }
          }
          ACCEPTS.put(host, value);
        }
        case "refused.test" -> throw ConnectorException.denied("permission denied to change the password");
        case "down.test" -> throw ConnectorException.unavailable("connection refused", null);
        default -> ACCEPTS.put(host, value);
      }
    }
  }
}
