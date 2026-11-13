package com.gaterook.core.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gaterook.connector.api.TemplateKind;
import com.gaterook.core.connectors.ConnectorRegistry;
import com.gaterook.core.platform.Masker;
import com.gaterook.core.platform.ProblemException;
import com.gaterook.core.platform.SchemaValidator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

class TemplateSharesTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final TemplateService templates = mock(TemplateService.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final TemplateShares shares = new TemplateShares(templates, registry, new SchemaValidator(), Masker.standard());

  @Test
  void copiesDefinitionsWithoutTransferringSourceTrust() {
    var runbook = json.readTree("""
        {"name":"check","command":"docker ps","readOnly":true,"readOnlyConfirmedFor":"docker ps"}
        """);
    var copied = TemplateShares.portableDefinition("ssh-runbook", runbook);
    assertThat(copied.path("readOnly").asBoolean()).isFalse();
    assertThat(copied.has("readOnlyConfirmedFor")).isFalse();
    assertThat(runbook.path("readOnly").asBoolean()).isTrue();
    var api = json.readTree("""
        {"operations":[{"method":"POST","nature":"read","readOnlyConfirmed":true},
        {"method":"GET","nature":"read"}]}
        """);
    var copiedApi = TemplateShares.portableDefinition("openapi", api);
    assertThat(copiedApi.path("operations").get(0).path("nature").asString()).isEqualTo("mutation");
    assertThat(copiedApi.path("operations").get(0).has("readOnlyConfirmed")).isFalse();
    assertThat(copiedApi.path("operations").get(1).path("nature").asString()).isEqualTo("read");
  }

  @Test
  void exportIncludesOnlyVerifiedActiveDefinitionsOfSelectedConnection() {
    when(templates.list(null)).thenReturn(List.of(info("selected", "c1", "active", true),
        info("other", "c2", "active", true), info("off", "c1", "disabled", true),
        info("changed", "c1", "active", false)));
    when(templates.definition("selected")).thenReturn(Optional.of(json.readTree("{\"name\":\"query\",\"sql\":\"SELECT 1\"}")));
    var result = shares.exportFor("c1", false);
    assertThat(result.templates().size()).isEqualTo(1);
    assertThat(result.templates().get(0).path("id").asString()).isEqualTo("selected");
    assertThat(result.warnings()).hasSize(1);
  }

  @Test
  void inlineCredentialsAreExcludedWhenSecretsAreNotSelected() {
    var definition = json.readTree("{\"name\":\"query\",\"sql\":\"SELECT 1\",\"password\":\"private-value\"}");
    when(templates.list(null)).thenReturn(List.of(info("selected", "c1", "active", true)));
    when(templates.definition("selected")).thenReturn(Optional.of(definition));
    assertThat(shares.exportFor("c1", false).templates().isEmpty()).isTrue();
    assertThat(shares.exportFor("c1", true).templates().size()).isEqualTo(1);
    ArrayNode incoming = json.createArrayNode();
    incoming.addObject().put("id", "source").put("name", "query").put("kind", "sql-query").set("definition", definition);
    var result = shares.importFor(incoming, "destination", false, "Reviewed import");
    assertThat(result.ids()).isEmpty();
    assertThat(result.warnings()).hasSize(1);
  }

  @Test
  void validationRejectsLocalSecretReferencesAndDuplicateIdsWithoutCompiling() {
    TemplateKind kind = mock(TemplateKind.class);
    when(kind.connectionTypes()).thenReturn(List.of("postgres"));
    when(kind.definitionSchema()).thenReturn(json.readTree("{\"type\":\"object\"}"));
    when(registry.templateKinds()).thenReturn(Map.of("sql-query", kind));
    var malicious = json.readTree("""
        [{"id":"one","name":"query","kind":"sql-query","definition":{"params":{"value":{"$secret":"local-id"}}}}]
        """);
    assertThatThrownBy(() -> shares.validate(malicious, "postgres")).isInstanceOf(ProblemException.class);
    var duplicate = json.readTree("""
        [{"id":"one","name":"a","kind":"sql-query","definition":{}},
        {"id":"one","name":"b","kind":"sql-query","definition":{}}]
        """);
    assertThatThrownBy(() -> shares.validate(duplicate, "postgres")).isInstanceOf(ProblemException.class);
    verifyNoInteractions(templates);
  }

  @Test
  void importRemapsTemplateIdsAndFailsInsteadOfSilentlyDroppingCompilationErrors() {
    var incoming = json.readTree("""
        [{"id":"source","name":"query","kind":"sql-query","definition":{"name":"query","sql":"SELECT 1"}}]
        """);
    when(templates.create(eq("sql-query"), eq("copy"), eq("query"), any(), any(), any()))
        .thenReturn(new TemplateService.SaveResult(info("new-id", "new-c", "active", true),
            new TemplateKind.CompileResult(List.of(), List.of(), List.of(), List.of(), null)));
    assertThat(shares.importFor(incoming, "copy", true, null).ids()).containsEntry("source", "new-id");
    when(templates.create(eq("sql-query"), eq("copy"), eq("query"), any(), any(), any()))
        .thenReturn(new TemplateService.SaveResult(null,
            new TemplateKind.CompileResult(List.of(), List.of(), List.of("Unsafe definition"), List.of(), null)));
    assertThatThrownBy(() -> shares.importFor(incoming, "copy", true, null)).isInstanceOf(ProblemException.class);
  }

  private static TemplateService.TemplateInfo info(String id, String connection, String status, boolean verified) {
    return new TemplateService.TemplateInfo(id, "sql-query", "query", connection, "database", 1, status,
        null, null, Instant.EPOCH, verified, null);
  }
}
