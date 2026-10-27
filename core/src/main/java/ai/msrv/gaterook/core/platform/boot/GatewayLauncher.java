package ai.msrv.gaterook.core.platform.boot;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.NativeOs;
import ai.msrv.gaterook.core.platform.db.SqlitePools;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.aot.AbstractAotProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.modulith.NamedInterface;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code main} of the core (ARCHITECTURE.md §7.1, report 14 §7.2). Before Spring starts: stdout becomes the protocol
 * channel (everything else printed goes to stderr), the process stops being dumpable, the virtual-thread scheduler
 * gets enough carriers for the SQLite pools (and proves it has them), and the bootstrap line is read from stdin
 * (10 s). Then Spring runs with
 * the bootstrap's port; stdin EOF at any time ends the process.
 *
 * <p>Exit codes: 0 when Electron went away (stdin EOF), 2 when the start failed (the {@code ready:false} line says
 * why).
 *
 * <p>The only type of {@code platform.boot} other code may use: {@code GatewayApplication.main} launches the core,
 * tests take its {@link #contexts application contexts}.
 */
@NamedInterface("launcher")
public final class GatewayLauncher {

  /** How long the core waits for its bootstrap line before it gives up ({@code BOOTSTRAP_TIMEOUT}). */
  static final Duration BOOTSTRAP_TIMEOUT = Duration.ofSeconds(10);

  /** After stdin EOF the shutdown hooks get this long; then the process halts, whatever hangs. */
  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(20);

  static final int EXIT_FAILED = 2;

  /** How long the carrier check may spin when the scheduler has fewer carriers than asked (it then warns). */
  static final Duration CARRIER_CHECK_PATIENCE = Duration.ofMillis(500);

  private GatewayLauncher() {}

  public static void launch(Class<?> application, String[] args) {
    if (Boolean.getBoolean(AbstractAotProcessor.AOT_PROCESSING)) {
      // Spring AOT runs main() at build time to capture the bean definitions, and abandons the run by throwing from
      // a hook before the context refreshes: no stdin, no protocol, no exit, let its exception through.
      new SpringApplication(application).run(args);
      return;
    }
    PrintStream protocolOut = System.out;
    System.setOut(System.err);
    StartupProtocol protocol = StartupProtocol.install(protocolOut);

    boolean noDumps = NativeOs.disableCoreDumps();
    VirtualThreadScheduler.Parallelism parallelism =
        VirtualThreadScheduler.ensureAtLeast(SqlitePools.MAX_CONNECTIONS + 2);
    // The first virtual threads of the process: they create the scheduler with the property just set.
    int carriers = VirtualThreadScheduler.carriersAtOnce(parallelism.minimum(), CARRIER_CHECK_PATIENCE);

    Bootstrap bootstrap = null;
    if (!DevBootstrap.requestedByLauncher()) {
      StdinChannel stdin = StdinChannel.open(System.in, GatewayLauncher::parentGone);
      byte[] line = null;
      try {
        line = stdin.awaitLine(BOOTSTRAP_TIMEOUT);
        bootstrap = BootstrapParser.parse(line);
      } catch (GatewayStartupException e) {
        // Logging isn't configured yet: stderr is what Electron keeps of a core that never started.
        System.err.println("GateRook core: " + e.getMessage());
        ObjectNode extra = JsonNodeFactory.instance.objectNode();
        extra.put("log", defaultHome().resolve("logs").resolve("core.log").toString());
        protocol.failed(e.reason(), e.getMessage(), extra);
        System.exit(EXIT_FAILED);
        return;
      } finally {
        if (line != null) {
          Arrays.fill(line, (byte) 0);
        }
      }
    }
    LaunchContext.set(bootstrap, new LaunchContext.Hardening(noDumps, parallelism, carriers));
    prepareLogDirectory();

    SpringApplication spring = new SpringApplication(application);
    spring.setApplicationContextFactory(contexts(false));
    if (bootstrap != null) {
      spring.addInitializers(new PortsInitializer(bootstrap));
    }
    spring.addInitializers(new ReleaseFlagInitializer());
    spring.addListeners(new StartupReporter(protocol, bootstrap != null ? bootstrap.port() : 7465));
    try {
      spring.run(args);
    } catch (Throwable e) {
      // SpringApplication logged it and the reporter wrote the failure line; make sure Electron got one.
      if (!protocol.finished()) {
        protocol.failed(StartupReason.STARTUP_FAILED, "The core failed to start; see core.log", null);
      }
      System.exit(EXIT_FAILED);
    }
  }

  /**
   * The application contexts the core runs in: Spring's, with the by-type cache of {@link GatewayBeanFactory}. Tests
   * that start a core in their JVM use them too, with {@code verifyTypeCache} checking every cached answer.
   */
  public static ApplicationContextFactory contexts(boolean verifyTypeCache) {
    return GatewayBeanFactory.contexts(verifyTypeCache);
  }

  /** {@code -Dgateway.home}, else {@code ~/.gaterook}: the home the failure line points at before Spring runs. */
  static Path defaultHome() {
    String home = System.getProperty("gateway.home");
    return home != null ? Path.of(home) : Path.of(System.getProperty("user.home"), ".gaterook");
  }

  /**
   * Logging starts before any bean: create the home and its log folder owner-only now, or Logback creates them with
   * the default umask. A failure is left to {@code GatewayPaths}, which reports it properly.
   */
  private static void prepareLogDirectory() {
    try {
      GatewayPaths.ownerOnlyDirectory(defaultHome());
      GatewayPaths.ownerOnlyDirectory(defaultHome().resolve("logs"));
    } catch (IOException | RuntimeException e) {
      System.err.println("GateRook core: can't prepare " + defaultHome() + ": " + e.getMessage());
    }
  }

  /** stdin closed: Electron is gone. */
  private static void parentGone() {
    Thread watchdog = Thread.ofPlatform().name("gateway-exit-watchdog").daemon(true).unstarted(() -> {
      try {
        Thread.sleep(SHUTDOWN_GRACE);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      Runtime.getRuntime().halt(0);
    });
    watchdog.start();
    System.exit(0);
  }

  /**
   * The ports of the bootstrap override any configuration: Electron's {@code desktop.json} is their only source
   * (ARCHITECTURE.md §7.1). Highest-priority property source, added before the web server reads it.
   */
  /**
   * Whether this is a release, for connector modules ({@code gateway.release}): they can't see {@link BuildFlags},
   * yet must ignore their test overrides ({@code gateway.test.*}) in a release like the core does. Highest priority:
   * a {@code -Dgateway.release=false} on a release binary's command line doesn't change the answer.
   */
  private record ReleaseFlagInitializer() implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("gatewayBuild",
          Map.of("gateway.release", BuildFlags.RELEASE)));
    }
  }

  private record PortsInitializer(Bootstrap bootstrap) implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("gatewayBootstrap", Map.of(
          "server.port", bootstrap.port(),
          "gateway.public-port", bootstrap.publicPort())));
    }
  }
}
