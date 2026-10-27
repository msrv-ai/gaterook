package ai.msrv.gaterook.connector.api;

import java.util.Locale;

/** Environment of a connection or endpoint; prod is always the strictest. */
public enum Env {
  DEV, STAGE, PROD;

  /** Lower-case id as stored in the DB and shown in rule ids: {@code dev | stage | prod}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Env of(String id) {
    return valueOf(id.toUpperCase(Locale.ROOT));
  }
}
