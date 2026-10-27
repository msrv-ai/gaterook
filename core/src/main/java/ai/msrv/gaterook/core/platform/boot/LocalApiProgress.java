package ai.msrv.gaterook.core.platform.boot;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;

/**
 * Progress of the {@code local-api} step. The web server binds its port when its own lifecycle starts, after every
 * bean exists; this lifecycle runs in an earlier phase, so "running" means "binding 127.0.0.1:&lt;port&gt; now".
 */
final class LocalApiProgress implements SmartLifecycle, ApplicationListener<WebServerInitializedEvent> {

  /** Before Boot's web server start/stop lifecycle ({@code DEFAULT_PHASE - 2048}). */
  private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

  private volatile boolean running;

  @Override
  public void start() {
    running = true;
    StartupProtocol.current().running(StartupStep.LOCAL_API);
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  @Override
  public void onApplicationEvent(WebServerInitializedEvent event) {
    StartupProtocol.current().done(StartupStep.LOCAL_API);
  }
}
