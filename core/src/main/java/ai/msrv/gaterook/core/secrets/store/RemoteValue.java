package ai.msrv.gaterook.core.secrets.store;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * A value read from a store for one use: the bytes of the key asked for (or of the whole secret), and the facts Test
 * reference shows — never with the value's length, hash or prefix. The owner zeroes it with {@link #close()}.
 *
 * @param version the store's version: Vault {@code 7}, GCP {@code 4}, AWS the version id; {@code null} when unknown
 *                (KV v1)
 * @param type    {@code string}, {@code binary} or {@code json} (an object whose keys are {@link #keys})
 * @param keys    names of the keys of a JSON secret, for "Keys: username, password"
 */
public record RemoteValue(byte[] value, String version, Instant created, String type, List<String> keys)
    implements AutoCloseable {

  public RemoteValue {
    keys = keys == null ? List.of() : List.copyOf(keys);
  }

  /** A copy whose bytes the receiver owns. */
  public RemoteValue copy() {
    return new RemoteValue(value.clone(), version, created, type, keys);
  }

  @Override
  public void close() {
    Arrays.fill(value, (byte) 0);
  }

  @Override
  public String toString() {
    return "RemoteValue[version=" + version + ", type=" + type + ", value=‹secret›]";
  }

  @Override
  public boolean equals(Object o) {
    return this == o;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }
}
