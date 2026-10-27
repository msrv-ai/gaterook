package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.FunctionRules.FunctionRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ResetStatement;
import net.sf.jsqlparser.statement.RollbackStatement;
import net.sf.jsqlparser.statement.SavepointStatement;
import net.sf.jsqlparser.statement.SetStatement;
import net.sf.jsqlparser.statement.ShowColumnsStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.UseStatement;
import net.sf.jsqlparser.statement.alter.AlterSession;
import net.sf.jsqlparser.statement.alter.AlterSystemStatement;
import net.sf.jsqlparser.statement.create.schema.CreateSchema;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.TableStatement;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.show.ShowTablesStatement;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * What a statement does, without I/O (report 04 §3.5, §7.2): JSqlParser 5.4 picks the route, the database is the
 * guarantee. Each statement gets a class — READ &lt; DML &lt; DDL &lt; FORBIDDEN, or UNPARSED — and the call gets the
 * highest. What the parser can't read, or reads differently from the server's lexer, is UNPARSED: fail closed.
 */
final class SqlClassifier {

  /** The class of one statement, in increasing order of what it may do. */
  enum Kind { READ, DML, DDL, FORBIDDEN, UNPARSED }

  /**
   * One statement.
   *
   * @param type      {@code SELECT}, {@code UPDATE}, {@code CREATE TABLE}… for the approver and the result
   * @param tables    the tables it touches as the parser names them (CTE names left out); {@code null} when the
   *                  parser can't say ({@code CALL}, {@code SHOW TABLES})
   * @param targets   for DML and DDL, the tables it changes (a subset of {@code tables}, or {@code null})
   * @param functions every function call, wherever it is
   */
  record StatementFacts(Kind kind, String type, List<String> tables, List<String> targets,
      List<FunctionRef> functions) {}

  /**
   * A whole call.
   *
   * @param problem why it is UNPARSED or too complex, in words for the agent; {@code null} otherwise
   */
  record Analysis(Kind kind, List<StatementFacts> statements, String problem, boolean tooComplex) {

    int count() {
      return statements.size();
    }

    boolean parsed() {
      return kind != Kind.UNPARSED && !tooComplex;
    }

    List<FunctionRef> functions() {
      List<FunctionRef> out = new ArrayList<>();
      statements.forEach(s -> out.addAll(s.functions()));
      return out;
    }
  }

  /** The type of a statement that assigns a user variable ({@code SELECT @v := …}). */
  static final String VARIABLE_ASSIGNMENT = "user variable assignment";
  /** The type of a statement that creates a temporary table, view or sequence. */
  static final String TEMPORARY_OBJECT = "temporary object";

  static final int MAX_LENGTH = 100_000;
  static final int MAX_NESTING = 64;
  private static final long PARSE_TIMEOUT_MS = 2_000;

  /** Words that start a statement the parser doesn't know and that the gateway never runs: it owns sessions. */
  static final Set<String> FORBIDDEN_FIRST_WORDS = Set.of("BEGIN", "START", "RELEASE", "COPY", "LISTEN", "UNLISTEN",
      "NOTIFY", "PREPARE", "DEALLOCATE", "VACUUM", "LOAD", "HANDLER", "KILL", "COMMIT", "ROLLBACK", "SAVEPOINT", "END",
      "ABORT", "SET", "RESET", "DISCARD", "USE", "SHUTDOWN", "FLUSH", "XA", "INSTALL", "UNINSTALL", "LOCK", "UNLOCK",
      "CHECKPOINT", "RESTART", "CLONE", "BINLOG", "PURGE", "CHANGE", "DECLARE");

  /**
   * {@code CREATE [GLOBAL | LOCAL | OR REPLACE | UNLOGGED] TEMP[ORARY] …}: a session's own object, which would stay in
   * the pooled session after the call and, as a temporary table, hide the real one of that name from later calls.
   */
  private static final Pattern CREATE_TEMPORARY = Pattern.compile(
      "(?i)^\\s*\\(*\\s*CREATE(?:\\s+(?:GLOBAL|LOCAL|OR|REPLACE|UNLOGGED))*\\s+TEMP(?:ORARY)?\\b");
  /** PostgreSQL {@code SELECT … INTO [TEMP | TEMPORARY] [TABLE] t}: a temporary table, as above. */
  private static final Pattern SELECT_INTO_TEMPORARY = Pattern.compile(
      "(?i)(?<!\\b(?:INSERT|MERGE)\\s{1,64})\\bINTO\\s+TEMP(?:ORARY)?\\b");
  /** MySQL {@code @v := …} or {@code … INTO @v}: a user variable, which outlives the call in the pooled session. */
  private static final Pattern MYSQL_ASSIGNMENT = Pattern.compile("(?i):=|\\bINTO\\s+@");

