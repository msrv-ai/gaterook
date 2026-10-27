package ai.msrv.gaterook.core.connections;

import java.util.Arrays;

/**
 * The value of one secret field as a form sends it ({@code secrets: {"/credentials/password": {source, value|ref}}}):
 * a new value to encrypt here, a reference to an external store, or Vault dynamic credentials. The value lives only
 * in the request: it is zeroed once stored or tested, and never printed.
 *
 * @param value     a new value ({@code source: local}), or {@code null}
 * @param reference {@code vault://…}, {@code gcp-sm://…}, {@code aws-sm://…} ({@code source: reference} or
 *                  {@code vault-dynamic}), or {@code null}
 * @param dynamic   {@code source: vault-dynamic}: short-lived credentials from {@code vault://<mount>/creds/<role>}
 * @param store     the external store the person chose for the reference, or {@code null} for the one of its kind
 */
public record SecretInput(char[] value, String reference, boolean dynamic, String store) {

  public static SecretInput local(char[] value) {
    return new SecretInput(value, null, false, null);
  }

  public static SecretInput reference(String uri) {
    return new SecretInput(null, uri, false, null);
  }

  public static SecretInput reference(String uri, String store) {
    return new SecretInput(null, uri, false, store);
  }

  /** Vault dynamic credentials ({@code vault://database/creds/<role>}). */
  public static SecretInput dynamic(String uri, String store) {
    return new SecretInput(null, uri, true, store);
  }

  public boolean isLocal() {
    return value != null;
  }

  /** Zeroes the value. */
  public void clear() {
    if (value != null) {
      Arrays.fill(value, '\0');
    }
  }

  @Override
  public String toString() {
    return value != null ? "SecretInput[value=‹redacted›]"
        : "SecretInput[" + (dynamic ? "dynamic" : "reference") + "=" + reference + "]";
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
