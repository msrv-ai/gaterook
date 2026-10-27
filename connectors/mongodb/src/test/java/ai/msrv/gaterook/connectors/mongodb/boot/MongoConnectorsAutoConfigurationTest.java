package ai.msrv.gaterook.connectors.mongodb.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class MongoConnectorsAutoConfigurationTest {

  @Test
  void isListedInTheModulesAutoConfigurationImports() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
        .contains(MongoConnectorsAutoConfiguration.class.getName());
  }

  @Test
  void registersTheConnector() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MongoConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id())
            .containsExactly("mongodb"));
  }

  @Test
  void theTestDnsServerIsHonouredOnlyWhenTheCoreSaysItIsNoRelease() {
    MockEnvironment dev = new MockEnvironment().withProperty("gateway.test.dns-server", "127.0.0.1:5353")
        .withProperty("gateway.release", "false");
    MockEnvironment release = new MockEnvironment().withProperty("gateway.test.dns-server", "127.0.0.1:5353")
        .withProperty("gateway.release", "true");
    MockEnvironment unknown = new MockEnvironment().withProperty("gateway.test.dns-server", "127.0.0.1:5353");

    assertThat(MongoConnectorsAutoConfiguration.testDnsServer(dev))
        .isEqualTo(new InetSocketAddress("127.0.0.1", 5353));
    assertThat(MongoConnectorsAutoConfiguration.testDnsServer(release)).isNull();
    // Without the core's word the build counts as a release.
    assertThat(MongoConnectorsAutoConfiguration.testDnsServer(unknown)).isNull();
    assertThat(MongoConnectorsAutoConfiguration.testDnsServer(new MockEnvironment())).isNull();
  }

  @Test
  void schemasAndTheWindowsDnsFallbackAreNativeImageHints() {
    RuntimeHints hints = new RuntimeHints();
    new MongoHints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/mongodb/mongo_find.input.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/mongodb/config.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of("com.sun.jndi.dns.DnsContextFactory"))
        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)).accepts(hints);
  }
}
