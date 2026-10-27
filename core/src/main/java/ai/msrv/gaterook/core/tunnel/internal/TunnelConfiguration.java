package ai.msrv.gaterook.core.tunnel.internal;

import ai.msrv.gaterook.core.platform.BuildFlags;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Wiring of the tunnel's agents: the pinned binaries ({@code gateway.resources} is the installer's bundle; a test of a
 * non-release build may pin its own with {@code gateway.test.tunnel-pins}) and the two agents. The self-test's probe
 * is looked up when used: tests of the core register their own.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(TunnelConfiguration.Hints.class)
class TunnelConfiguration {

  @Bean
  TunnelBinaries tunnelBinaries(@Value("${gateway.home}") Path home, @Value("${gateway.resources:}") String resources,
      @Value("${gateway.test.tunnel-pins:}") String testPins) {
    return new TunnelBinaries(home, resources, testPins);
  }

  /**
   * The agents with the waits of report 02 §7.3 (hostname 20 s, edge 30 s). A test of a non-release build may
   * shorten them ({@code gateway.test.tunnel-wait}, e.g. {@code 3s}), so that "never ready" doesn't take half a
   * minute; a release ignores it.
   */
  @Bean
  CloudflaredAgent cloudflaredAgent(@Value("${gateway.test.tunnel-wait:}") String testWait) {
    Duration wait = testWait(testWait);
    return wait == null ? new CloudflaredAgent() : new CloudflaredAgent(wait, wait);
  }

  @Bean
  NgrokAgent ngrokAgent(@Value("${gateway.test.tunnel-wait:}") String testWait) {
    Duration wait = testWait(testWait);
    return wait == null ? new NgrokAgent() : new NgrokAgent(wait);
  }

  private static Duration testWait(String value) {
    if (value == null || value.isBlank() || BuildFlags.RELEASE) {
      return null;
    }
    return DurationStyle.detectAndParse(value);
  }

  /** The pins file, read as a classpath resource in the native image. */
  static final class Hints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
      hints.resources().registerPattern("ai/msrv/gaterook/core/tunnel/binaries.json");
    }
  }
}