  /**
   * The parser waits on its own task to stop at a deadline; without an executor every parse would start a platform
   * thread. A virtual thread per parse costs nothing.
   */
  private static final ExecutorService PARSER = Executors.newThreadPerTaskExecutor(
      Thread.ofVirtual().name("sql-parse-", 0).factory());

  private SqlClassifier() {}

  static Analysis analyze(String sql, boolean mysql) {
    if (sql == null || sql.isBlank()) {
      return unparsed(List.of(), "The statement is empty.");
    }
    if (sql.length() > MAX_LENGTH) {
      return tooComplex("The statement is longer than " + MAX_LENGTH + " characters.");
    }
    if (CCJSqlParserUtil.getNestingDepth(sql) > MAX_NESTING) {
      return tooComplex("The statement nests deeper than " + MAX_NESTING + " levels.");
    }
    SqlLexer.Scan scan = SqlLexer.scan(sql, mysql);
    if (scan.unterminated()) {
      return unparsed(List.of(), "A quote or comment isn't closed.");
    }
    if (scan.executableComment()) {
      return unparsed(List.of(), "It contains a comment the server acts on (/*! … */ or a /*+ … */ hint): the "
          + "server runs or applies what the gateway reads as a comment.");
    }
    List<String> pieces = scan.statements();
    Statements statements;
    try {
      statements = CCJSqlParserUtil.parseStatements(sql, PARSER,
          p -> p.withTimeOut(PARSE_TIMEOUT_MS).withBackslashEscapeCharacter(mysql));
    } catch (JSQLParserException | RuntimeException e) {
      return byFirstWords(pieces, mysql);
    }
    if (statements == null || statements.isEmpty()) {
      return unparsed(List.of(), "The gateway found no statement in it.");
    }
    if (statements.size() != pieces.size()) {
      // The parser and the server's lexer disagree about where statements end: trust neither.
      return unparsed(List.of(), "The gateway reads " + statements.size() + " statement(s) where the database would "
          + "read " + pieces.size() + ".");
    }
    List<StatementFacts> facts = new ArrayList<>();
    for (int i = 0; i < statements.size(); i++) {
      StatementFacts f = facts(statements.get(i), mysql);
      String session = sessionState(pieces.get(i), mysql);
      facts.add(session == null ? f : new StatementFacts(Kind.FORBIDDEN, session, f.tables(), f.targets(),
          f.functions()));
    }
    Kind kind = Kind.READ;
    for (StatementFacts f : facts) {
      if (f.kind().ordinal() > kind.ordinal()) {
        kind = f.kind();
      }
    }
    String problem = kind == Kind.UNPARSED ? "The gateway couldn't analyze " + facts.stream()
        .filter(f -> f.kind() == Kind.UNPARSED).findFirst().map(StatementFacts::type).orElse("it") + "." : null;
    return new Analysis(kind, List.copyOf(facts), problem, false);
  }

  /** The parser gave up: a known session or transaction command is FORBIDDEN, anything else UNPARSED. */
  private static Analysis byFirstWords(List<String> pieces, boolean mysql) {
    List<StatementFacts> facts = new ArrayList<>();
    boolean forbidden = false;
    for (String piece : pieces) {
      String word = SqlLexer.scan(piece, mysql).firstKeyword();
      String session = sessionState(piece, mysql);
      boolean f = session != null || FORBIDDEN_FIRST_WORDS.contains(word);
      forbidden |= f;
      String type = session != null ? session : word.isEmpty() ? "statement" : word;
      facts.add(new StatementFacts(f ? Kind.FORBIDDEN : Kind.UNPARSED, type, null, null, List.of()));
    }
    if (forbidden) {
      return new Analysis(Kind.FORBIDDEN, List.copyOf(facts), null, false);
    }
    return new Analysis(Kind.UNPARSED, List.copyOf(facts), "The gateway couldn't analyze this statement.", false);
  }

  /**
   * What a statement would leave in the pooled session after its call, read from its code (literals and comments
   * left out), whether or not the parser reads it: {@link #TEMPORARY_OBJECT}, {@link #VARIABLE_ASSIGNMENT} (MySQL),
   * or {@code null}. Transactions and sessions belong to the gateway, so these are never run.
   */
  static String sessionState(String piece, boolean mysql) {
    SqlLexer.Scan scan = SqlLexer.scan(piece, mysql);
    String code = scan.code();
    if (CREATE_TEMPORARY.matcher(code).find()) {
      return TEMPORARY_OBJECT;
    }
    String first = scan.firstKeyword();
    if (!mysql && (first.equals("SELECT") || first.equals("WITH")) && SELECT_INTO_TEMPORARY.matcher(code).find()) {
      return TEMPORARY_OBJECT;
    }
    if (mysql && MYSQL_ASSIGNMENT.matcher(code).find()) {
      return VARIABLE_ASSIGNMENT;
    }
    return null;
  }

