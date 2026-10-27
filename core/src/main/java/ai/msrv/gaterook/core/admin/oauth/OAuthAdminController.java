package ai.msrv.gaterook.core.admin.oauth;

import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.oauth.OAuthService;
import java.time.Instant;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The person's side of OAuth sign-ins (ARCHITECTURE.md §6 tunnel / OAuth, report 02 §5): the sign-ins waiting here
 * with the code to compare against the browser's, Allow / Deny, and "Signed in" with Revoke. This is the only place a
 * sign-in is decided: the admin API on the local port, behind the launch secret — never the browser that asked, never
 * a message in Slack or Telegram.
 */
@RestController
@RegisterReflectionForBinding({OAuthAdminController.Consent.class, OAuthAdminController.Tools.class,
    OAuthAdminController.Grant.class})
class OAuthAdminController {

  record Consent(String id, String code, String clientHost, String clientName, String clientKind,
      String redirectHost, String endpoint, String env, Tools tools, String ip, String ingress, Instant expiresAt) {}

  record Tools(int read, int approval, int off, int bypass) {}

  record Grant(String id, String client, String clientName, String clientKind, String endpoint, String ingress,
      String resource, String scope, Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt,
      String revokeReason, String state) {}

  record Decision(String decision, String reason) {}

  record Reason(String reason) {}

  private final OAuthService oauth;

  OAuthAdminController(OAuthService oauth) {
    this.oauth = oauth;
  }

  @GetMapping("/api/oauth/consents")
  List<Consent> consents() {
    return oauth.consents().stream().map(c -> new Consent(c.id(), c.code(), c.clientHost(), c.clientName(),
        c.clientKind(), c.redirectHost(), c.endpoint(), c.env(), new Tools(c.tools().read(), c.tools().approval(),
            c.tools().off(), c.tools().bypass()), c.ip(), c.ingress().name(), c.expiresAt())).toList();
  }

  /** 204, or 409 when the sign-in was answered or expired meanwhile (404 for one that never existed). */
  @PostMapping("/api/oauth/consents/{id}")
  ResponseEntity<Void> decide(@PathVariable String id, @RequestBody Decision request) {
    String decision = request == null ? null : request.decision();
    if (!"allow".equals(decision) && !"deny".equals(decision)) {
      throw ApiRequestException.unprocessable("Invalid decision", "Send allow or deny.", "/decision");
    }
    oauth.decide(id, "allow".equals(decision), Reasons.check(request.reason()));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/oauth/grants")
  List<Grant> grants() {
    return oauth.grants().stream().map(g -> new Grant(g.id(), g.client(), g.clientName(), g.clientKind(),
        g.endpoint(), g.ingress().name(), g.resource(), g.scope(), g.createdAt(), g.expiresAt(), g.lastUsedAt(),
        g.revokedAt(), g.revokeReason(), g.state())).toList();
  }

  /** Revoke: the client's access and refresh tokens stop on its next call; 204. */
  @DeleteMapping("/api/oauth/grants/{id}")
  ResponseEntity<Void> revoke(@PathVariable String id, @RequestBody(required = false) Reason request) {
    oauth.revoke(id, request == null ? null : Reasons.check(request.reason()));
    return ResponseEntity.noContent().build();
  }
}
