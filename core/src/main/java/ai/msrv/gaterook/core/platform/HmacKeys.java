package ai.msrv.gaterook.core.platform;

import java.security.MessageDigest;

/**
 * Port: HMAC-SHA256 with keys derived from the master keyset, one key per purpose (HKDF {@code info} =
 * {@link Purpose#info()}). Implemented by the {@code secrets} module, which owns the master key; declared here so that
 * {@code platform.RowMac}, {@code mcp.RequestStateCodec} and the handle codec don't depend on {@code secrets}.
 *
 * <p>Key material never leaves the implementation: callers get MACs, not keys. While the master key is missing
 * (locked mode, ARCHITECTURE.md §7.1) every method throws {@link IllegalStateException}.
 */
public interface HmacKeys {

  /** HMAC-SHA256 of {@code data} under the 32-byte key of {@code purpose}. */
  byte[] mac(Purpose purpose, byte[] data);

  /** Constant-time check of a MAC made by {@link #mac}. */
  default boolean verify(Purpose purpose, byte[] data, byte[] mac) {
    return mac != null && MessageDigest.isEqual(mac(purpose, data), mac);
  }

  /** What a key is for; a key of one purpose never verifies a MAC of another. */
  enum Purpose {
    /** {@code mac} column of the security-relevant rows (ARCHITECTURE.md §4). */
    ROW_MAC("row-mac/v1"),
    /** {@code requestState} of MRTR approval rounds (§5 step 11). */
    REQUEST_STATE("request-state/v1"),
    /** Opaque cursors handed to agents ({@code ExecContext.handles()}). */
    HANDLES("handles/v1"),
    /** MAC of the audit hash-chain head stored in {@code setting} and in every export. */
    AUDIT_HEAD("audit-head/v1");

    private final String info;

    Purpose(String info) {
      this.info = info;
    }

    /** HKDF {@code info} string; the version suffix lets a purpose move to a new key. */
    public String info() {
      return info;
    }
  }
}
