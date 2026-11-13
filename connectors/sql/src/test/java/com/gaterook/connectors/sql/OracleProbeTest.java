package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OracleProbeTest {

  @Test
  void sessionPrivilegesDoNotCountAsSchemaChanges() {
    assertThat(OracleProbe.changesSchema("CREATE SESSION")).isFalse();
    assertThat(OracleProbe.changesSchema("ALTER SESSION")).isFalse();
    assertThat(OracleProbe.changesSchema("SELECT ANY TABLE")).isFalse();
    assertThat(OracleProbe.changesSchema("CREATE TABLE")).isTrue();
    assertThat(OracleProbe.changesSchema("CREATE VIEW")).isTrue();
    assertThat(OracleProbe.changesSchema("ALTER ANY TABLE")).isTrue();
    assertThat(OracleProbe.changesSchema("DROP ANY VIEW")).isTrue();
  }
}
