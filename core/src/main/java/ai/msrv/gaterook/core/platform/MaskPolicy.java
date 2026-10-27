package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.connector.api.Env;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The Masking section of an environment's policy (kit Policies › Masking; report 12 §6): secrets are always masked,
 * whatever this says; on top of them personal data of the chosen kinds and the fields named here ("Always mask these
 * fields": {@code orders.customer_email}, {@code *.password}).
 *
 * @param entropy whether long random-looking strings are masked as unknown secrets (on by default; a dev connection
 *                full of base64 data may switch it off)
 * @param pii     kinds of personal data masked in every value, whatever its field name
 * @param fields  {@code table.column} (SQL), {@code collection.field} (MongoDB), {@code a.b.c} (JSON);
 *                {@code *} is any one name
 */
public record MaskPolicy(boolean entropy, Set<Pii> pii, List<String> fields) {

  /** Kinds of personal data (kit: email, phone, card, IBAN). */
  public enum Pii { EMAIL, PHONE, CARD, IBAN }

  /** Secrets only: what logs and every environment get at least. */
  public static final MaskPolicy SECRETS_ONLY = new MaskPolicy(true, Set.of(), List.of());

  public MaskPolicy {
    pii = pii.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(pii));
    fields = List.copyOf(fields);
  }

  /** Defaults by environment before any policy edit: personal data masked on prod only (report 12 §6). */
  public static MaskPolicy defaults(Env env) {
    return env == Env.PROD ? new MaskPolicy(true, EnumSet.allOf(Pii.class), List.of()) : SECRETS_ONLY;
  }

  /**
   * Reads {@code policy.sections.masking}: {@code {"entropy": true, "pii": ["email", "phone"], "fields": [...]}}.
   * Missing keys keep {@code base}'s values.
   */
  public static MaskPolicy fromSection(JsonNode section, MaskPolicy base) {
    if (section == null || !section.isObject()) {
      return base;
    }
    boolean entropy = section.has("entropy") ? section.get("entropy").asBoolean(base.entropy()) : base.entropy();
    Set<Pii> pii = base.pii();
    if (section.has("pii")) {
      EnumSet<Pii> kinds = EnumSet.noneOf(Pii.class);
      for (JsonNode kind : section.get("pii")) {
        try {
          kinds.add(Pii.valueOf(kind.asString().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
          // An unknown kind from a newer policy: masking more is the safe side, but we can't detect it.
        }
      }
      pii = kinds;
    }
    List<String> fields = base.fields();
    if (section.has("fields")) {
      fields = new ArrayList<>();
      for (JsonNode field : section.get("fields")) {
        fields.add(field.asString());
      }
    }
    return new MaskPolicy(entropy, pii, fields);
  }
}
