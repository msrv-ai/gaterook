package com.gaterook.core.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gaterook.connector.api.Env;
import com.gaterook.core.audit.AuditQueries;
import com.gaterook.core.endpoints.EndpointCatalogInvalidated;
import com.gaterook.core.endpoints.EndpointRemoved;
import com.gaterook.core.endpoints.EndpointSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** The tools agents call most on an endpoint: counted once, kept a day, counted again (ARCHITECTURE.md §5.7). */
class PopularToolsTest {

  private final AuditQueries audit = mock(AuditQueries.class);
  private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
  private final MovingClock clock = new MovingClock(Instant.parse("2026-11-11T09:00:00Z"));
  private final PopularTools popular = new PopularTools(audit, publisher, clock);
  private final EndpointSnapshot dev = new EndpointSnapshot("ep_dev", "dev", "", Env.DEV, false, true, null, null,
      false, false, null, 1, List.of(), List.of(), List.of(), null);

  @Test
  void countsOnceAndKeepsTheAnswerForADay() {
    calls("sql_query", "sentry_search_issues");

    assertThat(popular.mostCalled(dev)).containsExactly("sql_query", "sentry_search_issues");
    calls("sentry_search_issues", "sql_query");
    assertThat(popular.mostCalled(dev)).as("kept").containsExactly("sql_query", "sentry_search_issues");
    verify(audit, times(1)).mostCalled(eq("dev"), eq(Instant.parse("2026-10-12T09:00:00Z")), anyInt());

    clock.advance(Duration.ofHours(23));
    popular.recountStale();
    assertThat(popular.mostCalled(dev)).containsExactly("sql_query", "sentry_search_issues");
    verify(publisher, never()).publishEvent(any());
    long version = popular.version();

    clock.advance(Duration.ofHours(1));
    popular.recountStale();
    assertThat(popular.mostCalled(dev)).containsExactly("sentry_search_issues", "sql_query");
    assertThat(popular.version()).isGreaterThan(version);
    verify(publisher).publishEvent(new EndpointCatalogInvalidated("ep_dev"));
  }

  @Test
  void anUnchangedCountNotifiesNobody() {
    calls("sql_query");
    popular.mostCalled(dev);
    long version = popular.version();

    popular.recountNow();

    assertThat(popular.version()).isEqualTo(version);
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void anAuditItCantReadCountsNoToolAndARemovedEndpointIsForgotten() {
    when(audit.mostCalled(any(), any(), anyInt())).thenThrow(new IllegalStateException("database is locked"));
    assertThat(popular.mostCalled(dev)).isEmpty();

    calls("sql_query");
    popular.onEndpointRemoved(new EndpointRemoved("ep_dev", "dev"));
    assertThat(popular.mostCalled(dev)).as("counted again").containsExactly("sql_query");
  }

  private void calls(String... tools) {
    org.mockito.Mockito.doReturn(java.util.Arrays.stream(tools).map(t -> new AuditQueries.ToolCalls(t, 1)).toList())
        .when(audit).mostCalled(any(), any(), anyInt());
  }

  private static final class MovingClock extends Clock {
    private Instant now;

    MovingClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
