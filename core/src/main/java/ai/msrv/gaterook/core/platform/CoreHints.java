package ai.msrv.gaterook.core.platform;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * What Spring AOT can't infer for the core (report 15 §9). JPA entities, repositories, converters, configuration
 * properties and {@code @RequestBody} types need nothing here: AOT registers them. JDK proxies and FFM downcalls of
 * libraries live in {@code META-INF/native-image/ai.msrv.gaterook/core-manual} (and the Linux-only
 * {@code core-ffm-linux}), outside the directory AOT overwrites.
 *
 * <p>Rule 11 of report 15: every resource folder the core reads at run time gets a line here.
 */
public final class CoreHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // CoreBuild.VERSION: the filtered build version.
    hints.resources().registerPattern("ai/msrv/gaterook/core/platform/core.properties");
    hints.resources().registerPattern("ai/msrv/gaterook/core/mcp/apps/*");
    // The core's policy sections (Masking, Limits, Approvals).
    hints.resources().registerPattern("ai/msrv/gaterook/core/policy/*.schema.json");
    // GW_DEMO=1: the kit's demo world (admin.demo.DemoSeed); a non-release image needs it at run time.
    hints.resources().registerPattern("demo/seed.json");
  }
}
