package ai.msrv.gaterook.core.insights;

import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.endpoints.EndpointCatalogInvalidated;
import ai.msrv.gaterook.core.endpoints.EndpointRemoved;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.ToolPopularity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The tools agents call most on each endpoint (ARCHITECTURE.md §5.7): the calls each tool received over the last
 * {@value #WINDOW_DAYS} days, counted from the audit when an endpoint that searches its tools first lists them, then
 * again once a day. So the list AI apps get stays the same for a day, and their prompt caches with it. A count that
 * changes an endpoint's answer invalidates its catalog, so clients that listen hear
 * {@code notifications/tools/list_changed}. A call run through {@code call_tool} counts for the tool it ran. When the
 * audit can't be read, no tool counts as called: the endpoint searches them all.
 */
@Component
public class PopularTools implements ToolPopularity {

  private static final Logger log = LoggerFactory.getLogger(PopularTools.class);

  static final int WINDOW_DAYS = 30;
  static final Duration KEEP = Duration.ofDays(1);
  /** More than any list shows: the catalog skips the gateway's own tools and those a pin already lists. */
  static final int KEPT = 60;

  private record Counted(String endpointId, List<String> tools, Instant at) {}

  private final AuditQueries audit;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final Map<String, Counted> bySlug = new ConcurrentHashMap<>();
  private final AtomicLong version = new AtomicLong();

  public PopularTools(AuditQueries audit, ApplicationEventPublisher publisher, Clock clock) {
    this.audit = audit;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Override
  public List<String> mostCalled(EndpointSnapshot endpoint) {
    Counted counted = bySlug.get(endpoint.slug());
    if (counted == null) {
      counted = count(endpoint.id(), endpoint.slug());
      Counted first = bySlug.putIfAbsent(endpoint.slug(), counted);
      if (first != null) {
        counted = first;
      }
    }
    return counted.tools();
  }

  @Override
  public long version() {
    return version.get();
  }

  /** Every hour: the endpoints counted a day ago or earlier are counted again. */
  @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
  public void recountStale() {
    recount(false);
  }

  /** Counts every endpoint counted before again, now. */
  public void recountNow() {
    recount(true);
  }

  @EventListener
  public void onEndpointRemoved(EndpointRemoved e) {
    bySlug.remove(e.slug());
  }

  private void recount(boolean all) {
    Instant now = clock.instant();
    for (Map.Entry<String, Counted> e : List.copyOf(bySlug.entrySet())) {
      Counted before = e.getValue();
      if (!all && before.at().plus(KEEP).isAfter(now)) {
        continue;
      }
      Counted after = count(before.endpointId(), e.getKey());
      bySlug.put(e.getKey(), after);
      if (!after.tools().equals(before.tools())) {
        version.incrementAndGet();
        publisher.publishEvent(new EndpointCatalogInvalidated(before.endpointId()));
      }
    }
  }

  private Counted count(String endpointId, String slug) {
    Instant now = clock.instant();
    try {
      List<String> tools = audit.mostCalled(slug, now.minus(Duration.ofDays(WINDOW_DAYS)), KEPT).stream()
          .map(AuditQueries.ToolCalls::tool).toList();
      return new Counted(endpointId, tools, now);
    } catch (RuntimeException e) {
      log.warn("The most called tools of {} weren't counted: {}", slug, e.toString());
      return new Counted(endpointId, List.of(), now);
    }
  }
}
