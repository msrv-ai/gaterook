package ai.msrv.gaterook.connectors.sql;

/** Lexical and identifier rules used by a SQL server. */
public enum SqlSyntax {
  POSTGRES, MYSQL, ORACLE;

  static SqlSyntax of(boolean mysql) {
    return mysql ? MYSQL : POSTGRES;
  }
}
