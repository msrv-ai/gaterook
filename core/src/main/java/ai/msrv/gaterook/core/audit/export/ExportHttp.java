package ai.msrv.gaterook.core.audit.export;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One request of an S3 or BigQuery export, with a deadline for the whole answer. {@code HttpRequest.timeout} ends with
 * the headers: an answer that stalls in its body would hold the run — and with it the destination's lock, so the
 * scheduler skips it and Export now waits in vain — for as long as the connection lives. The body is read up to
 * {@link #MAX_ANSWER}: a destination's answer is only ever read to report it.
 */
final class ExportHttp {

  /** The most of an answer read: an S3 error document, a BigQuery answer with an error for each of 500 rows. */
  static final int MAX_ANSWER = 1 << 20;

  private ExportHttp() {}

  /**
   * Sends {@code request} and waits at most {@code within} for its whole answer; the exchange is cancelled past it.
   *
   * @throws ExportException when no answer came in time or the connection failed, in words for the person
   */
  static HttpResponse<String> exchange(HttpClient http, HttpRequest request, Duration within)
      throws ExportException {
    String host = request.uri().getHost();
    CompletableFuture<HttpResponse<String>> answer = http.sendAsync(request, info ->
        HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.limiting(
            HttpResponse.BodySubscribers.ofByteArray(), MAX_ANSWER), b -> new String(b, StandardCharsets.UTF_8)));
    try {
      return answer.get(within.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      answer.cancel(true);
      throw new ExportException("No answer from " + host + " within " + within.toSeconds() + " s");
    } catch (InterruptedException e) {
      answer.cancel(true);
      Thread.currentThread().interrupt();
      throw new ExportException("The export was stopped", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
        if (String.valueOf(t.getMessage()).contains("exceeds capacity")) {
          throw new ExportException(host + " answered with more than " + MAX_ANSWER / (1 << 20) + " MB", cause);
        }
      }
      throw new ExportException("Couldn't reach " + host + ": " + (cause.getMessage() == null
          ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
    }
  }
}
