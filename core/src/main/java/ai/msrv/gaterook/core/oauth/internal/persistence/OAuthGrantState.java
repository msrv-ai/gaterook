package ai.msrv.gaterook.core.oauth.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/**
 * {@code oauth_grant.state}; {@code address-changed} when a quick tunnel came back with a new hostname, so the
 * grant's {@code resource} no longer matches (ARCHITECTURE.md §7.3).
 */
public enum OAuthGrantState {
  ACTIVE, ADDRESS_CHANGED, EXPIRED, REVOKED;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<OAuthGrantState> {
    public Converter() {
      super(values());
    }
  }
}
