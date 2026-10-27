package ai.msrv.gaterook.connector.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectorException.Kind;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ConnectorExceptionTest {

  private final IOException cause = new IOException("reset");

  @Test
  void factoriesSetKindMessageAndCause() {
    assertThat(ConnectorException.unavailable("Can't reach", cause))
        .satisfies(e -> assertThat(e.kind()).isEqualTo(Kind.RESOURCE_UNAVAILABLE))
        .hasMessage("Can't reach").hasCause(cause);
    assertThat(ConnectorException.timeout("Timed out after 5 s"))
        .satisfies(e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT)).hasNoCause();
    assertThat(ConnectorException.badInput("lines must be 1..500"))
        .satisfies(e -> assertThat(e.kind()).isEqualTo(Kind.BAD_INPUT));
    assertThat(ConnectorException.resourceError("syntax error at line 1", cause))
        .satisfies(e -> assertThat(e.kind()).isEqualTo(Kind.RESOURCE_ERROR)).hasCause(cause);
    assertThat(ConnectorException.denied("not approved"))
        .satisfies(e -> assertThat(e.kind()).isEqualTo(Kind.DENIED));
    assertThat(ConnectorException.outcomeUnknown("dropped during COMMIT", cause))
        .satisfies(e -> assertThat(e.kind()).isEqualTo(Kind.OUTCOME_UNKNOWN)).hasCause(cause);
  }

  @Test
  void onlyUnavailableAndTimeoutTripTheCircuitBreaker() {
    for (Kind kind : Kind.values()) {
      boolean counts = new ConnectorException(kind, "m", null).countsForCircuitBreaker();
      assertThat(counts).as(kind.name()).isEqualTo(kind == Kind.RESOURCE_UNAVAILABLE || kind == Kind.TIMEOUT);
    }
  }

  @Test
  void unavailableSecretIsAnUnavailableResource() {
    SecretUnavailableException e = new SecretUnavailableException("AWS Secrets Manager isn't connected.", null);

    assertThat(e).isInstanceOf(ConnectorException.class);
    assertThat(e.kind()).isEqualTo(Kind.RESOURCE_UNAVAILABLE);
    assertThat(e.countsForCircuitBreaker()).isTrue();
  }
}
