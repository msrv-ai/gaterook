package ai.msrv.gaterook.core.notifications.internal.telegram;

import ai.msrv.gaterook.core.notifications.Eligibility;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Telegram message as plain text plus {@code entities} (report 13 §3): no {@code parse_mode}, so nothing in the
 * text needs escaping and the exact action stays literal in a {@code pre} entity. Offsets and lengths count UTF-16
 * code units, which is exactly {@code String.length()} in Java — emoji and other surrogate pairs count two, as the
 * Bot API wants.
 */
public final class TgText {

  private final StringBuilder text = new StringBuilder();
  private final ArrayNode entities = JsonNodeFactory.instance.arrayNode();

  public TgText text(String s) {
    text.append(s == null ? "" : s);
    return this;
  }

  /** {@code bold}, {@code code}: a run of text with one entity. */
  public TgText entity(String type, String s) {
    if (s == null || s.isEmpty()) {
      return this;
    }
    entities.addObject().put("type", type).put("offset", text.length()).put("length", s.length());
    text.append(s);
    return this;
  }

  /** A code block in {@code language}, or without one. */
  public TgText pre(String s, String language) {
    if (s == null || s.isEmpty()) {
      return this;
    }
    ObjectNode e = entities.addObject().put("type", "pre").put("offset", text.length()).put("length", s.length());
    if (language != null && !language.isBlank()) {
      e.put("language", language);
    }
    text.append(s);
    return this;
  }

  /** A time Telegram shows in the reader's zone ({@code date_time}, format {@code T}); {@code shown} is ours. */
  public TgText time(String shown, long unixSeconds) {
    entities.addObject().put("type", "date_time").put("offset", text.length()).put("length", shown.length())
        .put("unix_time", unixSeconds).put("date_time_format", "T");
    text.append(shown);
    return this;
  }

  /** UTF-16 code units, what Telegram counts. */
  public int length() {
    return text.length();
  }

  public boolean fits() {
    return text.length() <= Eligibility.TELEGRAM_MESSAGE_LIMIT;
  }

  public String text() {
    return text.toString();
  }

  public ArrayNode entities() {
    return entities.deepCopy();
  }
}
