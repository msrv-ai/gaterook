package ai.msrv.gaterook.core.platform.boot;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.CoreBuild;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.StartupFacts;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the ready line when the application is ready, or the failure line when it isn't (ARCHITECTURE.md §7.1).
 * Registered by the launcher on the {@code SpringApplication}, so it also sees failures before the context exists.
 */
final class StartupReporter implements ApplicationListener<SpringApplicationEvent> {

  private static final Logger log = LoggerFactory.getLogger(StartupReporter.class);

  private final StartupProtocol protocol;
  private final int configuredPort;

  StartupReporter(StartupProtocol protocol, int configuredPort) {
    this.protocol = protocol;
    this.configuredPort = configuredPort;
  }

  @Override
  public void onApplicationEvent(SpringApplicationEvent event) {
    if (event instanceof ApplicationReadyEvent ready) {
      ready(ready.getApplicationContext());
    } else if (event instanceof ApplicationFailedEvent failed) {
      failed(failed.getException(), failed.getApplicationContext());
    }
  }

  private void ready(ConfigurableApplicationContext context) {
    LaunchContext.hardening().ifPresent(h -> {
      log.info("Process hardening: core dumps {}; virtual thread scheduler parallelism {} (minimum {}{}), {} carriers "
          + "verified", h.coreDumpsDisabled() ? "disabled" : "not disabled on this system", h.parallelism().effective(),
          h.parallelism().minimum(), h.parallelism().raised() ? ", raised from " + h.parallelism().requested() : "",
          h.carriersAtOnce());
      if (!h.carriersVerified()) {
        // Pinned SQLite connections may now stall unrelated requests (report 14 §9.2); the core still works.
        log.warn("Only {} virtual threads ran at once, {} expected: the scheduler ignored {}", h.carriersAtOnce(),
            h.parallelism().minimum(), VirtualThreadScheduler.PARALLELISM);
      }
    });
    Bootstrap bootstrap = context.getBean(Bootstrap.class);
    StartupFacts facts = context.getBean(StartupFacts.class);
    MasterKeyLock lock = context.getBean(MasterKeyLock.class);
    int port = context instanceof WebServerApplicationContext web ? web.getWebServer().getPort() : bootstrap.port();

    ObjectNode line = JsonNodeFactory.instance.objectNode();
    line.put("firstRun", facts.firstRun());
    if (lock.reason().isEmpty()) {
      line.putNull("locked");
    } else {
      line.put("locked", lock.reason().get().name());
    }
    line.put("version", CoreBuild.VERSION);
    ObjectNode ports = line.putObject("ports");
    ports.put("local", port);
    ports.put("public", bootstrap.publicPort());
    line.put("runtime", CoreBuild.runtime());
    line.put("pid", ProcessHandle.current().pid());
    protocol.ready(line);
    log.info("MCP Gateway {} ({}) ready on 127.0.0.1:{}", CoreBuild.VERSION, CoreBuild.runtime(), port);
  }

  private void failed(Throwable exception, ConfigurableApplicationContext context) {
    StartupFailures.Failure failure = StartupFailures.classify(exception, configuredPort);
    // The failure line says "see core.log": the cause has to be there (Boot's own report doesn't reach it).
    log.error("The core failed to start ({}): {}", failure.reason(), failure.detail(), exception);
    ObjectNode extra = JsonNodeFactory.instance.objectNode();
    if (failure.port() != null) {
      PortOwner.of(failure.port()).ifPresent(owner -> {
        ObjectNode portOwner = extra.putObject("portOwner");
        portOwner.put("pid", owner.pid());
        portOwner.put("user", owner.user());
      });
    }
    extra.put("log", logFile(context).toString());
    protocol.failed(failure.reason(), failure.detail(), extra);
  }

  static Path logFile(ConfigurableApplicationContext context) {
    String file = context == null ? null : context.getEnvironment().getProperty("logging.file.name");
    return file != null ? Path.of(file) : GatewayLauncher.defaultHome().resolve("logs").resolve("core.log");
  }
}
