package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.postgresql.PGResultSetMetaData;
import tools.jackson.databind.JsonNode;

/** A long value never crosses into the gateway whole: what each driver's path reads, and what the cell then says. */
class ResultReaderBoundsTest {

  private static final int MAX_CELL_CHARS = 1000;
  /** 4 × 1000 + 4 bytes: what a read takes of one value at most. */
  private static final int CAP = ResultReader.fieldSize(MAX_CELL_CHARS);

  @Test
  void oracleLobsAreReadThroughTheirLocatorsOnly() throws Exception {
    ResultSet rows = rows(SqlSyntax.ORACLE, new int[] {Types.CLOB, Types.BLOB}, new String[] {"CLOB", "BLOB"});
    Clob clob = mock(Clob.class);
    when(rows.getClob(1)).thenReturn(clob);
    when(clob.length()).thenReturn(1_000_000L);
    when(clob.getSubString(1, MAX_CELL_CHARS + 1)).thenReturn("c".repeat(MAX_CELL_CHARS + 1));
    Blob blob = mock(Blob.class);
    when(rows.getBlob(2)).thenReturn(blob);
    when(blob.length()).thenReturn(5_000L);
    when(blob.getBytes(1, 256)).thenReturn(new byte[256]);

    JsonNode row = ResultReader.read(rows, 10, 100_000, MAX_CELL_CHARS, SqlSyntax.ORACLE, false).table().rows()
        .get(0);

    assertThat(row.get(0).asString()).isEqualTo("c".repeat(MAX_CELL_CHARS) + "…‹truncated 999,000 chars›");
    assertThat(row.get(1).asString()).isEqualTo("\\x" + "00".repeat(256) + "…‹5000 bytes›");
    verify(rows, never()).getString(anyInt());
    verify(rows, never()).getBytes(anyInt());
    verify(clob).free();
    verify(blob).free();
  }

  @Test
  void longColumnsAreReadThroughBoundedStreams() throws Exception {
    ResultSet rows = rows(SqlSyntax.SQLSERVER, new int[] {Types.LONGVARCHAR, Types.LONGNVARCHAR,
        Types.LONGVARBINARY, Types.LONGVARBINARY}, new String[] {"varchar", "nvarchar", "varbinary", "varbinary"});
    when(rows.getCharacterStream(1)).thenReturn(new StringReader("a".repeat(1_000_000)));
    when(rows.getCharacterStream(2)).thenReturn(new StringReader("b".repeat(3_000)));
    when(rows.getBinaryStream(3)).thenReturn(new ByteArrayInputStream(new byte[100_000]));
    when(rows.getBinaryStream(4)).thenReturn(new ByteArrayInputStream(new byte[1_000]));

    JsonNode row = ResultReader.read(rows, 10, 100_000, MAX_CELL_CHARS, SqlSyntax.SQLSERVER, false).table().rows()
        .get(0);

    // Read up to CAP and no further: the value is at least CAP + 1 long, so at least 3,005 characters are left out.
    assertThat(row.get(0).asString()).isEqualTo("a".repeat(MAX_CELL_CHARS) + "…‹truncated 3,005+ chars›");
    assertThat(row.get(1).asString()).isEqualTo("b".repeat(MAX_CELL_CHARS) + "…‹truncated 2,000 chars›");
    assertThat(row.get(2).asString()).isEqualTo("\\x" + "00".repeat(256) + "…‹" + (CAP + 1) + "+ bytes›");
    assertThat(row.get(3).asString()).isEqualTo("\\x" + "00".repeat(256) + "…‹1000 bytes›");
    verify(rows, never()).getString(anyInt());
    verify(rows, never()).getBytes(anyInt());
  }

  @Test
  void postgresTextIsDecodedFromTheRowsBytesOnlyAsFarAsItIsShown() throws Exception {
    ResultSet rows = rows(SqlSyntax.POSTGRES, new int[] {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.OTHER},
        new String[] {"text", "text", "text", "json"});
    PGResultSetMetaData pg = mock(PGResultSetMetaData.class);
    when(rows.getMetaData().unwrap(PGResultSetMetaData.class)).thenReturn(pg);
    when(pg.getFormat(1)).thenReturn(0);
    when(pg.getFormat(2)).thenReturn(0);
    when(pg.getFormat(3)).thenReturn(0);
    when(pg.getFormat(4)).thenReturn(1);
    when(rows.getBytes(1)).thenReturn("яё".repeat(100_000).getBytes(StandardCharsets.UTF_8));
    when(rows.getBytes(2)).thenReturn("日".repeat(2_000).getBytes(StandardCharsets.UTF_8));
    when(rows.getBytes(3)).thenReturn("short".getBytes(StandardCharsets.UTF_8));
    when(rows.getString(4)).thenReturn("{\"binary\":\"format\"}");

    JsonNode row = ResultReader.read(rows, 10, 100_000, MAX_CELL_CHARS, SqlSyntax.POSTGRES, false).table().rows()
        .get(0);

    // CAP bytes are 2,002 two-byte characters: at least 2,003 − 1,000 are left out.
    assertThat(row.get(0).asString()).isEqualTo("яё".repeat(MAX_CELL_CHARS / 2) + "…‹truncated 1,003+ chars›");
    // CAP bytes end inside a three-byte character, which is left out rather than decoded as garbage.
    assertThat(row.get(1).asString()).isEqualTo("日".repeat(MAX_CELL_CHARS) + "…‹truncated 335+ chars›")
        .doesNotContain("�");
    assertThat(row.get(2).asString()).isEqualTo("short");
    assertThat(row.get(3).asString()).isEqualTo("{\"binary\":\"format\"}");
    verify(rows, never()).getString(1);
    verify(rows, never()).getString(2);
    verify(rows, never()).getString(3);
    verify(rows, never()).getBytes(4);
  }

  @Test
  void cutMarkersSayWhatIsKnownOfTheLength() {
    assertThat(ResultReader.text("abcdef", 4).asString()).isEqualTo("abcd…‹truncated 2 chars›");
    assertThat(ResultReader.text("abcd", 4, 10, false).asString()).isEqualTo("abcd…‹truncated 6 chars›");
    assertThat(ResultReader.text("abcd", 4, 5, true).asString()).isEqualTo("abcd…‹truncated 1+ chars›");
    assertThat(ResultReader.text("ab😀", 3, 4, false).asString()).isEqualTo("ab…‹truncated 2 chars›");
    assertThat(ResultReader.hex(new byte[300]).length()).isEqualTo(2 + 512 + "…‹300 bytes›".length());
    assertThat(ResultReader.hex(new byte[10], 10, false)).isEqualTo("\\x" + "00".repeat(10));
    assertThat(ResultReader.hex(new byte[10], 11, true)).isEqualTo("\\x" + "00".repeat(10) + "…‹11+ bytes›");
  }

  private static ResultSet rows(SqlSyntax syntax, int[] types, String[] names) throws Exception {
    ResultSet rows = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rows.getMetaData()).thenReturn(md);
    when(rows.next()).thenReturn(true, false);
    when(md.getColumnCount()).thenReturn(types.length);
    for (int i = 0; i < types.length; i++) {
      when(md.getColumnLabel(i + 1)).thenReturn("c" + (i + 1));
      when(md.getColumnType(i + 1)).thenReturn(types[i]);
      when(md.getColumnTypeName(i + 1)).thenReturn(names[i]);
    }
    return rows;
  }
}
