package ai.msrv.gaterook.core.audit.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.What;
import jakarta.persistence.Converter;

/** {@code setup_change.what} as {@link What#id()} spells it ({@code risk-warning}). */
@Converter
public final class SetupChangeWhatConverter extends KebabCaseEnumConverter<What> {

  public SetupChangeWhatConverter() {
    super(What.values());
  }
}
