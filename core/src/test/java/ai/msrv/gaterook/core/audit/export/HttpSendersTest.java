package ai.msrv.gaterook.core.audit.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.utils.http.SdkHttpUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The S3 and BigQuery senders on their own, against the fakes: an object key goes out encoded the way S3 signs it,
 * {@code insertAll} requests stay under BigQuery's 10 MB, a refused request names the row at fault, and an answer
 * that stalls after its headers ends at the sender's deadline instead of holding the run forever.
 */
class HttpSendersTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
      .proxy(HttpClient.Builder.NO_PROXY).build();

  @Test
  void anObjectKeyGoesOutEncodedTheWayS3SignsIt() throws Exception {
    try (FakeCloud cloud = new FakeCloud()) {
      S3Sender sender = s3(URI.create(cloud.url()), "audit(prod)!*'/", Duration.ofSeconds(10));
      Batch batch = SiemSenderTest.batch();
      String key = sender.objectKey(batch);
      assertThat(key).startsWith("audit(prod)!*'/2026/10/22/tool-calls-");

      // S3 signs the canonical URI of the key with everything but A-Z a-z 0-9 - _ . ~ and / percent-encoded; the
      // request's path is signed as it goes, so it must already be that form.
      String encoded = SdkHttpUtils.urlEncodeIgnoreSlashes(key);
      assertThat(sender.objectUri(key).getRawPath()).isEqualTo("/acme-audit/" + encoded);
      assertThat(encoded).contains("audit%28prod%29%21%2A%27/");
      S3Sender aws = s3(null, "audit(prod)!*'/", Duration.ofSeconds(10));
      assertThat(aws.objectUri(key).toString()).isEqualTo("https://acme-audit.s3.eu-central-1.amazonaws.com/"
          + encoded);

      sender.send(batch);
      assertThat(cloud.requests("PUT", "/acme-audit/").getFirst().path()).isEqualTo("/acme-audit/" + encoded);
    }
  }

  @Test
  void insertAllRequestsStayUnderBigQuerysTenMegabytes() throws Exception {
    try (FakeCloud cloud = new FakeCloud()) {
      // Twelve calls of 1.5 MB arguments each (an agent may send up to 1 MiB, JSON escaping adds to it): 18 MB.
      List<ObjectNode> rows = new ArrayList<>();
      for (int i = 1; i <= 12; i++) {
        ObjectNode row = JsonNodeFactory.instance.objectNode().put("type", "tool-call").put("id", i);
        row.putObject("args").put("body", "x".repeat(1_500_000));
        rows.add(row);
      }
      Batch batch = new Batch("000000000001-000000000012", rows, SiemSenderTest.batch().head(), Instant.now());

      bigQuery(URI.create(cloud.url()), Duration.ofSeconds(30)).send(batch);

      List<FakeCloud.Request> inserts = cloud.requests("POST", "/bigquery/v2/");
      assertThat(inserts).hasSizeGreaterThan(1).allSatisfy(r -> assertThat(r.body().length)
          .isLessThanOrEqualTo(10 * 1024 * 1024));
      List<String> ids = new ArrayList<>();
      for (FakeCloud.Request r : inserts) {
        JSON.readTree(r.text()).path("rows").forEach(row -> ids.add(row.path("insertId").asString()));
      }
      assertThat(ids).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
          "head-000000000001-000000000012");
    }
  }

  @Test
  void aRefusedRequestNamesTheRowAtFaultRatherThanOneStoppedWithIt() throws Exception {
    try (FakeCloud cloud = new FakeCloud()) {
      // skipInvalidRows=false: BigQuery reports every row of the request, the valid ones as "stopped".
      cloud.bigQueryAnswers(200, """
          {"insertErrors":[{"index":0,"errors":[{"reason":"stopped","location":"","message":""}]},
           {"index":1,"errors":[{"reason":"invalid","location":"durationMs","message":"Cannot convert value"}]},
           {"index":2,"errors":[{"reason":"stopped","location":"","message":""}]}]}""");

      assertThatThrownBy(() -> bigQuery(URI.create(cloud.url()), Duration.ofSeconds(10))
          .send(SiemSenderTest.batch())).isInstanceOf(ExportException.class)
          .hasMessage("BigQuery refused row 1 of the batch: invalid at durationMs: Cannot convert value");
    }
  }

  @Test
  void anAnswerThatStallsAfterItsHeadersEndsAtTheDeadline() throws Exception {
    HttpServer stalled = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    stalled.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    // Headers and one byte of a promised hundred, then nothing for a minute: a connection that died mid-answer.
    stalled.createContext("/", ex -> {
      ex.getRequestBody().readAllBytes();
      ex.sendResponseHeaders(200, 100);
      OutputStream out = ex.getResponseBody();
      out.write('{');
      out.flush();
      try {
        Thread.sleep(60_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      ex.close();
    });
    stalled.start();
    try {
      URI base = URI.create("http://127.0.0.1:" + stalled.getAddress().getPort());
      assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThatThrownBy(() -> s3(base, "", Duration
          .ofMillis(800)).send(SiemSenderTest.batch())).isInstanceOf(ExportException.class)
          .hasMessageContaining("No answer from 127.0.0.1"));
      assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThatThrownBy(() -> bigQuery(base, Duration
          .ofMillis(800)).send(SiemSenderTest.batch())).isInstanceOf(ExportException.class)
          .hasMessageContaining("No answer from 127.0.0.1"));
    } finally {
      stalled.stop(0);
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static S3Sender s3(URI endpoint, String prefix, Duration timeout) {
    return new S3Sender(new ExportSettings.S3("acme-audit", prefix, "eu-central-1", endpoint, endpoint != null,
        "jsonl", null), StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE",
        "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")), HTTP, Clock.systemUTC(), timeout);
  }

  private static BigQuerySender bigQuery(URI base, Duration timeout) {
    GoogleCredentials token = GoogleCredentials.create(new AccessToken(FakeCloud.TOKEN,
        Date.from(Instant.now().plus(Duration.ofHours(1)))));
    return new BigQuerySender(new ExportSettings.BigQuery("acme-prod", "audit", "mcp_calls", null), token, base,
        HTTP, t -> {}, timeout);
  }
}
