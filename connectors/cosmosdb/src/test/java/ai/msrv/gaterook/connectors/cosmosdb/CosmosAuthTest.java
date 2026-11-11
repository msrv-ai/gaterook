package ai.msrv.gaterook.connectors.cosmosdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The master-key token as Cosmos DB documents it, and the date it is computed over. */
class CosmosAuthTest {

  /**
   * The example of the REST API reference ("Access control on Cosmos DB resources"): GET of the database ToDoList at
   * {@code Thu, 27 Apr 2017 00:51:12 GMT} with its example key gives
   * {@code type%3dmaster%26ver%3d1.0%26sig%3dc09PEVJrgp2uQRkr934kFbTqhByc7TVr3OHyqlu%2bc%2bc%3d}. The key is Microsoft's
   * published example, not a key of any account.
   */
  @Test
  void theSignatureIsTheOneOfMicrosoftsExample() throws Exception {
    String key = "dsZQi3KtZmCv1ljt3VNWNm7sQUF1y5rJfC6kv5JiwvW0EndXdDku/dkKBp8/ufDToSxLzR4y+O/0H/t4bQtVNw==";
    String signature = CosmosAuth.signature(key.toCharArray(), "GET", "dbs", "dbs/ToDoList",
        "Thu, 27 Apr 2017 00:51:12 GMT");
    assertThat(signature).isEqualTo("c09PEVJrgp2uQRkr934kFbTqhByc7TVr3OHyqlu+c+c=");
    String header = CosmosAuth.header(CosmosAuth.MASTER, signature);
    // Percent-encoding is case-insensitive: Microsoft's example writes %3d, Java %3D.
    assertThat(header).isEqualToIgnoringCase("type%3dmaster%26ver%3d1.0%26sig%3dc09PEVJrgp2uQRkr934kFbTqhByc7TVr3OHyqlu"
        + "%2bc%2bc%3d");
    assertThat(URLDecoder.decode(header, StandardCharsets.UTF_8)).isEqualTo("type=master&ver=1.0&sig=" + signature);
    // White space a paste brings doesn't change the key.
    assertThat(CosmosAuth.signature((" " + key.substring(0, 40) + "\n" + key.substring(40) + " ").toCharArray(),
        "GET", "dbs", "dbs/ToDoList", "Thu, 27 Apr 2017 00:51:12 GMT")).isEqualTo(signature);
  }

  @Test
  void theDateIsRfc7231sFixedDateWithTwoDigitDays() {
    assertThat(CosmosAuth.HTTP_DATE.format(Instant.parse("2026-11-01T08:12:31Z")))
        .isEqualTo("Sun, 01 Nov 2026 08:12:31 GMT");
  }

  @Test
  void aKeyThatIsNoKeyIsRefusedWithoutQuotingIt() {
    for (String bad : new String[] {"not a key!", "c2hvcnQ=", "Zürich"}) {
      assertThatThrownBy(() -> CosmosAuth.signature(bad.toCharArray(), "GET", "", "", "x"))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
            assertThat(e.getMessage()).doesNotContain(bad);
          });
    }
  }

  @Test
  void anEntraIdRefusalNamesTheFixAndNeverTheSecret() {
    var cfg = CosmosDbConnectorTest.config(ai.msrv.gaterook.connector.api.Env.DEV, CosmosDbConnectorTest.ENTRA, null);
    CosmosSettings s;
    try {
      s = CosmosSettings.of(cfg, false);
    } catch (ConnectorException e) {
      throw new AssertionError(e);
    }
    assertThat(CosmosAuth.signInRefused(cfg, s, "invalid_client", "7000222", "AADSTS7000222: The provided client "
        + "secret keys are expired.")).contains("has expired");
    assertThat(CosmosAuth.signInRefused(cfg, s, "unauthorized_client", "700016", "AADSTS700016: Application not "
        + "found.")).contains("client ID");
    assertThat(CosmosAuth.signInRefused(cfg, s, "invalid_request", "", "")).contains("invalid_request");
  }
}
