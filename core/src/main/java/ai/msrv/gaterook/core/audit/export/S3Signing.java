package ai.msrv.gaterook.core.audit.export;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

/**
 * AWS Signature Version 4 for S3 requests sent with {@code java.net.http} (ARCHITECTURE.md §10 K5: the signer of
 * {@code software.amazon.awssdk:http-auth-aws}, no S3 SDK): S3's flavour — the path encoded once and not normalized,
 * the payload's SHA-256 signed in {@code x-amz-content-sha256}. The signer adds {@code Host}, {@code x-amz-date},
 * {@code x-amz-content-sha256}, {@code x-amz-security-token} (session credentials) and {@code Authorization}; the
 * caller sends every header back except {@code Host}, which {@code java.net.http} writes itself, to the same value.
 */
public final class S3Signing {

  private S3Signing() {}

  /**
   * The headers to send with the request, signed.
   *
   * @param uri     the request's URI, its path already encoded as it goes on the wire
   * @param headers headers to sign besides those the signer adds ({@code Content-Type}, {@code Content-MD5}, …)
   * @param clock   the signing time ({@code x-amz-date}); tests sign a known vector at its time
   */
  public static Map<String, List<String>> sign(SdkHttpMethod method, URI uri, Map<String, String> headers,
      byte[] body, AwsCredentialsIdentity credentials, String region, Clock clock) {
    SdkHttpRequest.Builder request = SdkHttpRequest.builder().method(method).uri(uri);
    headers.forEach(request::putHeader);
    SignedRequest signed = AwsV4HttpSigner.create().sign(r -> r.identity(credentials)
        .request(request.build())
        .payload(ContentStreamProvider.fromByteArray(body))
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, "s3")
        .putProperty(AwsV4HttpSigner.REGION_NAME, region)
        .putProperty(AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE, false)
        .putProperty(AwsV4FamilyHttpSigner.NORMALIZE_PATH, false)
        .putProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, true)
        .putProperty(HttpSigner.SIGNING_CLOCK, clock));
    return new LinkedHashMap<>(signed.request().headers());
  }
}
