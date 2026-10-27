package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.connector.api.Env;
import jakarta.persistence.Converter;

/** Column form of {@link Env}: {@code dev}, {@code stage}, {@code prod} (the CHECK constraints of V1). */
@Converter
public final class EnvConverter extends KebabCaseEnumConverter<Env> {

  public EnvConverter() {
    super(Env.values());
  }
}
