package ai.msrv.gaterook.core.endpoints;

import java.util.List;

/**
 * The tools agents call most on an endpoint, which the list of an endpoint that searches its tools shows
 * (ARCHITECTURE.md §5.7, {@code settings.toolList.mostUsed}). The insights module counts them from the audit, which
 * this module may not read.
 */
public interface ToolPopularity {

  /** What an endpoint gets without one: no tool is called more than another. */
  ToolPopularity NONE = new ToolPopularity() {
    @Override
    public List<String> mostCalled(EndpointSnapshot endpoint) {
      return List.of();
    }

    @Override
    public long version() {
      return 0;
    }
  };

  /** Tool names of the endpoint, most called first; empty when none was called. */
  List<String> mostCalled(EndpointSnapshot endpoint);

  /** Changes whenever an answer of {@link #mostCalled} changes, so a catalog built before is built again. */
  long version();
}
