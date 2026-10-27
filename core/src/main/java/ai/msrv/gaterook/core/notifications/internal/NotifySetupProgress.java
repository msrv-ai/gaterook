package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.platform.SetupProgress;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Overview's "Set up approval notifications" (ARCHITECTURE.md §6 overview): done once the person turned a channel on
 * themselves (desktop notifications are on by default, which isn't setting anything up); the line names the channels
 * that are on ("Desktop and Slack"); a channel in error needs attention.
 */
@Component
class NotifySetupProgress implements SetupProgress {

  private final ChannelStore channels;

  NotifySetupProgress(ChannelStore channels) {
    this.channels = channels;
  }

  @Override
  public String area() {
    return "notifications";
  }

  @Override
  public Progress progress() {
    List<String> on = new ArrayList<>();
    boolean saved = false;
    boolean attention = false;
    for (NotifyChannel c : NotifyChannel.values()) {
      ChannelStore.Row row = channels.read(c);
      saved |= row.stored() && row.enabled();
      if (row.active()) {
        on.add(Linking.title(c));
      }
      attention |= row.enabled() && ("error".equals(row.state()) || "stopped".equals(row.state())
          || !row.verified());
    }
    String status = on.isEmpty() ? null : on.size() == 1 ? on.get(0)
        : String.join(", ", on.subList(0, on.size() - 1)) + " and " + on.get(on.size() - 1);
    return new Progress(on.size(), saved, attention, status);
  }
}
