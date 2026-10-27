package ai.msrv.gaterook.core.mcp.internal.web;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /approvals/{id}} on the local listener only (ARCHITECTURE.md §6 approvals, §9 "Approval cannot be
 * forged through the protocol"): the page an MRTR URL elicitation opens. It only hands over to the app
 * ({@code gaterook://approvals/<id>}); it shows no action, takes no decision and doesn't even say whether the request
 * exists — the same bytes for every well-formed id. {@code default-src 'none'}: no script, no style, no frame.
 */
@RestController
class ApprovalPageController {

  private static final Pattern ID = Pattern.compile("ap_[a-z0-9]{12}");

  @GetMapping("/approvals/{id}")
  ResponseEntity<byte[]> page(@PathVariable String id) {
    if (!ID.matcher(id).matches()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    String link = "gaterook://approvals/" + id;
    String html = """
        <!doctype html>
        <html lang="en">
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="no-referrer"><title>Approval request · GateRook</title></head>
        <body>
        <main>
        <h1>Decide in GateRook</h1>
        <p>An agent asked for an action that needs your approval. The exact action and the decision are in the MCP \
        Gateway window, never on this page.</p>
        <p><a href="%s">Open request %s in GateRook</a></p>
        <p>If nothing opens, switch to the GateRook window: the request is under Approvals.</p>
        </main>
        </body>
        </html>
        """.formatted(link, id);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
        .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; "
            + "form-action 'none'")
        .header("X-Content-Type-Options", "nosniff")
        .header("Referrer-Policy", "no-referrer")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(html.getBytes(StandardCharsets.UTF_8));
  }
}
