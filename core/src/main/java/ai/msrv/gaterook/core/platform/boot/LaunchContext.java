package ai.msrv.gaterook.core.platform.boot;

import ai.msrv.gaterook.core.platform.Bootstrap;
import java.util.Optional;

/**
 * What the launcher learned before Spring started, for the beans that need it: the bootstrap read from stdin and the
 * outcome of the process hardening (logged once logging is up). Empty when the context was started some other way
 * (tests).
 */
final class LaunchContext {

  /**
   * @param carriersAtOnce virtual threads seen running at the same time by the launcher's check, at most the
   *     parallelism's minimum: fewer means the scheduler ignored the property
   */
  record Hardening(boolean coreDumpsDisabled, VirtualThreadScheduler.Parallelism parallelism, int carriersAtOnce) {

    boolean carriersVerified() {
      return carriersAtOnce >= parallelism.minimum();
    }
  }

  private static volatile Bootstrap bootstrap;
  private static volatile Hardening hardening;

  private LaunchContext() {}

  static void set(Bootstrap fromStdin, Hardening facts) {
    bootstrap = fromStdin;
    hardening = facts;
  }

  static Optional<Bootstrap> bootstrap() {
    return Optional.ofNullable(bootstrap);
  }

  static Optional<Hardening> hardening() {
    return Optional.ofNullable(hardening);
  }
}
