package com.gaterook.e2e.modules.fixture;

import com.gaterook.connector.api.Connector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The fixture's connectors as a build compiles them in: an auto-configuration that registers them as beans, the way
 * each connector of {@code connectors/} registers its own. {@link EchoPackages#compiledIn} packs it with the
 * connectors' classes, and the test of an update that turns a compiled-in connector into a module starts the build
 * before with that jar. Nothing on the test's own class path names it, so no other core has these connectors.
 */
@AutoConfiguration
public class EchoCompiledIn {

  @Bean
  Connector echoDbConnector() {
    return new EchoModule().connectors(null).get(0);
  }

  @Bean
  Connector echoLogConnector() {
    return new EchoModule().connectors(null).get(1);
  }
}
