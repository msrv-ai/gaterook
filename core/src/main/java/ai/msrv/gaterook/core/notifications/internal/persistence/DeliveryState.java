package ai.msrv.gaterook.core.notifications.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code notify_delivery.state}; {@code resolved} once the message was updated with the decision. */
public enum DeliveryState {
  SENDING, SENT, RESOLVED, FAILED;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<DeliveryState> {
    public Converter() {
      super(values());
    }
  }
}
