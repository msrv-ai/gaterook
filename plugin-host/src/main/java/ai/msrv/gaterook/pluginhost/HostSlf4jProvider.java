package ai.msrv.gaterook.pluginhost;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * slf4j for the plugin and PF4J (report 16 §7: "the host supplies the slf4j provider"): {@code org.slf4j} is parent-first, so a
 * plugin that logs through slf4j lands here, and every line becomes a {@code log} notification the core masks before
 * it reaches {@code logs/plugins/<id>.log}. No MDC: a plugin's context belongs in the message, which is masked.
 */
public final class HostSlf4jProvider implements SLF4JServiceProvider {

  private final ILoggerFactory loggers = new Factory();
  private final IMarkerFactory markers = new BasicMarkerFactory();
  private final MDCAdapter mdc = new NOPMDCAdapter();

  @Override
  public ILoggerFactory getLoggerFactory() {
    return loggers;
  }

  @Override
  public IMarkerFactory getMarkerFactory() {
    return markers;
  }

  @Override
  public MDCAdapter getMDCAdapter() {
    return mdc;
  }

  @Override
  public String getRequestedApiVersion() {
    return "2.0.99";
  }

  @Override
  public void initialize() {
    // Nothing to set up: the peer attaches later, lines before that go to stderr.
  }

  private static final class Factory implements ILoggerFactory {
    private final ConcurrentMap<String, Logger> byName = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
      return byName.computeIfAbsent(name, HostLogger::new);
    }
  }

  /** One named logger; the level is the host's, read on every line, so {@code host/hello} can change it. */
  static final class HostLogger extends LegacyAbstractLogger {
    private static final long serialVersionUID = 1L;

    HostLogger(String name) {
      this.name = name;
    }

    @Override
    public boolean isTraceEnabled() {
      return HostLog.enabled(System.Logger.Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
      return HostLog.enabled(System.Logger.Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
      return HostLog.enabled(System.Logger.Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
      return HostLog.enabled(System.Logger.Level.WARNING);
    }

    @Override
    public boolean isErrorEnabled() {
      return HostLog.enabled(System.Logger.Level.ERROR);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
      return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern, Object[] arguments,
        Throwable throwable) {
      String text = MessageFormatter.basicArrayFormat(messagePattern, arguments);
      if (throwable != null) {
        text = text + " " + throwable;
      }
      HostLog.line(name, switch (level) {
        case TRACE -> System.Logger.Level.TRACE;
        case DEBUG -> System.Logger.Level.DEBUG;
        case INFO -> System.Logger.Level.INFO;
        case WARN -> System.Logger.Level.WARNING;
        case ERROR -> System.Logger.Level.ERROR;
      }, text);
    }
  }
}
