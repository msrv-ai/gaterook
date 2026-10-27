package ai.msrv.gaterook.core.platform;

import static ai.msrv.gaterook.core.platform.Masker.MASK;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** The masker's corpus (report 12 §8.6): every rule, the taint forms, trees, tables, personal data, no false hits. */
class MaskerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final Masker masker = Masker.standard();

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
      "aws-access-key-id | key AKIAIOSFODNN7EXAMPLE in use                          | key ‹masked› in use",
      "aws-secret-key    | aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY | aws_secret_access_key = ‹masked›",
      "jwt               | id eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U ok | id ‹masked› ok",
      "gateway-token     | token gwt_K9xPq2LmA7vT4nR8sW1yZ3bC6dF0hJ5kE9gU2iO4pXq here | token ‹masked› here",
      "github            | ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789 pushed        | ‹masked› pushed",
      "slack             | xoxb-1234567890-0987654321-AbCdEfGhIjKl sent           | ‹masked› sent",
      "gcp-oauth         | Bearer-less ya29.a0AfH6SMBx3Ld9kXa7mQ2-pRt8vWz_1234567890 x | Bearer-less ‹masked› x",
      "vault-token       | X-Vault-Token: hvs.CAESIJ1x2y3z4a5b6c7d8e9f0gHhIiJjKk   | X-Vault-Token: ‹masked›",
      "bearer-token      | Authorization: Bearer abcdefghij0123456789KLMNOP       | Authorization: Bearer ‹masked›",
      "basic-auth        | Authorization: Basic dXNlcjpwYXNzd29yZA==             | Authorization: Basic ‹masked›",
      "url-credentials   | dsn postgres://app:s3cr3t-pw@db.dev.internal:5432/app  | dsn postgres://app:‹masked›@db.dev.internal:5432/app",
      "kv-password       | login failed password=hunter22 user=bob               | login failed password=‹masked› user=bob",
      "kv-password       | DB_PASSWORD: \"S3cret!\"                               | DB_PASSWORD: \"‹masked›\"",
      "sql-password      | ALTER ROLE app PASSWORD 'n3w-p@ss''word'              | ALTER ROLE app PASSWORD ‹masked›",
      "sql-password      | CREATE USER gw IDENTIFIED BY \"pw-2024\"              | CREATE USER gw IDENTIFIED BY ‹masked›",
  })
  void everyRuleMasksItsSecretAndKeepsTheRestReadable(String rule, String input, String expected) {
    Masker.MaskedText masked = masker.text(input.strip(), new Taint(), MaskPolicy.SECRETS_ONLY);
    assertThat(masked.text()).isEqualTo(expected.strip());
    assertThat(masked.outcome().byRule()).containsKey(rule);
    assertThat(masked.outcome().fields()).isEqualTo(1);
  }

  @Test
  void syntheticStripeKeyIsMasked() {
    String token = "sk_live_" + "a".repeat(24);
    everyRuleMasksItsSecretAndKeepsTheRestReadable("stripe", "stripe " + token + " charge", "stripe ‹masked› charge");
  }

  @Test
  void googleKeysAndPrivateKeysAreMaskedWhole() {
    String google = "AIza" + "SyA" + "1234567890" + "abcdefghijklmnopqrstuv";
    assertThat(masker.text("key=" + google + ";", new Taint(), MaskPolicy.SECRETS_ONLY).text()).isEqualTo("key=" + MASK + ";");
    String pem = "before\n-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA1\nabc\n-----END RSA PRIVATE KEY-----\nafter";
    assertThat(masker.text(pem, new Taint(), MaskPolicy.SECRETS_ONLY).text()).isEqualTo("before\n" + MASK + "\nafter");
    assertThat(masker.text("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAA", new Taint(),
        MaskPolicy.SECRETS_ONLY).text()).isEqualTo(MASK);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "550e8400-e29b-41d4-a716-446655440000",
      "da39a3ee5e6b4b0d3255bfef95601890afd80709",
      "sha256:3f5a8c7e9b1d2f4a6c8e0b2d4f6a8c0e2b4d6f8a0c2e4b6d8f0a2c4e6b8d0f2a",
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "pod api-7d9f8c6b5-x2k4p restarted on node gke-prod-pool-1-3f9a2c4d-k8s2",
      "The token expired; check your password policy and secret rotation.",
      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "ai.msrv.gaterook.core.platform.db.DatabaseSelfCheck.afterSingletonsInstantiated",
      "GET /api/v1/orders/48213/items?expand=customer&limit=50 200 12ms",
      "trace 4f2a9c71e8d04b5a9c3e2b1f0a6d8e81 span 7a1c2e9b4d3f6a10",
      "2026-10-22T12:29:58.123456789Z 1729600000000 48240 pending",
      "lc1.eyJ2IjoxLCJjIjoiYXBwLWxvZ3MiLCJxIjoiYWJjZGVmMDEiLCJ0IjoxNzI5NjAwMDAwMDAwMDAwMDAwfQ.mU1uY2tZq3Lp0s9x",
      "the gwt_K9xP… token of codex-laptop",
  })
  void leavesOrdinaryDataAlone(String text) {
    Masker.MaskedText masked = masker.text(text, new Taint(), MaskPolicy.SECRETS_ONLY);
    assertThat(masked.text()).isEqualTo(text);
    assertThat(masked.outcome().masked()).isFalse();
  }

  @Test
  void entropyCatchesUndeclaredRandomTokens() {
    String token = "Zx8Qp3Lr7Vt2Wn9Ks4Jd6Hf1Bg5Mc0Ya8Ue";
    Masker.MaskedText masked = masker.text("session " + token + " issued", new Taint(), MaskPolicy.SECRETS_ONLY);
    assertThat(masked.text()).isEqualTo("session " + MASK + " issued");
    assertThat(masked.outcome().byRule()).containsEntry("entropy", 1);
    assertThat(masker.text(token, new Taint(), new MaskPolicy(false, Set.of(), List.of())).text()).isEqualTo(token);
  }

  @ParameterizedTest
  @ValueSource(strings = {"key sk.", "key abc.", "key x9.", "\"lc1.", "cursor=lc2.", "rsc.", "k8c.", "gc1.", "ac1."})
  void aShortDottedPrefixDoesNotHideAToken(String prefix) {
    // Only the gateway's own handles are exempt, and only in their shape (base64url JSON after
    // lc1./rgc./rsc./k8c./gc1./ac1.).
    String token = "Qm9vT3xKp8Lw2ZrT6vYc1NfH4sDgJ0aUeXbWkRiM";
    assertThat(masker.text(prefix + token + " end", new Taint(), MaskPolicy.SECRETS_ONLY).text())
        .isEqualTo(prefix + MASK + " end");
  }

  @Test
  void gatewayHandlesStayIntactWithTheirMac() {
    // A payload with '>' and '~' encodes to base64url with '-' and '_'; the MAC is as long as an HMAC-SHA256.
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"v\":1,\"q\":\"latency_p95>5~ms\",\"c\":\"app-logs\",\"t\":1729600000000000000,\"s\":[\"Zx8Qp3Lr7Vt2\"]}"
            .getBytes(StandardCharsets.UTF_8));
    assertThat(payload).contains("-");
    String mac = "mU1uY2tZq3Lp0s9xQm9vT3xKp8Lw2ZrT6vYc1NfH4sD";
    for (String handle : List.of("lc1." + payload + "." + mac, "rsc." + payload, "rgc." + payload,
        "k8c." + payload + "." + mac, "gc1." + payload + "." + mac, "ac1." + payload + "." + mac)) {
      String text = "{\"nextCursor\":\"" + handle + "\"} next: " + handle;
      assertThat(masker.text(text, new Taint(), MaskPolicy.SECRETS_ONLY).text()).as(handle).isEqualTo(text);
    }
    // The same characters glued to a standard-Base64 tail are not a handle.
    String lookalike = "lc1." + payload + "+" + mac + "==";
    assertThat(masker.text(lookalike, new Taint(), MaskPolicy.SECRETS_ONLY).text()).isNotEqualTo(lookalike);
  }

  @Test
  void propertyNamesResourceLinksColumnsAndNumbersAreValuesToo() {
    String stored = "cnry-stored-Zx81QwPl";
    Taint taint = new Taint();
    taint.add(stored);
    taint.add("84726193");
    JsonNode tree = JSON.readTree("""
        {"sessions":{"cnry-stored-Zx81QwPl":"alice","ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789":"bob","‹masked›":"x"},
         "account":84726193,"balance":84726193.0,"ok":123456,"tags":[84726193, 7]}""");

    Masker.MaskedTree masked = masker.tree(tree, taint, MaskPolicy.SECRETS_ONLY);

    JsonNode sessions = masked.tree().path("sessions");
    assertThat(sessions.propertyNames()).containsExactly(MASK, MASK + "#2", MASK + "#3");
    assertThat(sessions.path(MASK).asString()).isEqualTo("alice");
    assertThat(sessions.path(MASK + "#2").asString()).isEqualTo("bob");
    assertThat(masked.tree().path("account").asString()).isEqualTo(MASK);
    assertThat(masked.tree().path("balance").asString()).as("the plain decimal form").isEqualTo(MASK);
    assertThat(masked.tree().path("ok").asInt()).isEqualTo(123456);
    assertThat(masked.tree().path("tags").get(0).asString()).isEqualTo(MASK);
    assertThat(masked.tree().path("tags").get(1).asInt()).isEqualTo(7);
    assertThat(masked.outcome().fields()).as("two names, three numbers").isEqualTo(5);
    assertThat(JSON.writeValueAsString(masked.tree())).doesNotContain(stored).doesNotContain("8472619")
        .doesNotContain("ghp_");
    JsonNode cards = masker.tree(JSON.readTree("{\"card\":4111111111111111,\"order\":1729600000000}"), new Taint(),
        MaskPolicy.defaults(Env.PROD)).tree();
    assertThat(cards.path("card").asString()).as("a card number stored as a number, on prod").isEqualTo(MASK);
    assertThat(cards.path("order").asLong()).isEqualTo(1729600000000L);

    ArrayNode rows = JsonNodeFactory.instance.arrayNode();
    rows.addArray().add(84726193L).add("x");
    ToolResult.Table table = new ToolResult.Table(List.of(
        new ToolResult.Column(stored, "int8", "public", "t_" + stored, stored),
        new ToolResult.Column("note", "text", null, null, null)), rows, "page-" + stored);
    String presigned = "https://bucket.s3.amazonaws.com/report.csv?X-Amz-Security-Token="
        + java.net.URLEncoder.encode(stored + "/+=", StandardCharsets.UTF_8) + "&X-Amz-Signature=3f5a8c7e9b1d";
    ToolResult result = new ToolResult(List.of(new ToolResult.Content("resource_link", null, presigned, "text/csv",
        "export-" + stored + ".csv")), null, false, table, false, null, List.of(), 0, 0, null);

    Masker.MaskedResult out = masker.result(result, taint, MaskPolicy.SECRETS_ONLY);

    ToolResult.Content link = out.result().content().getFirst();
    assertThat(link.uri()).startsWith("https://bucket.s3.amazonaws.com/report.csv?X-Amz-Security-Token=" + MASK)
        .endsWith("&X-Amz-Signature=3f5a8c7e9b1d");
    assertThat(link.name()).isEqualTo("export-" + MASK + ".csv");
    assertThat(link.mimeType()).isEqualTo("text/csv");
    assertThat(out.result().table().columns().getFirst()).isEqualTo(
        new ToolResult.Column(MASK, "int8", "public", "t_" + MASK, MASK));
    assertThat(out.result().table().rows().get(0).get(0).asString()).isEqualTo(MASK);
    assertThat(out.result().table().nextCursor()).isEqualTo("page-" + MASK);
    assertThat(JSON.writeValueAsString(out.result())).doesNotContain(stored).doesNotContain("84726193");
  }

  @Test
  void binarySecretsAreMaskedInTheirEncodings() {
    byte[] keystore = new byte[48];
    new java.util.Random(7).nextBytes(keystore);
    keystore[0] = (byte) 0xFE; // never valid UTF-8: the resolver has no text view of it
    Taint taint = new Taint();
    taint.addBytes(keystore);
    java.util.HexFormat hex = java.util.HexFormat.of();
    String text = "b64 " + Base64.getEncoder().encodeToString(keystore) + " url "
        + Base64.getUrlEncoder().encodeToString(keystore) + " hex " + hex.formatHex(keystore) + " HEX "
        + hex.withUpperCase().formatHex(keystore);

    String masked = masker.text(text, taint, new MaskPolicy(false, Set.of(), List.of())).text();

    assertThat(masked).isEqualTo("b64 " + MASK + " url " + MASK + " hex " + MASK + " HEX " + MASK);
    assertThat(masker.tree(JsonNodeFactory.instance.objectNode().put("blob", keystore), taint,
        MaskPolicy.SECRETS_ONLY).tree().path("blob").asString()).as("a binary node").isEqualTo(MASK);
  }

  @Test
  void storedSecretsAreMaskedInEveryEncoding() {
    String secret = "cnry-7f3k-PASS word/+?&\"\\";
    Taint taint = new Taint();
    taint.add(secret);
    String base64 = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    String base64url = Base64.getUrlEncoder().withoutPadding().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    String urlForm = java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8);
    String json = JSON.writeValueAsString(secret);
    String text = "raw=" + secret + " b64=" + base64 + " b64url=" + base64url + " url=" + urlForm + " json=" + json
        + " pct=" + urlForm.replace("+", "%20");

    String masked = masker.text(text, taint, MaskPolicy.SECRETS_ONLY).text();

    assertThat(masked).doesNotContain("cnry-7f3k").doesNotContain(base64.substring(0, 12))
        .doesNotContain(base64url.substring(0, 12)).doesNotContain("cnry-7f3k-PASS+word");
    assertThat(masked).startsWith("raw=" + MASK + " b64=" + MASK);
  }

  /** Echoes the way libraries in other languages encode a value (computed with Python, PHP and Go rules). */
  static Stream<Arguments> echoesOfOtherLibraries() {
    String all = "cnry-~*'()! <&=> p\u00e4ss/w";
    return Stream.of(
        Arguments.of("Tr0ub@dor!x", "encodeURIComponent", "pw=Tr0ub%40dor!x"),
        Arguments.of("Tr0ub@dor!x", "RFC 3986", "pw=Tr0ub%40dor%21x"),
        Arguments.of("p\u00e4ssw\u00f6rd-42-xyz", "Python json.dumps",
            "{\"pw\":\"p\\u00e4ssw\\u00f6rd-42-xyz\"}"),
        Arguments.of("p\u00e4ssw\u00f6rd-42-xyz", "Jackson ESCAPE_NON_ASCII",
            "{\"pw\":\"p\\u00E4ssw\\u00F6rd-42-xyz\"}"),
        Arguments.of("p\u00e4ssw\u00f6rd-42-xyz", "URL of UTF-8",
            "?pw=p%C3%A4ssw%C3%B6rd-42-xyz"),
        Arguments.of("cnry/slash/pass", "PHP json_encode",
            "{\"pw\":\"cnry\\/slash\\/pass\"}"),
        Arguments.of(all, "encodeURIComponent",
            "pw=cnry-~*'()!%20%3C%26%3D%3E%20p%C3%A4ss%2Fw"),
        Arguments.of(all, "RFC 3986", "pw=cnry-~%2A%27%28%29%21%20%3C%26%3D%3E%20p%C3%A4ss%2Fw"),
        Arguments.of(all, "Python quote_plus",
            "pw=cnry-~%2A%27%28%29%21+%3C%26%3D%3E+p%C3%A4ss%2Fw"),
        Arguments.of(all, "PHP json_encode",
            "\"cnry-~*'()! <&=> p\\u00e4ss\\/w\""),
        Arguments.of(all, "Go encoding/json",
            "\"cnry-~*'()! \\u003c\\u0026=\\u003e p\u00e4ss/w\""),
        Arguments.of(all, "Gson",
            "\"cnry-~*\\u0027()! \\u003c\\u0026\\u003d\\u003e p\u00e4ss/w\""));
  }

  @ParameterizedTest(name = "{1}: {2}")
  @MethodSource("echoesOfOtherLibraries")
  void echoesEncodedByOtherLibrariesAreMasked(String secret, String library, String echo) {
    Taint taint = new Taint();
    taint.add(secret);

    String masked = masker.text("before " + echo + " after", taint, MaskPolicy.SECRETS_ONLY).text();

    assertThat(masked).as(library).contains(MASK).startsWith("before ").endsWith(" after")
        .doesNotContain("dor").doesNotContain("ssw").doesNotContain("slash").doesNotContain("cnry");
  }

  @Test
  void loginsAreMaskedAsBasicCredentialsToo() {
    Taint taint = new Taint();
    taint.addLogin("app", "s3cr3t-db-pass");
    String basic = Base64.getEncoder().encodeToString("app:s3cr3t-db-pass".getBytes(StandardCharsets.UTF_8));
    assertThat(masker.text("echo " + basic + " and s3cr3t-db-pass", taint, MaskPolicy.SECRETS_ONLY).text())
        .isEqualTo("echo " + MASK + " and " + MASK);
    taint.clear();
    assertThat(taint.isEmpty()).isTrue();
    assertThat(masker.text("s3cr3t-db-pass", taint, MaskPolicy.SECRETS_ONLY).text()).isEqualTo("s3cr3t-db-pass");
  }

  @Test
  void treesMaskSensitiveKeysPolicyFieldsAndCountFields() {
    JsonNode tree = JSON.readTree("""
        {"user":"bob","password":"hunter2","nested":{"client_secret":12345,"dbPassword":"x1","enabled":true,
         "secretName":"app-tls","nextPageToken":"CgwIxZ3Lr7Vt2Wn9Ks4Jd6Hf1Bg5Mc0Ya8UeQ2"},
         "orders":[{"customer_email":"a@b.example","id":1},{"customer_email":"c@d.example","id":2}],
         "headers":{"Authorization":"Bearer abcdefghij0123456789KLMNOP","x-request-id":"7f3k"},
         "note":"see postgres://app:pw12@db/app"}""");
    MaskPolicy policy = new MaskPolicy(true, Set.of(), List.of("orders.customer_email"));

    Masker.MaskedTree masked = masker.tree(tree, new Taint(), policy);

    JsonNode out = masked.tree();
    assertThat(out.path("user").asString()).isEqualTo("bob");
    assertThat(out.path("password").asString()).isEqualTo(MASK);
    assertThat(out.path("nested").path("client_secret").asString()).isEqualTo(MASK);
    assertThat(out.path("nested").path("dbPassword").asString()).isEqualTo(MASK);
    assertThat(out.path("nested").path("enabled").asBoolean()).isTrue();
    assertThat(out.path("nested").path("secretName").asString()).isEqualTo("app-tls");
    assertThat(out.path("nested").path("nextPageToken").asString()).startsWith("CgwI");
    assertThat(out.path("orders").get(1).path("customer_email").asString()).isEqualTo(MASK);
    assertThat(out.path("orders").get(1).path("id").asInt()).isEqualTo(2);
    assertThat(out.path("headers").path("Authorization").asString()).isEqualTo(MASK);
    assertThat(out.path("note").asString()).isEqualTo("see postgres://app:" + MASK + "@db/app");
    assertThat(masked.outcome().fields()).isEqualTo(7);
    assertThat(tree.path("password").asString()).as("the input is not changed").isEqualTo("hunter2");
  }

  @Test
  void resultsMaskTextsTablesAndConnectorPaths() {
    ArrayNode rows = JsonNodeFactory.instance.arrayNode();
    rows.addArray().add(1).add("ann@example.com").add("cnry-api-key-123");
    rows.addArray().add(2).add("bob@example.com").add("plain");
    ToolResult.Table table = new ToolResult.Table(List.of(
        new ToolResult.Column("id", "int4", "public", "customers", "id"),
        new ToolResult.Column("email", "text", "public", "customers", "email"),
        new ToolResult.Column("note", "text", "public", "customers", "note")), rows, null);
    Taint taint = new Taint();
    taint.add("cnry-api-key-123");
    ToolResult result = ToolResult.table(table, JSON.readTree("{\"echo\":{\"auth\":\"x-y-z-1\"}}"))
        .maskPaths(List.of("/echo/auth"));
    MaskPolicy policy = new MaskPolicy(true, Set.of(), List.of("customers.email"));

    Masker.MaskedResult masked = masker.result(result, taint, policy);

    ArrayNode out = masked.result().table().rows();
    assertThat(out.get(0).get(1).asString()).isEqualTo(MASK);
    assertThat(out.get(0).get(2).asString()).isEqualTo(MASK);
    assertThat(out.get(1).get(2).asString()).isEqualTo("plain");
    assertThat(masked.result().structuredContent().path("echo").path("auth").asString()).isEqualTo(MASK);
    assertThat(masked.result().maskedCount()).isEqualTo(masked.outcome().fields()).isEqualTo(4);
  }

  @Test
  void personalDataOnlyWhereThePolicySaysSo() {
    String text = "mail ann.lee@example.com, call +49 30 1234 5678, card 4111 1111 1111 1111, iban DE89 3704 0044 "
        + "0532 0130 00, order 1729600000000, id 4111111111111112";
    assertThat(masker.text(text, new Taint(), MaskPolicy.defaults(Env.DEV)).text()).isEqualTo(text);

    Masker.MaskedText prod = masker.text(text, new Taint(), MaskPolicy.defaults(Env.PROD));

    assertThat(prod.text()).isEqualTo("mail " + MASK + ", call " + MASK + ", card " + MASK + ", iban " + MASK
        + ", order 1729600000000, id 4111111111111112");
    assertThat(prod.outcome().byRule()).containsKeys("pii-email", "pii-phone", "pii-card", "pii-iban");
    assertThat(masker.text(text, new Taint(), new MaskPolicy(true, EnumSet.of(MaskPolicy.Pii.EMAIL), List.of()))
        .text()).startsWith("mail " + MASK + ", call +49");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "ann@example.com                              | ‹masked›",
      "a@b.example,c@d.example x@y.example          | ‹masked›,‹masked› ‹masked›",
      "card 4111-1111-1111-1111                     | card ‹masked›",
      "4111111111111111                             | ‹masked›",
      "pay to GB82WEST12345698765432                | pay to ‹masked›",
      "+442071838750                                | ‹masked›",
      "build 2026-10-22-1729600000000 ok            | build 2026-10-22-1729600000000 ok",
      "user@localhost has no TLD                    | user@localhost has no TLD"})
  void personalDataAtTheEdgesOfItsWindows(String text, String expected) {
    assertThat(masker.text(text, new Taint(), MaskPolicy.defaults(Env.PROD)).text()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "Card: 4111111111111111 12/27                 | Card: ‹masked› 12/27",
      "Card: 4111 1111 1111 1111 12/27              | Card: ‹masked› 12/27",
      "cvv 4111111111111111 123                     | cvv ‹masked› 123",
      "4111-1111-1111-1111 2                        | ‹masked› 2",
      "paid 4111111111111111 100.00 USD             | paid ‹masked› 100.00 USD",
      "amex 3782 822463 10005 0425                  | amex ‹masked› 0425",
      "visa 4222 2222 2222 2 05/28                  | visa ‹masked› 05/28",
      "order 57 4111111111111111                    | order 57 ‹masked›",
      // The whole run 424111111111111111 passes the Luhn check too: more is masked, never less.
      "order 42 4111111111111111                    | order ‹masked›",
      "4111 1111 1111 1111 1234 5678                | ‹masked› 1234 5678",
      // Luhn passes on 4111 11111111 1111, but that is not how a card is printed.
      "4111 11111111 1111 7                         | 4111 11111111 1111 7",
      "ids 4111111111111112 12                      | ids 4111111111111112 12",
      "at 1729600000000 2 rows                      | at 1729600000000 2 rows",
      "2026-10-22 4111111111111111                  | 2026-10-22 ‹masked›",
      // After a word or a sign the number is a card; after a digit it is the rest of a fraction.
      "card-4111111111111111                        | card-‹masked›",
      "order-4111111111111111 paid                  | order-‹masked› paid",
      "Card No.4111 1111 1111 1111                  | Card No.‹masked›",
      "x:-4111111111111111                          | x:-‹masked›",
      "ratio 1.4111111111111111                     | ratio 1.4111111111111111",
      "v2.4111111111111111                          | v2.4111111111111111"})
  void cardsNextToOtherNumbers(String text, String expected) {
    assertThat(masker.text(text, new Taint(), MaskPolicy.defaults(Env.PROD)).text()).isEqualTo(expected);
    var cells = JsonNodeFactory.instance.arrayNode().add(text);
    assertThat(masker.tree(cells, new Taint(), MaskPolicy.defaults(Env.PROD)).tree().get(0).asString())
        .as("a string cell").isEqualTo(expected);
  }

  @Test
  void logsAndSecretDetectionUseTheRulesWithoutTaint() {
    assertThat(masker.log("connecting to postgres://app:pw-123@db:5432/app with key AKIAIOSFODNN7EXAMPLE"))
        .isEqualTo("connecting to postgres://app:" + MASK + "@db:5432/app with key " + MASK);
    assertThat(masker.looksLikeSecret("ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789")).contains("github");
    assertThat(masker.looksLikeSecret("db.dev.internal")).isEmpty();
  }

  @Test
  void masksAMegabyteWithinTheBudget() {
    org.junit.jupiter.api.Assumptions.assumeFalse(Boolean.getBoolean("gateway.test.skipPerf"),
        "performance checks are off on this runner");
    StringBuilder sb = new StringBuilder(1 << 20);
    int i = 0;
    while (sb.length() < (1 << 20)) {
      sb.append("{\"ts\":\"2026-10-22T12:29:").append(10 + i % 50).append(".123Z\",\"level\":\"INFO\",\"msg\":\"GET ")
          .append("/api/orders/").append(48000 + i).append(" 200 12ms user=svc-").append(i % 7)
          .append("\",\"pod\":\"api-7d9f8c6b5-x2k4p\",\"trace\":\"4f2a9c71e8d04b5a9c3e2b1f0a6d8e81\"}\n");
      if (i % 500 == 0) {
        sb.append("WARN token=abcdefghijklmnopqrstuvwxyz012345 refused for postgres://app:pw@db/app\n");
      }
      i++;
    }
    String text = sb.toString();
    // A call's realistic taint: a login whose password has characters every encoding changes, an API token, AWS keys
    // with a session token, a GCP access token and a Vault token — a few dozen forms to look for.
    Taint taint = new Taint();
    taint.add("cnry-perf-secret-value");
    taint.addLogin("svc-app", "Tr0ub@dor!x~ p\u00e4ss/w=rd&<1>");
    taint.add("ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789");
    taint.add("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
    taint.add("IQoJb3JpZ2luX2VjEMr//////////wEaCWV1LWNlbnRyYWwtMSJHMEUCIQD+example/session+token==");
    taint.add("ya29.a0AfH6SMBx3Ld9kXa7mQ2-pRt8vWz_1234567890abcdef");
    taint.add("hvs.CAESIJ1x2y3z4a5b6c7d8e9f0gHhIiJjKk");
    assertThat(taint.forms().length).as("forms in this taint").isGreaterThan(30);
    // Dev (secrets and entropy) and prod (plus every PII detector): both within the target.
    for (MaskPolicy policy : java.util.List.of(MaskPolicy.SECRETS_ONLY, MaskPolicy.defaults(Env.PROD))) {
      for (int warmup = 0; warmup < 20; warmup++) {
        masker.text(text, taint, policy);
      }
      long best = Long.MAX_VALUE;
      for (int run = 0; run < 7; run++) {
        long start = System.nanoTime();
        Masker.MaskedText masked = masker.text(text, taint, policy);
        best = Math.min(best, System.nanoTime() - start);
        assertThat(masked.outcome().byRule()).containsKeys("bearer-token", "url-credentials");
      }
      double msPerMb = best / 1_000_000.0;
      System.out.printf("Masker: %.1f ms per MB (PII %s)%n", msPerMb, policy.pii().isEmpty() ? "off" : "on");
      assertThat(msPerMb).as("ms per MB (report 12 §7.7 target 40), PII " + policy.pii())
          .isLessThanOrEqualTo(40.0);
    }
  }
}
