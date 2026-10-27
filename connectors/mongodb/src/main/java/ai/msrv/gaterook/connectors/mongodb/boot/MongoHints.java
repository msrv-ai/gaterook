package ai.msrv.gaterook.connectors.mongodb.boot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the MongoDB connector; they live with the module, not in the core (ARCHITECTURE.md §2.3).
 * The driver ships its own reachability metadata (codecs, its run-time initialized classes); SRV and TXT lookups go
 * through the connector's own DNS client, so the image needs no JNDI DNS provider on Linux and macOS.
 */
class MongoHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool, connection and policy schemas are read with getResourceAsStream: null in native without this hint.
    hints.resources().registerPattern("connectors/mongodb/*.schema.json");
    // Windows has no resolv.conf: there the lookups fall back to JNDI's DNS provider, which InitialContext
    // instantiates by name (UNVERIFIED in a Windows image: its resolver configuration is native code of the JDK).
    hints.reflection().registerTypeIfPresent(classLoader, "com.sun.jndi.dns.DnsContextFactory",
        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
  }
}
