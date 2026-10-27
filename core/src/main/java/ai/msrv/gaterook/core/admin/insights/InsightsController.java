package ai.msrv.gaterook.core.admin.insights;

import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.insights.Insights;
import ai.msrv.gaterook.core.insights.InsightsService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Insights (ARCHITECTURE.md §6 insights, kit {@code insights.html}): {@code GET /api/insights?q&from&to}, the numbers
 * and names the page turns into tiles, charts, sentences and links to Activity. The UI sends a range that ends "now"
 * by the core's clock; without one, the last 24 hours.
 */
@RestController
@RegisterReflectionForBinding({Insights.class, Insights.Kpis.class, Insights.Totals.class, Insights.ToolCount.class,
    Insights.ClientCalls.class, Insights.Approvals.class, Insights.WaitBucket.class, Insights.SlowDecision.class,
    Insights.DeniedRule.class, Insights.Example.class, Insights.Latency.class, Insights.ConnectionLatency.class,
    Insights.ConnectionErrors.class, Insights.Unusual.class, AuditQueries.Histogram.class,
    AuditQueries.Bucket.class})
class InsightsController {

  private final InsightsService insights;

  InsightsController(InsightsService insights) {
    this.insights = insights;
  }

  @GetMapping("/api/insights")
  Insights get(@RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    return insights.compute(q, instant(from, "/from"), instant(to, "/to"));
  }

  private static Instant instant(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      try {
        return OffsetDateTime.parse(value).toInstant();
      } catch (DateTimeParseException e2) {
        throw ApiRequestException.unprocessable("Invalid time", "Give the time as ISO 8601, like "
            + "2026-10-22T14:30:00+02:00.", field);
      }
    }
  }
}
