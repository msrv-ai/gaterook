package com.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a KQL query of Azure Monitor Logs may reach: its connection's sources and nothing else. */
class KqlQueryTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "union workspace('other').AppTraces",
      "workspace(\"8f2c\").Heartbeat | take 1",
      "app('fabrikam-app').requests",
      "resource('/subscriptions/x/resourceGroups/rg').AzureActivity",
      "adx('https://help.kusto.windows.net/Samples').StormEvents",
      "arg('').Resources | take 1",
      "cluster('help').database('Samples').StormEvents",
      "AppTraces | join kind=inner (database('x').T) on OperationId",
      "workspace // a comment\n('other').AppTraces",
      "work​space('other').AppTraces",
      "['workspace']('other').AppTraces",
      "AppTraces | where x == materialized_view('mv')",
      "external_table('t') | take 1"})
  void crossResourceFunctionsAreRefused(String query) {
    assertThat(KqlQuery.outside(query)).as(query).hasValueSatisfying(r ->
        assertThat(r.reach()).isEqualTo(KqlQuery.Reach.CROSS_RESOURCE));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "externaldata (Url:string) [ h@'https://example.test/data.csv' ]",
      "AppTraces | evaluate http_request('https://example.test')",
      "evaluate sql_request('Server=x', 'select 1')",
      "macro-expand entity_group [T] as X (X | take 1)",
      "AppTraces | evaluate cosmosdb_sql_request('x', 'y')"})
  void externalDataAndPluginsAreRefused(String query) {
    assertThat(KqlQuery.outside(query)).as(query).hasValueSatisfying(r ->
        assertThat(r.reach()).isEqualTo(KqlQuery.Reach.EXTERNAL));
  }

  @ParameterizedTest
  @ValueSource(strings = {".show tables", "let a = 1;\n.drop table T", "  .set-or-append T <| print 1"})
  void controlCommandsAreRefused(String query) {
    assertThat(KqlQuery.outside(query)).as(query).hasValueSatisfying(r ->
        assertThat(r.reach()).isEqualTo(KqlQuery.Reach.COMMAND));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "AppTraces | where Message has 'timeout'",
      "AppRequests | extend app = AppRoleName | where app == 'api'",
      "AppRequests | project TimeGenerated, workspaceName = 'x', resourceId = _ResourceId",
      "search * | where Level == 'Error'",
      "AzureDiagnostics | where Category == 'FunctionAppLogs' | summarize count() by bin(TimeGenerated, 5m)",
      "requests | where success == false | extend v = 0.5"})
  void ordinaryQueriesPass(String query) {
    assertThat(KqlQuery.outside(query)).as(query).isEmpty();
  }

  @Test
  void aggregatesAreTables() {
    assertThat(KqlQuery.aggregates("AppRequests | summarize count() by AppRoleName")).isTrue();
    assertThat(KqlQuery.aggregates("AppRequests | count")).isTrue();
    assertThat(KqlQuery.aggregates("AppRequests | make-series n = count() on TimeGenerated step 5m")).isTrue();
    assertThat(KqlQuery.aggregates("AppRequests | where Name has 'summarize'")).isFalse();
    assertThat(KqlQuery.aggregates("AppRequests | project countOf = 1 // | summarize")).isFalse();
    assertThat(KqlQuery.aggregates("AppRequests | take 5")).isFalse();
  }
}
