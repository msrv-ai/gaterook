package ai.msrv.gaterook.core.platform.web;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.SettingStore;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;

/**
 * Browser origins allowed to call the local listener: Settings › Local API › Allowed origins (setting
 * {@code local-api}, empty by default), plus {@code http://localhost:4200} for {@code ng serve} in a development run.
 * Changing the setting restarts the core (ARCHITECTURE.md §6), so the list is read once, on first use: the guard never
 * touches the database before it is migrated, and a failure to read it allows no origin at all.
 */
final class AllowedOrigins {

  static final String SETTING = "local-api";
  static final String DEV_ORIGIN = "http://localhost:4200";

  private static final Logger log = LoggerFactory.getLogger(AllowedOrigins.class);

  private final ObjectProvider<SettingStore> settings;
  private final boolean dev;
  private volatile Set<String> origins;

  AllowedOrigins(ObjectProvider<SettingStore> settings, Bootstrap bootstrap) {
    this.settings = settings;
    this.dev = bootstrap.dev() && !BuildFlags.RELEASE;
  }

  boolean allows(String origin) {
    // "null" (sandboxed frames, file:, some redirects) is never a trusted origin, whatever the setting says.
    return !"null".equals(origin) && origins().contains(origin);
  }

  private Set<String> origins() {
    Set<String> current = origins;
    if (current == null) {
      synchronized (this) {
        current = origins;
        if (current == null) {
          current = load();
          origins = current;
        }
      }
    }
    return current;
  }

  private Set<String> load() {
    Set<String> result = new LinkedHashSet<>();
    if (dev) {
      result.add(DEV_ORIGIN);
    }
    try {
      settings.getObject().find(SETTING).map(s -> s.value().path("allowedOrigins")).filter(JsonNode::isArray)
          .ifPresent(list -> list.forEach(o -> {
            if (o.isString() && !o.asString().isBlank()) {
              result.add(o.asString().strip());
            }
          }));
    } catch (RuntimeException e) {
      log.warn("Can't read the allowed origins; no browser origin is allowed until the core restarts", e);
      return dev ? Set.of(DEV_ORIGIN) : Set.of();
    }
    return Set.copyOf(result);
  }
}
