package ai.msrv.gaterook.core.platform;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.context.ApplicationListener;

/**
 * The {@code %mask(…)} conversion word of the core's log patterns ({@code logging.pattern.file} and
 * {@code logging.pattern.console} in {@code application.yml}): every message and stack trace goes through
 * {@link Masker#log} before it reaches {@code core.log} or stderr (ARCHITECTURE.md §9, report 12 §6). No taint here —
 * the logger doesn't know the call — so the rules and entropy catch what they can; code never logs secret values in
 * the first place.
 *
 * <p>Registered from {@code META-INF/spring.factories} on {@link ApplicationStartingEvent}, before Boot parses the
 * patterns. The converter goes into Logback's supplier map, not a class name: nothing for the native image to
 * reflect on.
 */
public final class MaskingLogs implements ApplicationListener<ApplicationStartingEvent> {

  /** The conversion word. */
  public static final String WORD = "mask";

  @Override
  public void onApplicationEvent(ApplicationStartingEvent event) {
    register();
  }

  /** Makes {@code %mask} known to every Logback pattern of this JVM; idempotent. */
  public static void register() {
    synchronized (PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP) {
      PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.putIfAbsent(WORD, Converter::new);
    }
  }

  /** {@code %mask(<pattern>)}: the child pattern's output, masked. */
  public static final class Converter extends CompositeConverter<ILoggingEvent> {
    @Override
    protected String transform(ILoggingEvent event, String in) {
      return Masker.standard().log(in);
    }
  }
}
