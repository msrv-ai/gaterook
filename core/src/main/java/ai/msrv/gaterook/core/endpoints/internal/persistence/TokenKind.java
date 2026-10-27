package ai.msrv.gaterook.core.endpoints.internal.persistence;

/**
 * {@code api_token.kind}: a {@code NAMED} {@code gwt_} token shown once to a person, or a 15-minute {@code BRIDGE}
 * {@code gwb_} token issued on the fly for a grant (ARCHITECTURE.md A4, A7).
 */
public enum TokenKind {
  NAMED, BRIDGE
}
