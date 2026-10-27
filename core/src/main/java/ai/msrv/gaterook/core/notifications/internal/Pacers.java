package ai.msrv.gaterook.core.notifications.internal;

import java.time.Clock;
import org.springframework.stereotype.Component;

/** The module's shared clocks: one for sends to a chat or channel, one for the flood and burst windows. */
@Component
public class Pacers {

  private final Pacer sends;
  private final Pacer flood;

  Pacers(Clock clock, NotifyConfig config) {
    this.sends = new Pacer(clock, config.pace());
    this.flood = new Pacer(clock, config.pace());
  }

  /** At most one message per pace to one target. */
  public Pacer sends() {
    return sends;
  }

  /** Request counts per channel within a window. */
  public Pacer flood() {
    return flood;
  }
}
