package ai.msrv.gaterook.core.oauth.internal.persistence;

/** {@code oauth_token.kind}: {@code gwo_} access (1 h) or {@code gwr_} refresh (rotated on use). */
public enum OAuthTokenKind {
  ACCESS, REFRESH
}