  private static Analysis unparsed(List<StatementFacts> facts, String problem) {
    return new Analysis(Kind.UNPARSED, facts, problem, false);
  }

  private static Analysis tooComplex(String problem) {
    return new Analysis(Kind.UNPARSED, List.of(), problem, true);
  }

  // ── One statement ───────────────────────────────────────────────────────────────────────────────────────────

  private static StatementFacts facts(Statement st, boolean mysql) {
    Collector c = new Collector();
    List<String> tables;
    try {
      tables = List.copyOf(c.getTables(st));
    } catch (UnsupportedOperationException e) {
      // CALL, SHOW TABLES, SET…: the parser can't list what they touch.
      tables = null;
    }
    Kind kind = kindOf(st, c, mysql);
    List<String> targets = targets(st, kind, tables);
    String type = c.assignsVariable ? VARIABLE_ASSIGNMENT : type(st);
    return new StatementFacts(kind, type, tables, targets, List.copyOf(c.functions));
  }

  static Kind kindOf(Statement st, Collector c, boolean mysql) {
    return switch (st) {
      case Values v -> Kind.READ;
      case TableStatement t -> Kind.READ;
      case Select s -> {
        if (c.mysqlInto || c.assignsVariable) {
          // INTO OUTFILE / DUMPFILE writes a file on the server; @v := … outlives the call in the pooled session.
          yield Kind.FORBIDDEN;
        }
        if (c.into) {
          yield Kind.DDL;                 // SELECT … INTO creates a table
        }
        boolean locks = c.lock || s.getForMode() != null || s.getForUpdate() != null;
        yield c.dml > 0 || locks ? Kind.DML : Kind.READ;   // a CTE with DELETE, FOR UPDATE / FOR SHARE
      }
      case ExplainStatement e -> e.getOption(ExplainStatement.OptionType.ANALYZE) == null || e.getStatement() == null
          ? Kind.READ : analyzedKind(e.getStatement(), mysql);
      case ShowStatement s -> Kind.READ;
      case ShowTablesStatement s -> Kind.READ;
      case ShowColumnsStatement s -> Kind.READ;
      case DescribeStatement d -> Kind.READ;
      case Execute x when x.getExecType() == Execute.ExecType.EXECUTE -> Kind.FORBIDDEN;   // PG EXECUTE of a prepared
      case Insert i -> Kind.DML;
      case Update u -> Kind.DML;
      case Delete d -> Kind.DML;
      case Upsert u -> Kind.DML;
      case Merge m -> Kind.DML;
      case Execute x -> Kind.DML;                                                         // CALL p()
      case Commit x -> Kind.FORBIDDEN;
      case RollbackStatement x -> Kind.FORBIDDEN;
      case SavepointStatement x -> Kind.FORBIDDEN;
      case SetStatement x -> Kind.FORBIDDEN;
      case ResetStatement x -> Kind.FORBIDDEN;
      case UseStatement x -> Kind.FORBIDDEN;
      case AlterSystemStatement x -> Kind.FORBIDDEN;
      case AlterSession x -> Kind.FORBIDDEN;
      case net.sf.jsqlparser.statement.create.database.CreateDatabase x -> Kind.FORBIDDEN;
      case Drop d when "DATABASE".equalsIgnoreCase(d.getType()) -> Kind.FORBIDDEN;
      case CreateSchema x -> Kind.DDL;
      case UnsupportedStatement u -> Kind.UNPARSED;   // SHOW CREATE TABLE, SHOW GRANTS…: parsed without an error
      case net.sf.jsqlparser.statement.Block b -> Kind.UNPARSED;
      default -> Kind.DDL;                            // CREATE, ALTER, DROP, TRUNCATE, GRANT, COMMENT, REFRESH…
    };
  }

  /** {@code EXPLAIN ANALYZE} runs its statement: it is what the statement is. */
  private static Kind analyzedKind(Statement inner, boolean mysql) {
    Collector c = new Collector();
    try {
      c.getTables(inner);
    } catch (UnsupportedOperationException e) {
      // Only the kind matters here.
    }
    return kindOf(inner, c, mysql);
  }

