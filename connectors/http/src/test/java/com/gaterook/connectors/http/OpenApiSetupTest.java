package com.gaterook.connectors.http;

import static com.gaterook.connectors.http.Fixtures.cfg;
import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Env;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenApiSetupTest {
  private final OpenApiTemplateKind kind = (OpenApiTemplateKind) new HttpConnector().templateKinds().getFirst();

  private static final String DOCUMENT = """
      openapi: 3.1.0
      info: {title: Example API, version: '1'}
      servers:
        - url: https://api.example.com/{version}
          variables: {version: {default: v1}}
      security: [{token: []}]
      paths:
        /items:
          get: {operationId: listItems, responses: {'200': {description: OK}}}
        /public:
          get: {security: [], responses: {'200': {description: OK}}}
        /oauth:
          post: {security: [{oauth: [write]}], responses: {'200': {description: OK}}}
        /alternative:
          get: {security: [{oauth: []}, {token: []}], responses: {'200': {description: OK}}}
        /combined:
          get: {security: [{token: [], key: []}], responses: {'200': {description: OK}}}
      components:
        securitySchemes:
          token: {type: http, scheme: bearer, example: NEVER_COPY_THIS}
          key: {type: apiKey, in: header, name: X-API-Key}
          oauth: {type: oauth2, flows: {}}
      """;

  @Test
  void inspectionSuggestsOnlyNonSecretSettingsAndResolvesServerDefaults() {
    var result = kind.inspectDocument(DOCUMENT.getBytes(StandardCharsets.UTF_8));
    assertThat(result.errors()).isEmpty();
    assertThat(result.info().path("servers").get(0).asString()).isEqualTo("https://api.example.com/v1");
    assertThat(result.info().at("/authentication/0/config").toString()).isEqualTo("{\"type\":\"bearer\"}");
    assertThat(result.info().at("/authentication/2/supported").asBoolean()).isFalse();
    assertThat(result.info().toString()).doesNotContain("NEVER_COPY_THIS");
  }

  @Test
  void securityOverridesAlternativesAndUnsupportedRequirementsAreExplicit() {
    var config = cfg("example", Env.DEV, """
        {"baseUrls":["https://api.example.com/v1"],"auth":{"type":"bearer"}}""");
    var result = kind.importDocument(DOCUMENT.getBytes(StandardCharsets.UTF_8), config, null);
    assertThat(result.operations().stream().filter(o -> o.skipped() == null).map(o -> o.path()))
        .containsExactly("/items", "/public", "/alternative");
    assertThat(result.operations().get(2).skipped()).contains("unsupported authentication oauth");
    assertThat(result.operations().get(4).skipped()).isNotBlank();
    var anonymous = cfg("example", Env.DEV, """
        {"baseUrls":["https://api.example.com/v1"],"auth":{"type":"none"}}""");
    assertThat(kind.importDocument(DOCUMENT.getBytes(StandardCharsets.UTF_8), anonymous, null)
        .operations().stream().filter(o -> o.skipped() == null).map(o -> o.path())).containsExactly("/public");
  }

  @Test
  void inspectionUsesTheSameBoundedParserAndRefusesExternalReferences() {
    var invalid = DOCUMENT + "\nx-test: {$ref: 'https://example.com/private'}\n";
    assertThat(kind.inspectDocument(invalid.getBytes(StandardCharsets.UTF_8)).errors()).isNotEmpty();
    assertThat(kind.inspectDocument("swagger: '2.0'".getBytes(StandardCharsets.UTF_8)).errors()).isNotEmpty();
  }

  @Test
  void unsupportedMethodsAndServerOverridesAreVisibleInsteadOfSilentlyChanged() {
    String document = """
        openapi: 3.1.0
        info: {title: Example API, version: '1'}
        paths:
          /items:
            options: {responses: {'200': {description: OK}}}
            get: {servers: [{url: 'https://other.example.com'}], responses: {'200': {description: OK}}}
        """;
    var config = cfg("example", Env.DEV, """
        {"baseUrls":["https://api.example.com/v1"],"auth":{"type":"none"}}""");
    var result = kind.importDocument(document.getBytes(StandardCharsets.UTF_8), config, null);
    assertThat(result.operations()).hasSize(2).allMatch(o -> o.skipped() != null && !o.selected());
  }
}
