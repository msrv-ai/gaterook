package ai.msrv.gaterook.core.admin.tokens;

import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.endpoints.TokenService;
import java.time.Instant;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint › Tokens (ARCHITECTURE.md §6 endpoint-detail, kit {@code token-issue}): named {@code gwt_} tokens for
 * clients that send one (MCP Inspector, Cursor, scripts) and the rows of access issued on the fly (grants of Claude
 * Desktop's bridge and Codex's helper). A token's value is in exactly one response, the one that issues it ("Shown
 * once"); afterwards only its prefix exists. Revoking takes effect on the next call and rejects what the token's
 * principal still waits for.
 */
@RestController
@RegisterReflectionForBinding({TokensController.TokenRow.class, TokensController.IssuedToken.class,
    TokensController.Revoked.class})
class TokensController {

  record Issue(String name, Integer ttlDays, String reason) {}

  record Reason(String reason) {}

  /**
   * One row of Tokens.
   *
   * @param kind   {@code named} or {@code grant} ("issued on the fly": no value exists to show)
   * @param prefix the first characters of a named token ({@code gwt_K9xP}); {@code null} for a grant
   * @param state  {@code active}, {@code expired} or {@code revoked}
   */
  record TokenRow(String id, String kind, String name, String prefix, String client, Instant createdAt,
      Instant expiresAt, Instant lastUsedAt, Instant revokedAt, String state) {}

  /** The one response that carries a token's value: the UI shows it once and forgets it. */
  record IssuedToken(String id, String name, String token, String prefix, Instant expiresAt) {

    @Override
    public String toString() {
      return "IssuedToken[id=" + id + ", name=" + name + ", prefix=" + prefix + "…]";
    }
  }

  /** {@code rejectedApprovals}: requests of this principal that waited and were rejected. */
  record Revoked(String id, int rejectedApprovals) {}

  private final TokenService tokens;

  TokensController(TokenService tokens) {
    this.tokens = tokens;
  }

  @GetMapping("/api/endpoints/{slug}/tokens")
  List<TokenRow> list(@PathVariable String slug) {
    return tokens.list(slug).stream().map(t -> new TokenRow(t.id(), t.kind(), t.name(), t.prefix(), t.client(),
        t.createdAt(), t.expiresAt(), t.lastUsedAt(), t.revokedAt(), t.state())).toList();
  }

  @PostMapping("/api/endpoints/{slug}/tokens")
  ResponseEntity<IssuedToken> issue(@PathVariable String slug, @RequestBody Issue request) {
    TokenService.Issued issued = tokens.issue(slug, request.name(), request.ttlDays(),
        Reasons.check(request.reason()));
    return ResponseEntity.status(HttpStatus.CREATED).body(new IssuedToken(issued.id(), issued.name(), issued.token(),
        issued.prefix(), issued.expiresAt()));
  }

  @DeleteMapping("/api/tokens/{id}")
  Revoked revoke(@PathVariable String id, @RequestBody(required = false) Reason request) {
    return new Revoked(id, tokens.revoke(id, request == null ? null : Reasons.check(request.reason())));
  }

  @DeleteMapping("/api/grants/{id}")
  Revoked revokeGrant(@PathVariable String id, @RequestBody(required = false) Reason request) {
    return new Revoked(id, tokens.revokeGrant(id, request == null ? null : Reasons.check(request.reason())));
  }
}
