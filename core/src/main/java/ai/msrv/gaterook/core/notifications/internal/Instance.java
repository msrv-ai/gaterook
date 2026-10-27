package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.SettingStore;
import java.net.InetAddress;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Which MCP Gateway this is, as its messages say (report 13 §6.3). Slack hands an interaction to any connection of
 * the app, so two computers sharing one Slack app would get each other's clicks: every button carries this
 * installation's id ({@code setting notifications.instance-id}, made once), and a click with another id is answered
 * "This request belongs to another MCP Gateway". The host name goes into texts people read ("Linked to MCP Gateway on
 * ravi-mbp").
 */
@Component
public class Instance {

  static final String KEY = "notifications.instance-id";

  private final SettingStore settings;
  private volatile String id;
  private volatile String host;

  Instance(SettingStore settings) {
    this.settings = settings;
  }

  public String id() {
    String current = id;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (id == null) {
        id = settings.find(KEY).map(s -> s.value().path("id").asString(null)).filter(v -> v != null && !v.isBlank())
            .orElseGet(() -> {
              String made = Ids.next("ins_");
              settings.put(KEY, JsonNodeFactory.instance.objectNode().put("id", made), 0L);
              return made;
            });
      }
      return id;
    }
  }

  /** This computer's short name ({@code ravi-mbp}), or "this computer". */
  public String host() {
    String current = host;
    if (current != null) {
      return current;
    }
    String name = System.getenv("HOSTNAME");
    if (name == null || name.isBlank()) {
      name = System.getenv("COMPUTERNAME");
    }
    if (name == null || name.isBlank()) {
      try {
        name = InetAddress.getLocalHost().getHostName();
      } catch (java.io.IOException | RuntimeException e) {
        name = null;
      }
    }
    if (name == null || name.isBlank() || name.equals("localhost")) {
      name = "this computer";
    } else {
      int dot = name.indexOf('.');
      name = (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }
    host = name.length() > 63 ? name.substring(0, 63) : name;
    return host;
  }
}
