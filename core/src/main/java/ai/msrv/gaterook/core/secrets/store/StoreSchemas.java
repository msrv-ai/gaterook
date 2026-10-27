package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import ai.msrv.gaterook.core.secrets.store.aws.AwsSmStore;
import ai.msrv.gaterook.core.secrets.store.gcp.GcpSmStore;
import ai.msrv.gaterook.core.secrets.store.vault.VaultSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The forms of Secrets › External stores (report 12 §4.3): one JSON Schema per kind, a resource of this package
 * ({@code ai/msrv/gaterook/core/secrets/store/<kind>.schema.json}, registered for the native image by
 * {@code StoreConfiguration}), and which of its fields are the store's own secrets.
 */
public final class StoreSchemas {

  private static final Map<StoreKind, String> RESOURCES = Map.of(StoreKind.VAULT, "vault", StoreKind.GCP_SM,
      "gcp-sm", StoreKind.AWS_SM, "aws-sm");

  private final Map<StoreKind, JsonNode> schemas = new EnumMap<>(StoreKind.class);
  private final JsonMapper json;

  public StoreSchemas(JsonMapper json) {
    this.json = json;
  }

  /** Read when a form or a check first needs it: nothing on the start's path to the ready line. */
  public synchronized JsonNode schema(StoreKind kind) {
    return schemas.computeIfAbsent(kind, k -> {
      String path = "ai/msrv/gaterook/core/secrets/store/" + RESOURCES.get(k) + ".schema.json";
      try (InputStream in = StoreSchemas.class.getClassLoader().getResourceAsStream(path)) {
        if (in == null) {
          throw new IllegalStateException(path + " is missing from the classpath");
        }
        return json.readTree(in);
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    });
  }

  /** Every secret field a store of {@code kind} may have, whatever its sign-in. */
  public static List<String> secretFields(StoreKind kind) {
    return switch (kind) {
      case VAULT -> List.of(VaultSettings.SECRET_ID, VaultSettings.TOKEN);
      case GCP_SM -> List.of(GcpSmStore.KEY);
      case AWS_SM -> List.of(AwsSmStore.SECRET_KEY);
    };
  }

  /** {@code vault}, {@code gcp-sm}, {@code aws-sm}. */
  public static String id(StoreKind kind) {
    return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** The kind of {@code id}, or {@code null}. */
  public static StoreKind kind(String id) {
    for (StoreKind k : StoreKind.values()) {
      if (id(k).equals(id)) {
        return k;
      }
    }
    return null;
  }
}
