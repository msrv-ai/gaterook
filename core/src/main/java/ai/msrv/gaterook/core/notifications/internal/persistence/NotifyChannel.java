package ai.msrv.gaterook.core.notifications.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** A notification channel: the desktop (Electron), Slack (Socket Mode) or Telegram (long polling). */
public enum NotifyChannel {
  DESKTOP, SLACK, TELEGRAM;

  /** The stored id ({@code desktop}). */
  public String id() {
    return KebabCaseEnumConverter.idOf(this);
  }

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<NotifyChannel> {
    public Converter() {
      super(values());
    }
  }
}
