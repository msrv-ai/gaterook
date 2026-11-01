package ai.msrv.gaterook.connectors.cloudauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/**
 * SigV4 of the gateway's own requests (ARCHITECTURE.md §7.6): the AWS test suite's vectors, and a POST to an MCP server
 * checked by computing its signature again from what goes out — host, date, session token and the body's hash.
 */
class AwsSigV4Test {

  private static final AwsBasicCredentials EXAMPLE = AwsBasicCredentials.create("AKIDEXAMPLE",
      "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
  private static final Clock SUITE = Clock.fixed(Instant.parse("2015-08-30T12:36:00Z"), ZoneOffset.UTC);
  private static final Pattern AUTHORIZATION = Pattern.compile("AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/"
      + "([^/]+)/aws4_request, SignedHeaders=([^,]+), Signature=([0-9a-f]{64})");

  @Test
  void signsTheTestSuitesVanillaRequestWithTheBodysHash() throws Exception {
    AwsSigV4.Target target = new AwsSigV4.Target("service", "us-east-1");
    Map<String, String> get = AwsSigV4.sign(EXAMPLE, target, "GET", URI.create("https://example.amazonaws.com/"), null,
        SUITE);
    assertThat(get).containsEntry("X-Amz-Date", "20150830T123600Z").containsEntry("Host", "example.amazonaws.com")
        // The SDK signs the hash of the body as a header too, as it does for every AWS service.
        .containsEntry("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    Matcher m = AUTHORIZATION.matcher(get.get("Authorization"));
    assertThat(m.matches()).isTrue();
    assertThat(m.group(1) + " " + m.group(2) + " " + m.group(5)).isEqualTo("AKIDEXAMPLE 20150830 "
        + "host;x-amz-content-sha256;x-amz-date");
    assertThat(m.group(6)).isEqualTo(expected("GET", "/", "", m.group(5), get, new byte[0],
        "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20150830", "us-east-1", "service"));
  }

  @Test
  void signsAPostToAnMcpServerOverItsBodyWithTheSessionToken() throws Exception {
    AwsSessionCredentials session = AwsSessionCredentials.create("ASIAEXAMPLEEXAMPLE12", "secret/key+example",
        "session-token-1");
    Clock clock = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);
    URI url = URI.create("https://aws-mcp.us-east-1.api.aws/mcp?oauth=initialize");
    byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8);
    AwsSigV4.Target target = AwsSigV4.fromHost(url.getHost()).orElseThrow();

    Map<String, String> headers = AwsSigV4.sign(session, target, "POST", url, body, clock);

    assertThat(target).isEqualTo(new AwsSigV4.Target("aws-mcp", "us-east-1"));
    assertThat(headers).containsEntry("X-Amz-Security-Token", "session-token-1")
        .containsEntry("Host", "aws-mcp.us-east-1.api.aws");
    Matcher m = AUTHORIZATION.matcher(headers.get("Authorization"));
    assertThat(m.matches()).isTrue();
    assertThat(m.group(1)).isEqualTo("ASIAEXAMPLEEXAMPLE12");
    assertThat(m.group(3) + "/" + m.group(4)).isEqualTo("us-east-1/aws-mcp");
    assertThat(m.group(5)).doesNotContain("content-type").contains("host").contains("x-amz-security-token");
    assertThat(m.group(6)).isEqualTo(expected("POST", "/mcp", "oauth=initialize", m.group(5), headers, body,
        "secret/key+example", "20261101", "us-east-1", "aws-mcp"));
    // Another body is another signature: a request changed after signing is refused.
    Map<String, String> other = AwsSigV4.sign(session, target, "POST", url, "{}".getBytes(StandardCharsets.UTF_8),
        clock);
    assertThat(other.get("Authorization")).isNotEqualTo(headers.get("Authorization"));
  }

  @Test
  void readsTheServiceAndRegionOfAwsHosts() {
    assertThat(AwsSigV4.fromHost("aws-mcp.eu-west-1.api.aws")).contains(new AwsSigV4.Target("aws-mcp", "eu-west-1"));
    assertThat(AwsSigV4.fromHost("bedrock-agentcore.us-west-2.amazonaws.com"))
        .contains(new AwsSigV4.Target("bedrock-agentcore", "us-west-2"));
    assertThat(AwsSigV4.fromHost("abc123.execute-api.ap-southeast-2.amazonaws.com"))
        .contains(new AwsSigV4.Target("execute-api", "ap-southeast-2"));
    assertThat(AwsSigV4.fromHost("mcp.linear.app")).isEmpty();
    assertThat(AwsSigV4.fromHost("s3.amazonaws.com")).isEmpty();
    // What the settings name wins; what they leave out comes from the host, or there is no target.
    assertThat(AwsSigV4.target(null, "eu-west-2", "aws-mcp.us-east-1.api.aws"))
        .contains(new AwsSigV4.Target("aws-mcp", "eu-west-2"));
    assertThat(AwsSigV4.target("bedrock-agentcore", "us-west-2", "127.0.0.1"))
        .contains(new AwsSigV4.Target("bedrock-agentcore", "us-west-2"));
    assertThat(AwsSigV4.target("aws-mcp", null, "127.0.0.1")).isEmpty();
    assertThat(AwsSigV4.target("Not A Service", "us-east-1", "127.0.0.1")).isEmpty();
    assertThatThrownBy(() -> new AwsSigV4.Target("aws mcp", "us-east-1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AwsSigV4.Target("aws-mcp", "mars-1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void writesTheHostAsClientsSendIt() {
    assertThat(AwsSigV4.host(URI.create("https://aws-mcp.us-east-1.api.aws/mcp"))).isEqualTo("aws-mcp.us-east-1.api.aws");
    assertThat(AwsSigV4.host(URI.create("https://example.com:443/mcp"))).isEqualTo("example.com");
    assertThat(AwsSigV4.host(URI.create("http://127.0.0.1:8080/mcp"))).isEqualTo("127.0.0.1:8080");
  }

  /** SigV4 computed by hand from the request as it goes out, for the headers the signature names. */
  private static String expected(String method, String path, String query, String signedHeaders,
      Map<String, String> sent, byte[] body, String secret, String date, String region, String service)
      throws Exception {
    Map<String, String> lower = new TreeMap<>();
    sent.forEach((k, v) -> lower.put(k.toLowerCase(java.util.Locale.ROOT), v.strip()));
    StringBuilder canonicalHeaders = new StringBuilder();
    for (String h : signedHeaders.split(";")) {
      canonicalHeaders.append(h).append(':').append(lower.get(h)).append('\n');
    }
    String canonical = method + "\n" + path + "\n" + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
        + hex(MessageDigest.getInstance("SHA-256").digest(body));
    String toSign = "AWS4-HMAC-SHA256\n" + lower.get("x-amz-date") + "\n" + date + "/" + region + "/" + service
        + "/aws4_request\n" + hex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    byte[] key = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
    key = hmac(key, region);
    key = hmac(key, service);
    key = hmac(key, "aws4_request");
    return hex(hmac(key, toSign));
  }

  private static byte[] hmac(byte[] key, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