  /** The tables a DML or DDL statement changes: its own target, or every table for statements the parser can't split. */
  private static List<String> targets(Statement st, Kind kind, List<String> tables) {
    if (kind == Kind.READ || tables == null) {
      return kind == Kind.READ ? List.of() : null;
    }
    String target = switch (st) {
      case Update u -> u.getTable() == null ? null : u.getTable().getFullyQualifiedName();
      case Delete d -> d.getTable() == null ? null : d.getTable().getFullyQualifiedName();
      case Insert i -> i.getTable() == null ? null : i.getTable().getFullyQualifiedName();
      case Upsert u -> u.getTable() == null ? null : u.getTable().getFullyQualifiedName();
      case Merge m -> m.getTable() == null ? null : m.getTable().getFullyQualifiedName();
      default -> null;
    };
    return target == null ? tables : List.of(target);
  }

  private static String type(Statement st) {
    return switch (st) {
      case Values v -> "VALUES";
      case TableStatement t -> "TABLE";
      case Select s -> "SELECT";
      case ExplainStatement e -> e.getOption(ExplainStatement.OptionType.ANALYZE) == null ? "EXPLAIN" : "EXPLAIN ANALYZE";
      case ShowStatement s -> "SHOW";
      case ShowTablesStatement s -> "SHOW TABLES";
      case ShowColumnsStatement s -> "SHOW COLUMNS";
      case DescribeStatement d -> "DESCRIBE";
      case Insert i -> "INSERT";
      case Update u -> "UPDATE";
      case Delete d -> "DELETE";
      case Upsert u -> "REPLACE";
      case Merge m -> "MERGE";
      case Execute x -> x.getExecType() == null ? "CALL" : x.getExecType().name();
      case Drop d -> "DROP " + (d.getType() == null ? "" : d.getType().toUpperCase(Locale.ROOT)).strip();
      case UnsupportedStatement u -> "this statement";
      default -> words(st.getClass().getSimpleName());
    };
  }

  /** {@code CreateTable} → {@code CREATE TABLE}, {@code Truncate} → {@code TRUNCATE}. */
  private static String words(String simpleName) {
    String name = simpleName.endsWith("Statement") ? simpleName.substring(0, simpleName.length() - 9) : simpleName;
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char ch = name.charAt(i);
      if (i > 0 && Character.isUpperCase(ch)) {
        out.append(' ');
      }
      out.append(Character.toUpperCase(ch));
    }
    return out.toString();
  }

  /**
   * One walk over a statement: tables through {@link TablesNamesFinder#getTables} (which leaves out the names of CTEs),
   * function calls wherever they are, and the flags that raise a SELECT: a lock clause or {@code INTO} on any level, a
   * data-changing statement inside it (a CTE with {@code DELETE … RETURNING}).
   */
  static final class Collector extends TablesNamesFinder<Void> {
    final Set<FunctionRef> functions = new LinkedHashSet<>();
    boolean lock;
    boolean into;
    boolean mysqlInto;
    boolean assignsVariable;
    int dml;

    @Override
    public <S> Void visit(VariableAssignment assignment, S context) {
      assignsVariable = true;
      return super.visit(assignment, context);
    }

    @Override
    public <S> Void visit(Function f, S context) {
      if (f.getMultipartName() != null && !f.getMultipartName().isEmpty()) {
        functions.add(new FunctionRef(List.copyOf(f.getMultipartName())));
      }
      return super.visit(f, context);
    }

    @Override
    public <S> Void visit(AnalyticExpression a, S context) {
      if (a.getName() != null) {
        functions.add(new FunctionRef(List.of(a.getName())));
      }
      return super.visit(a, context);
    }

    @Override
    public <S> Void visit(TableFunction tf, S context) {
      if (tf.getFunction() != null && tf.getFunction().getMultipartName() != null) {
        functions.add(new FunctionRef(List.copyOf(tf.getFunction().getMultipartName())));
      }
      return super.visit(tf, context);
    }

    @Override
    public <S> Void visit(PlainSelect ps, S context) {
      lock |= ps.getForMode() != null || ps.getForUpdate() != null;
      into |= ps.getIntoTables() != null && !ps.getIntoTables().isEmpty();
      mysqlInto |= ps.getMySqlSelectIntoClause() != null;
      return super.visit(ps, context);
    }

    @Override
    public <S> Void visit(Delete delete, S context) {
      dml++;
      return super.visit(delete, context);
    }

    @Override
    public <S> Void visit(Update update, S context) {
      dml++;
      return super.visit(update, context);
    }

    @Override
    public <S> Void visit(Insert insert, S context) {
      dml++;
      return super.visit(insert, context);
    }
  }
}
