package com.gaterook.core.audit.internal.persistence;

import com.gaterook.core.platform.KebabCaseEnumConverter;
import com.gaterook.core.platform.SetupChangeRecorder.What;
import jakarta.persistence.Converter;

/** {@code setup_change.what} as {@link What#id()} spells it ({@code risk-warning}). */
@Converter
public final class SetupChangeWhatConverter extends KebabCaseEnumConverter<What> {

  public SetupChangeWhatConverter() {
    super(What.values());
  }
}
