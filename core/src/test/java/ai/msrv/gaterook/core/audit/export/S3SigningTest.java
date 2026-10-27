package ai.msrv.gaterook.core.audit.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.http.SdkHttpMethod;

/**
 * The S3 signature against AWS's own worked examples ("Signature Calculations for the Authorization Header:
 * Transferring Payload in a Single Chunk", S3 API reference): the same credentials, request and time give the
 * documented signature. The vectors were checked with an independent SigV4 implementation before they went here.
 */
class S3SigningTest {

  private static final AwsBasicCredentials EXAMPLE = AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE",
      "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
  private static final Clock AT = Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void aPutObjectIsSignedLikeAwssWorkedExample() {
    byte[] body = "Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Date", "Fri, 24 May 2013 00:00:00 GMT");
    headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");

    Map<String, List<String>> signed = S3Signing.sign(SdkHttpMethod.PUT,
        URI.create("https://examplebucket.s3.amazonaws.com/test%24file.text"), headers, body, EXAMPLE, "us-east-1",
        AT);

    assertThat(first(signed, "x-amz-content-sha256"))
        .isEqualTo("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");
    assertThat(first(signed, "x-amz-date")).isEqualTo("20130524T000000Z");
    String authorization = first(signed, "Authorization");
    assertThat(authorization).startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/"
        + "aws4_request");
    assertThat(authorization).contains("SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class");
    assertThat(authorization).endsWith("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd");
  }

  @Test
  void aGetObjectWithARangeIsSignedLikeAwssWorkedExample() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Range", "bytes=0-9");

    Map<String, List<String>> signed = S3Signing.sign(SdkHttpMethod.GET,
        URI.create("https://examplebucket.s3.amazonaws.com/test.txt"), headers, new byte[0], EXAMPLE, "us-east-1",
        AT);

    assertThat(first(signed, "Authorization")).endsWith(
        "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
            + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
  }

  private static String first(Map<String, List<String>> headers, String name) {
    return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
        .map(e -> e.getValue().getFirst()).orElseThrow(() -> new AssertionError("no " + name + " in " + headers));
  }
}
