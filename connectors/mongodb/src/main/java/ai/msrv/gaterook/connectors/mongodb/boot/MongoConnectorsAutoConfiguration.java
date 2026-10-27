package ai.msrv.gaterook.connectors.mongodb.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.mongodb.MongoConnector;
import java.net.InetSocketAddress;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;

/**
 * Registers the MongoDB connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched
 * on and off at run time by {@code connector_type.enabled}.
 *
 * <p>{@value #TEST_DNS_SERVER} ({@code host:port}) points the SRV and TXT lookups of {@code mongodb+srv://} at one DNS
 * server — a test's local stub (the native smoke's touch). Like every test override it is ignored by a release: the
 * core says whether it is one in {@code gateway.release} (its {@code BuildFlags}, which this module can't see), and
 * without that property the build counts as a release.
 */
@AutoConfiguration
@ImportRuntimeHints(MongoHints.class)
public class MongoConnectorsAutoConfiguration {

  static final String TEST_DNS_SERVER = "gateway.test.dns-server";

  @Bean
  Connector mongodbConnector(Environment environment) {
    InetSocketAddress dns = testDnsServer(environment);
    return dns == null ? new MongoConnector() : new MongoConnector(dns);
  }

  static InetSocketAddress testDnsServer(Environment environment) {
    String value = environment.getProperty(TEST_DNS_SERVER);
    if (value == null || value.isBlank()) {
      return null;
    }
    System.Logger log = System.getLogger(MongoConnectorsAutoConfiguration.class.getName());
    if (environment.getProperty("gateway.release", Boolean.class, true)) {
      log.log(System.Logger.Level.WARNING, TEST_DNS_SERVER + " is ignored: this is a release build");
      return null;
    }
    int colon = value.lastIndexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException(TEST_DNS_SERVER + " must be host:port, not " + value);
    }
    log.log(System.Logger.Level.WARNING, "mongodb+srv:// lookups go to the test DNS server " + value + " ("
        + TEST_DNS_SERVER + ")");
    return new InetSocketAddress(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
  }
}
