package ai.msrv.gaterook.core.oauth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The browser pages of {@code /oauth/authorize} (report 02 §6.7): the waiting page with the code to compare, and the
 * error pages that never redirect (an invalid {@code client_id} or {@code redirect_uri} must not become an open
 * redirect). No external resource: the one style and the one script are inline and allowed by their SHA-256 in the
 * CSP {@code default-src 'none'; script-src 'sha256-…'; style-src 'sha256-…'; connect-src 'self'; frame-ancestors
 * 'none'; base-uri 'none'; form-action 'none'}. The page decides nothing: the person allows the sign-in in the app,
 * the page only polls {@code /oauth/authorize/status} with its HttpOnly cookie and follows the location it gets once.
 */
final class ConsentPages {

  static final String STYLE = """
      :root{color-scheme:light dark;--fg:#1d1d1f;--muted:#5f6368;--bg:#f6f7f9;--card:#fff;--line:#d9dce1;\
      --accent:#2f6fde;--warn:#9a5b00}
      @media (prefers-color-scheme:dark){:root{--fg:#ececf1;--muted:#a0a4ab;--bg:#16171a;--card:#202226;\
      --line:#33363c;--accent:#7aa7ff;--warn:#f0b35a}}
      *{box-sizing:border-box}body{margin:0;font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;\
      background:var(--bg);color:var(--fg);display:grid;place-items:center;min-height:100vh;padding:24px}
      main{background:var(--card);border:1px solid var(--line);border-radius:12px;max-width:440px;width:100%;\
      padding:28px}h1{font-size:18px;margin:0 0 12px}p{margin:8px 0;color:var(--muted)}
      .code{font:600 28px/1.2 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.12em;color:var(--fg);\
      margin:16px 0;text-align:center}dl{display:grid;grid-template-columns:auto 1fr;gap:4px 12px;margin:16px 0}
      dt{color:var(--muted)}dd{margin:0;word-break:break-all}.warn{color:var(--warn)}.status{font-weight:600;\
      color:var(--accent)}""";

  static final String SCRIPT = """
      (function(){var m=document.querySelector('main[data-rid]');if(!m){return;}var rid=m.getAttribute('data-rid');
      var s=document.getElementById('status');function poll(){fetch('/oauth/authorize/status?rid='+\
      encodeURIComponent(rid),{credentials:'same-origin',cache:'no-store'}).then(function(r){if(r.status!==200){
      s.textContent='This sign-in is no longer waiting. Start it again in your client.';return null;}return r.json();})
      .then(function(j){if(!j){return;}if(j.status==='done'&&j.location){s.textContent='Done. Returning to the app…';
      window.location.replace(j.location);return;}if(j.status==='pending'){setTimeout(poll,2000);}})
      .catch(function(){setTimeout(poll,4000);});}setTimeout(poll,1500);})();""";

  static final String CSP = "default-src 'none'; script-src '" + hash(SCRIPT) + "'; style-src '" + hash(STYLE)
      + "'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

  private ConsentPages() {}

  /** The page the browser waits on while the person decides in the app. */
  static String waiting(String rid, String code, String clientHost, String redirectHost, String endpoint,
      long secondsLeft) {
    return page("Confirm on your computer", "<main data-rid=\"" + esc(rid) + "\">"
        + "<h1>Confirm in MCP Gateway on your computer</h1>"
        + "<p>" + esc(clientHost) + " asks to sign in to endpoint <b>" + esc(endpoint) + "</b>. MCP Gateway shows "
        + "the same code. Allow it there only if you started this sign-in and the codes match.</p>"
        + "<div class=\"code\" aria-label=\"Code\">" + esc(code) + "</div>"
        + "<dl><dt>Client</dt><dd>" + esc(clientHost) + "</dd><dt>Returns to</dt><dd>" + esc(redirectHost)
        + "</dd><dt>Endpoint</dt><dd>" + esc(endpoint) + "</dd></dl>"
        + "<p class=\"status\" id=\"status\" role=\"status\">Waiting for you to allow it… (" + secondsLeft / 60
        + " min left)</p><p class=\"warn\">If you didn't start this, deny it in MCP Gateway.</p></main>", true);
  }

  /** A refusal shown in the browser, without a redirect. */
  static String error(String title, String message) {
    return page(title, "<main><h1>" + esc(title) + "</h1><p>" + esc(message) + "</p>"
        + "<p>Nothing was shared. You can close this tab.</p></main>", false);
  }

  private static String page(String title, String main, boolean script) {
    return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<meta name=\"referrer\" content=\"no-referrer\"><title>" + esc(title) + " · MCP Gateway</title>"
        + "<style>" + STYLE + "</style></head><body>" + main + (script ? "<script>" + SCRIPT + "</script>" : "")
        + "</body></html>";
  }

  /** Text for HTML: {@code & < > " '} escaped. */
  static String esc(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * A self-declared name made safe to show: control, format (bidi, zero-width) and private-use characters removed,
   * whitespace collapsed, at most {@code max} characters.
   */
  static String plain(String s, int max) {
    if (s == null) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    s.codePoints().forEach(cp -> {
      int type = Character.getType(cp);
      if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
          || type == Character.SURROGATE || type == Character.UNASSIGNED) {
        return;
      }
      out.appendCodePoint(Character.isWhitespace(cp) ? ' ' : cp);
    });
    String t = out.toString().replaceAll(" {2,}", " ").strip();
    return t.length() > max ? t.substring(0, max) : t;
  }

  private static String hash(String inline) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(inline.getBytes(StandardCharsets.UTF_8));
      return "sha256-" + Base64.getEncoder().encodeToString(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
