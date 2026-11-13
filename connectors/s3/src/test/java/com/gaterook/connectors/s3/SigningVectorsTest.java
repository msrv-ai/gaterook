package com.gaterook.connectors.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * SigV4 for S3 against the examples AWS publishes ("Signature Calculations for the Authorization Header: Transferring
 * Payload in a Single Chunk"): the requests the connector builds sign to exactly AWS's signatures, a key with
 * {@code $} encoded once, the empty payload's hash, a query with an empty value. The same examples check the
 * independent verifier the other tests run on everything the fake server receives.
 */
class SigningVectorsTest {

  private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
  private static final URI BASE = URI.create("https://s3.amazonaws.com");

  private static S3Api.Call call() {
    S3Settings s = new S3Settings(null, "us-east-1", false, List.of(), null, null, false);
    ConnectionConfig cfg = new ConnectionConfig("conn-s3", "examples", "s3", Env.DEV, 1,
        JsonNodeFactory.instance.objectNode(), Set.of(), null);
    return new S3Api.Call(cfg, s, new TestSecrets(),
        TestCtx.of().clock(Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC)));
  }

  private static String signature(S3Request r) {
    URI uri = r.uri(BASE, r.virtualHosted(BASE, false));
    Map<String, List<String>> headers = S3Api.sign(call(), r, uri, AwsBasicCredentials.create(ACCESS_KEY,
        SECRET_KEY));
    String auth = headers.get("Authorization").getFirst();
    assertThat(auth).startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/"
        + "aws4_request");
    Map<String, List<String>> lower = new LinkedHashMap<>();
    headers.forEach((k, v) -> lower.put(k.toLowerCase(java.util.Locale.ROOT), v));
    String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    SigV4Check.verify(r.method(), target, lower, r.body() == null ? new byte[0] : r.body(), SECRET_KEY);
    return auth.substring(auth.indexOf("Signature=") + 10);
  }

  @Test
  void getObjectWithARange() {
    S3Request r = S3Request.of("GetObject", "GET", "examplebucket", "test.txt").header("range", "bytes=0-9");
    assertThat(r.uri(BASE, true)).hasToString("https://examplebucket.s3.amazonaws.com/test.txt");
    assertThat(signature(r)).isEqualTo("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
  }

  @Test
  void putObjectWithADollarInItsKey() {
    S3Request r = S3Request.of("PutObject", "PUT", "examplebucket", "test$file.text")
        .header("date", "Fri, 24 May 2013 00:00:00 GMT").header("x-amz-storage-class", "REDUCED_REDUNDANCY")
        .withBody("Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8));
    assertThat(r.uri(BASE, true).getRawPath()).isEqualTo("/test%24file.text");
    assertThat(r.bodySha256()).isEqualTo("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");
    assertThat(signature(r)).isEqualTo("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd");
  }

  @Test
  void getBucketLifecycleWithAnEmptyParameter() {
    S3Request r = S3Request.of("GetBucketLifecycle", "GET", "examplebucket", null).param("lifecycle", "");
    assertThat(r.uri(BASE, true)).hasToString("https://examplebucket.s3.amazonaws.com/?lifecycle=");
    assertThat(signature(r)).isEqualTo("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543");
  }

  @Test
  void listObjectsWithAQuery() {
    S3Request r = S3Request.of("ListObjects", "GET", "examplebucket", null).param("prefix", "J")
        .param("max-keys", "2");
    assertThat(r.uri(BASE, true)).hasToString("https://examplebucket.s3.amazonaws.com/?max-keys=2&prefix=J");
    assertThat(signature(r)).isEqualTo("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7");
  }

  @Test
  void keysAreEncodedOnceAndNeverNormalized() {
    S3Request r = S3Request.of("GetObject", "GET", "examplebucket", "a b/c+d=e/ü~*!'()&;,.txt");
    URI uri = r.uri(BASE, false);
    assertThat(uri.getRawPath()).isEqualTo("/examplebucket/a%20b/c%2Bd%3De/%C3%BC~%2A%21%27%28%29%26%3B%2C.txt");
    // The verifier canonicalizes from the raw path: the signature holds for the path as sent.
    signature(r);
    S3Request query = S3Request.of("ListObjectsV2", "GET", "examplebucket", null).param("prefix", "a b+c/ü")
        .param("delimiter", "/").param("list-type", "2");
    assertThat(query.uri(BASE, true).getRawQuery()).isEqualTo("delimiter=%2F&list-type=2&prefix=a%20b%2Bc%2F%C3%BC");
    signature(query);
  }
}
