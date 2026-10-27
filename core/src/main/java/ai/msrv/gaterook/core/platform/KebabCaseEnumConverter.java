package ai.msrv.gaterook.core.platform;

import jakarta.persistence.AttributeConverter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stores an enum as the kebab-case form of its constant name ({@code GCP_SM} → {@code gcp-sm}), the spelling the
 * schema's CHECK constraints and the admin API use (ARCHITECTURE.md §4). Enums whose column holds the constant name
 * itself ({@code OFF}, {@code BUILT_IN}) use {@code EnumType.STRING} instead.
 *
 * <p>Subclasses pass {@code values()} explicitly: no {@code Class.getEnumConstants()} reflection, so the converter
 * needs no native-image metadata beyond the constructor Spring AOT registers for every JPA converter.
 *
 * @param <E> the enum type
 */
public abstract class KebabCaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

  private final Map<String, E> byId;

  protected KebabCaseEnumConverter(E[] values) {
    Map<String, E> map = new HashMap<>();
    for (E value : values) {
      map.put(idOf(value), value);
    }
    this.byId = Map.copyOf(map);
  }

  /** The stored id of {@code value}: lower case, underscores become hyphens. */
  public static String idOf(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  @Override
  public String convertToDatabaseColumn(E attribute) {
    return attribute == null ? null : idOf(attribute);
  }

  @Override
  public E convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    E value = byId.get(dbData);
    if (value == null) {
      // A value no enum constant matches means the file was written by a newer build or edited by hand.
      throw new IllegalArgumentException("Unknown stored value '" + dbData + "'");
    }
    return value;
  }
}
