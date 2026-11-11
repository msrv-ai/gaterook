package ai.msrv.gaterook.connectors.cassandra.boot;

import ai.msrv.gaterook.connectors.cassandra.NeverRetry;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Cassandra connector: its schemas, and the retry policy the driver builds by its name. The
 * driver's own policies, {@code reference.conf} and {@code Driver.properties} are in the driver's
 * {@code META-INF/native-image} metadata; Netty brings its own.
 */
class CassandraHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/cassandra/*.schema.json");
    // The driver loads advanced.retry-policy.class by name and calls its (DriverContext, String) constructor.
    hints.reflection().registerType(NeverRetry.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
  }
}
