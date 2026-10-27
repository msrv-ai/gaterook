package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/**
 * What the approver reads (report 10 §3.5, kit approval-request "Exact action · HTTP"): the request line, the
 * headers in sending order, the credentials the gateway adds as {@code ‹masked›}, a blank line and the body as the
 * exact bytes that go on the wire. Never a paraphrase; the headers OkHttp adds itself are named in a field.
 */
final class ActionText {

  /** The gateway's placeholder for a value the approver doesn't see (the same the masker writes). */
  static final String MASKED = "‹masked›";

  /** Slack's limit for the text of a section: a longer action can't be approved from a channel (report 13 §6.2). */
  static final int CHANNEL_LIMIT = 3_000;

  private static final Pattern ESCAPED = Pattern.compile("%[0-9A-Fa-f]{2}");

  private ActionText() {}

  static ActionPreview of(PlannedRequest r, HttpSettings s, ConnectionConfig cfg, Duration timeout) {
    StringBuilder text = new StringBuilder();
    text.append(r.method()).append(' ').append(shownUrl(r, s)).append('\n');
    if (r.body() != null && r.contentType() != null) {
      text.append("Content-Type: ").append(r.contentType()).append('\n');
    }
    boolean idempotencyKey = false;
    for (PlannedRequest.Header h : r.headers()) {
      text.append(h.name()).append(": ").append(h.value()).append('\n');
      idempotencyKey |= h.name().equalsIgnoreCase("Idempotency-Key");
    }
    if (s.auth().header() != null) {
      text.append(s.auth().header()).append(": ").append(MASKED).append('\n');
    }
    for (HttpSettings.SecretHeader h : s.secretHeaders()) {
      if (h.appliesTo(r.method())) {
        text.append(h.name()).append(": ").append(MASKED).append('\n');
      }
    }
    if (r.body() != null) {
      text.append('\n').append(r.bodyText());
    } else {
      text.setLength(text.length() - 1);
    }

    String host = r.url().host() + (r.url().port() == HttpUrl.defaultPort(r.url().scheme()) ? ""
        : ":" + r.url().port());
    String summary = r.line() + " · " + cfg.name() + " (" + cfg.env().id() + ")";
    if (summary.length() > 120) {
      summary = summary.substring(0, 119) + "…";
    }

    List<ActionPreview.Field> fields = new ArrayList<>();
    if (r.operation() != null) {
      fields.add(new ActionPreview.Field("Operation", r.operation().label()));
    }
    StringBuilder also = new StringBuilder("Host");
    if (r.body() != null) {
      also.append(", Content-Length: ").append(r.body().length);
    } else if (!r.readMethod() && !r.method().equals("DELETE")) {
      also.append(", Content-Length: 0");
    }
    also.append(", Connection: Keep-Alive, User-Agent: GateRook/").append(BuiltIn.VERSION)
        .append(", Accept-Encoding: gzip");
    fields.add(new ActionPreview.Field("Also sent by the gateway", also.toString()));

    List<ActionPreview.Field> impact = new ArrayList<>();
    impact.add(new ActionPreview.Field("Host", host + ", in the " + cfg.env().id() + " allowlist"));
    impact.add(new ActionPreview.Field("Retries", idempotencyKey ? "Safe: idempotency key set"
        : r.readMethod() || r.method().equals("PUT") || r.method().equals("DELETE") ? "Idempotent method"
        : "Not safe to retry: the gateway sends it once"));
    if (timeout != null) {
      impact.add(new ActionPreview.Field("Timeout", Sizes.seconds(timeout)));
    }

    List<String> warnings = new ArrayList<>();
    if (r.body() != null && r.bodyText().length() > CHANNEL_LIMIT) {
      warnings.add("The body is longer than 3,000 characters: Slack and Telegram show it cut and don't offer "
          + "Approve. Read it here.");
    }
    if (ESCAPED.matcher(r.url().encodedPath()).find()) {
      warnings.add("The path has percent-encoded characters: the server decodes " + r.url().encodedPath() + ".");
    }
    if (s.auth().type() == HttpSettings.AuthType.API_KEY_QUERY) {
      warnings.add("The key goes in the URL (?" + s.auth().name() + "=…): servers and proxies may log it.");
    }

    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("http.method", r.method());
    facets.put("http.host", host);
    if (r.operation() != null) {
      facets.put("template", r.operation().facet());
      facets.put("http.operation", r.operation().opKey());
    }
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text.toString(), cfg.name() + " · " + host, summary)
        .withFields(fields).withImpact(impact).withWarnings(warnings).withFacets(facets).withPayload(r.payload(s));
  }

  /** The URL as the approver reads it: with the key of {@code apiKeyQuery} as {@code ‹masked›}. */
  static String shownUrl(PlannedRequest r, HttpSettings s) {
    String url = r.url().toString();
    if (s.auth().type() != HttpSettings.AuthType.API_KEY_QUERY) {
      return url;
    }
    return url + (r.url().encodedQuery() == null ? "?" : "&") + s.auth().name() + "=" + MASKED;
  }

}
