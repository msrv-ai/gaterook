package ai.msrv.gaterook.connectors.sql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A template's SQL with {@code :name} placeholders, compiled for a {@code PreparedStatement} (report 10 §6.6, §7.5).
 * One pass over the lexer's segments: literals, quoted names and comments are copied as they are; in code
 * {@code :name} becomes {@code ?} (a name may occur more than once), {@code ::} and {@code :=} stay; PostgreSQL's
 * {@code ?} operator becomes {@code ??}, which pgjdbc reads as a literal {@code ?}; a {@code ?} in a MySQL template and
 * a JDBC escape {@code {…}} are errors: the driver would change the text, and what runs must be what was saved.
 *
 * <p>Verified on PostgreSQL 16: {@code (:since::timestamptz IS NULL OR created_at >= :since) … meta ? 'vip' LIMIT :lim}
 * becomes {@code (?::timestamptz IS NULL OR created_at >= ?) … meta ?? 'vip' LIMIT ?} with the order
 * {@code [since, since, lim]}.
 */
record NamedSql(String jdbc, List<String> order, List<String> errors, Set<String> used) {

  static NamedSql compile(String sql, boolean mysql, Set<String> declared) {
    List<String> errors = new ArrayList<>();
    List<String> order = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
    SqlLexer.Scan scan = SqlLexer.scan(sql, mysql);
    if (scan.unterminated()) {
      errors.add("A quote or comment in the SQL isn't closed.");
      return new NamedSql(sql, List.of(), List.copyOf(errors), Set.of());
    }
    StringBuilder out = new StringBuilder(sql.length() + 8);
    for (SqlLexer.Segment s : scan.segments()) {
      if (s.kind() != SqlLexer.Kind.CODE) {
        out.append(sql, s.start(), s.end());
        continue;
      }
      int i = s.start();
      while (i < s.end()) {
        char c = sql.charAt(i);
        char next = i + 1 < s.end() ? sql.charAt(i + 1) : '\0';
        if (c == ':' && (next == ':' || next == '=')) {
          out.append(c).append(next);
          i += 2;
        } else if (c == ':' && (Character.isLetter(next) || next == '_')
            && (i == s.start() || !SqlLexer.identifierPart(sql.charAt(i - 1)))) {
          int j = i + 1;
          while (j < s.end() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
          }
          String name = sql.substring(i + 1, j);
          if (!declared.contains(name)) {
            errors.add(":" + name + " isn't a parameter. Add it under Parameters, or write :: for a cast.");
          }
          order.add(name);
          used.add(name);
          out.append('?');
          i = j;
        } else if (c == '?') {
          if (mysql) {
            errors.add("Write :name for a parameter: a bare ? isn't allowed in a MySQL template.");
            out.append('?');
          } else {
            out.append("??");
          }
          i++;
        } else if (c == '{') {
          errors.add("JDBC escapes such as {fn …} aren't allowed: the driver would rewrite the SQL.");
          out.append(c);
          i++;
        } else {
          out.append(c);
          i++;
        }
      }
    }
    return new NamedSql(out.toString(), List.copyOf(order), List.copyOf(errors), Set.copyOf(used));
  }
}
