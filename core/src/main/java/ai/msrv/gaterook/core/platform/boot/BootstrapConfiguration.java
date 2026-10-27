package ai.msrv.gaterook.core.platform.boot;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.StartupFacts;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The {@link Bootstrap} bean: the line Electron sent on stdin, or a development bootstrap. */
@Configuration(proxyBeanMethods = false)
class BootstrapConfiguration {

  @Bean
  Bootstrap bootstrap(Environment environment) {
    return LaunchContext.bootstrap().orElseGet(() -> DevBootstrap.from(environment));
  }

  @Bean
  StartupFacts startupFacts(Clock clock) {
    return new StartupFacts(clock.instant());
  }

  /** "local-api running" just before the web server binds its port, "done" once it listens. */
  @Bean
  LocalApiProgress localApiProgress() {
    return new LocalApiProgress();
  }
}
