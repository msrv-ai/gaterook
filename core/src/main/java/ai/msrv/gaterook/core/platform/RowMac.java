package ai.msrv.gaterook.core.platform;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code mac} column of security-relevant rows (ARCHITECTURE.md §4): HMAC-SHA256 under the {@code row-mac/v1} key
 * of {@link HmacKeys} over the canonical JSON of the row's security fields, its table, id and version. A row edited in
 * {@code gateway.db} by anything but the core no longer verifies: its owner turns the object off and reports it as
 * "Changed outside GateRook" until the person reviews it (fail closed).
 *
 * <p>The owners sign after the flush that settles {@code version}; the {@code mac} fields are excluded from optimistic
 * locking, so writing the MAC doesn't bump the version it signs. A new master key (created, or replacing a lost one)
 * means a new {@code row-mac} key: nothing re-signs rows it can't vouch for (see {@link MasterKeyChanged}).
 */
public class RowMac {

  private final HmacKeys keys;
  private final JsonMapper json;

  public RowMac(HmacKeys keys, JsonMapper json) {
    this.keys = Objects.requireNonNull(keys, "keys");
    this.json = Objects.requireNonNull(json, "json");
  }

  /** A fresh object for the fields of one row. */
  public ObjectNode fields() {
    return JsonNodeFactory.instance.objectNode();
  }

  /**
   * A JSON column's value ({@code Map}, {@code List}, scalar or {@code null}) as a tree, the way it reads back from the
   * database: through its text, so numbers are typed as a reload types them.
   */
  public JsonNode tree(Object columnValue) {
    if (columnValue == null) {
      return JsonNodeFactory.instance.nullNode();
    }
    return json.readTree(json.writeValueAsString(columnValue));
  }

  /**
   * The MAC of a row.
   *
   * @throws IllegalStateException while the core is locked (no master key)
   */
  public byte[] sign(String table, String id, long version, JsonNode fields) {
    return keys.mac(HmacKeys.Purpose.ROW_MAC, data(table, id, version, fields));
  }

  /**
   * Whether {@code mac} is the MAC of the row. A missing MAC, a locked core and a MAC of other content are all
   * {@code false}: the caller fails closed.
   */
  public boolean verify(String table, String id, long version, JsonNode fields, byte[] mac) {
    if (mac == null) {
      return false;
    }
    try {
      return keys.verify(HmacKeys.Purpose.ROW_MAC, data(table, id, version, fields), mac);
    } catch (IllegalStateException locked) {
      return false;
    }
  }

  private static byte[] data(String table, String id, long version, JsonNode fields) {
    ObjectNode row = JsonNodeFactory.instance.objectNode();
    row.put("t", Objects.requireNonNull(table, "table"));
    row.put("id", Objects.requireNonNull(id, "id"));
    row.put("v", version);
    row.set("f", fields == null ? JsonNodeFactory.instance.nullNode() : fields);
    return CanonicalJson.bytes(row);
  }
}
