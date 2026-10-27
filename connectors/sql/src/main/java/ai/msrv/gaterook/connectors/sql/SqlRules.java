package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Analysis;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Kind;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.StatementFacts;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The routes of report 04 §3.5 for each tool: what the classifier found, against the merged {@code sql} policy section
 * of the call and the connection's own lists. Rule ids are {@code <env>.sql.<rule>} ({@code prod.sql.block_ddl}).
 *
 * <ul>
 *   <li>{@code sql_query}: one READ statement, built-in functions only (another function is a request on dev and
 *       stage and refused on prod), tables on the allowlist. A write is refused: it belongs to sql_execute.</li>
 *   <li>{@code sql_execute}: DML or DDL (PostgreSQL up to {@value #MAX_POSTGRES_STATEMENTS} statements in one
 *       transaction, MySQL one), always a request; a read is refused (it needs no approval); DDL is refused where the
 *       policy blocks it; a statement the gateway can't analyze is a request with a warning, refused where DDL is
 *       blocked or an allowlist restricts the tables (neither can be checked).</li>
 *   <li>A template's statement: like sql_query for a read, like sql_execute for a change.</li>
 * </ul>
 * Transaction and session control, denied functions and tables off the allowlist are refused everywhere.
 */
final class SqlRules {

  static final int MAX_POSTGRES_STATEMENTS = 20;

  /** The policy of one call. */
  record SqlPolicy(PolicyView view, boolean mysql, boolean denyDdl, Allowlist allowlist, List<String> deniedFunctions,
      List<String> allowedFunctions, Set<String> builtIns, String defaultSchema) {

    static SqlPolicy of(PolicyView view, ConnectionConfig cfg, boolean mysql) {
      ConnectionSettings s = ConnectionSettings.of(cfg, mysql);
      Env env = view.env() == null ? cfg.env() : view.env();
      return new SqlPolicy(view, mysql, view.flag("denyDdl", env != Env.DEV),
          Allowlist.of(view.strings("allowlist"), s.schemaAllowlist(), mysql), view.strings("deniedFunctions"),
          s.allowedFunctions(), FunctionRules.fromMetadata(cfg.metadata()), s.defaultSchema());
    }

    boolean prod() {
      return view.env() == Env.PROD;
    }

    String rule(String id) {
      return view.rule(id);
    }

    String env() {
      return view.env() == null ? "this environment" : view.env().id();
    }
  }

  private SqlRules() {}

  // ── sql_query ───────────────────────────────────────────────────────────────────────────────────────────────

  static Classification query(Analysis a, SqlPolicy p) {
    Classification common = common(a, p, "sql_query");
    if (common != null) {
      return common;
    }
    if (a.count() > 1) {
      return Classification.deny(p.rule("multi-statement"), "sql_query runs one statement at a time; this has "
          + a.count() + ".", "Send them one by one.");
    }
    if (a.kind() == Kind.UNPARSED) {
      return Classification.deny(p.rule("unparsed"), detail(a) + " The gateway couldn't analyze it, so it doesn't run "
          + "it as a read.", "Rewrite it in plain SQL.");
    }
    if (a.kind() != Kind.READ) {
      return Classification.deny(p.rule("query-writes"), "sql_query only reads; this is "
          + a.statements().getFirst().type() + ".", "Use sql_execute: a person approves the exact statement.");
    }
    Classification tables = tables(a, p);
    if (tables != null) {
      return tables;
    }
    return functions(a, p, "sql_query");
  }

  // ── sql_execute ─────────────────────────────────────────────────────────────────────────────────────────────

  static Classification execute(Analysis a, SqlPolicy p) {
    Classification common = common(a, p, "sql_execute");
    if (common != null) {
      return common;
    }
    if (p.mysql() && a.count() > 1) {
      return Classification.deny(p.rule("multi-statement"), "MySQL runs one statement per sql_execute; this has "
          + a.count() + ".", "Send them one by one, each waits for approval.");
    }
    if (a.count() > MAX_POSTGRES_STATEMENTS) {
      return Classification.deny(p.rule("too-many-statements"), "sql_execute runs at most " + MAX_POSTGRES_STATEMENTS
          + " statements in one transaction; this has " + a.count() + ".", "Split the change.");
    }
    if (a.kind() == Kind.UNPARSED) {
      if (p.denyDdl() || p.allowlist().restricted()) {
        return Classification.deny(p.rule("unparsed"), detail(a) + " The gateway couldn't analyze it, and on "
            + p.env() + " it checks " + (p.denyDdl() ? "that no statement changes the schema" : "which tables a "
            + "statement uses") + ".", "Rewrite it in plain SQL.");
      }
      return Classification.mutation("Not analyzed by the gateway: " + detail(a), facts(a));
    }
    if (a.statements().stream().allMatch(s -> s.kind() == Kind.READ)) {
      return Classification.deny(p.rule("execute-read"), "This is a read.", "Use sql_query; it doesn't need "
          + "approval.");
    }
    if (p.denyDdl() && a.kind() == Kind.DDL) {
      return blockDdl(a, p);
    }
    Classification tables = tables(a, p);
    if (tables != null) {
      return tables;
    }
    return Classification.mutation(changeReason(a), facts(a));
  }

  // ── Templates ───────────────────────────────────────────────────────────────────────────────────────────────

  static Classification template(Analysis a, SqlPolicy p, String tool) {
    Classification common = common(a, p, tool);
    if (common != null) {
      return common;
    }
    if (a.kind() == Kind.UNPARSED || a.count() != 1) {
      // Compiling refuses both; a stored template that no longer parses doesn't run.
      return Classification.deny(p.rule("unparsed"), "The statement of " + tool + " can't be analyzed.", null);
    }
    if (p.denyDdl() && a.kind() == Kind.DDL) {
      return blockDdl(a, p);
    }
    Classification tables = tables(a, p);
    if (tables != null) {
      return tables;
    }
    if (a.kind() == Kind.READ) {
      return functions(a, p, tool);
    }
    return Classification.mutation(tool + " runs " + a.statements().getFirst().type() + ", which changes data",
        facts(a));
  }

  /** {@code sql_describe_table}: the table must be on the allowlist. */
  static Classification describe(TableRef table, SqlPolicy p) {
    if (table != null && !p.allowlist().allows(table)) {
      return notAllowed(IdentifierRules.display(table, p.mysql()), p);
    }
    return Classification.read();
  }

  // ── Shared rules ────────────────────────────────────────────────────────────────────────────────────────────

  /** Too complex, transaction and session control, denied functions: refused for every tool. */
  private static Classification common(Analysis a, SqlPolicy p, String tool) {
    if (a.tooComplex()) {
      return Classification.deny(p.rule("too-complex"), a.problem(), "Simplify the statement.");
    }
    for (StatementFacts s : a.statements()) {
      if (s.kind() == Kind.FORBIDDEN) {
        String reason = switch (s.type()) {
          case "SELECT" -> "SELECT … INTO OUTFILE isn't allowed: it writes a file on the database server.";
          case SqlClassifier.VARIABLE_ASSIGNMENT -> "Assigning a user variable (@v := …, INTO @v) isn't allowed: it "
              + "would outlive the call in a shared session.";
          case SqlClassifier.TEMPORARY_OBJECT -> "A temporary table, view or sequence isn't allowed: it would outlive "
              + "the call in a shared session and hide the table of that name from later calls.";
          default -> s.type() + " isn't allowed: MCP Gateway runs every call in its own transaction and session.";
        };
        return Classification.deny(p.rule("forbidden"), reason, forbiddenHint(s));
      }
    }
    FunctionRules.Verdict v = FunctionRules.check(a.functions(), p.mysql(), p.builtIns(), p.deniedFunctions(),
        p.allowedFunctions());
    if (!v.denied().isEmpty()) {
      return Classification.deny(p.rule("function-denied"), String.join(", ", v.denied()) + " isn't allowed through "
          + "MCP Gateway: it acts outside the transaction.", null);
    }
    return null;
  }

  private static String forbiddenHint(StatementFacts s) {
    return switch (s.type()) {
      case "SELECT" -> "Read the rows with sql_query instead.";
      case SqlClassifier.VARIABLE_ASSIGNMENT -> "Compute the value in the query itself.";
      case SqlClassifier.TEMPORARY_OBJECT -> "Use a CTE (WITH …) in the query, or a regular table.";
      case "BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "END", "ABORT" ->
          "Send the statements without transaction control: sql_execute runs them in one transaction.";
      default -> null;
    };
  }

  /** Every table of every statement on the allowlist; a statement whose tables can't be named fails a restriction. */
  private static Classification tables(Analysis a, SqlPolicy p) {
    if (!p.allowlist().restricted()) {
      return null;
    }
    for (StatementFacts s : a.statements()) {
      if (s.tables() == null) {
        return Classification.deny(p.rule("table-not-allowed"), "The gateway can't tell which tables " + s.type()
            + " uses, and on " + p.env() + " only some tables are open to agents.", null);
      }
      for (String name : s.tables()) {
        if (p.mysql() && "dual".equalsIgnoreCase(name)) {
          // MySQL's FROM DUAL names no table.
          continue;
        }
        TableRef t = IdentifierRules.table(name, p.mysql(), p.defaultSchema());
        if (t == null || !p.allowlist().allows(t)) {
          return notAllowed(t == null ? name : IdentifierRules.display(t, p.mysql()), p);
        }
      }
    }
    return null;
  }

  private static Classification notAllowed(String table, SqlPolicy p) {
    return Classification.deny(p.rule("table-not-allowed"), table + " isn't one of the tables agents may use on "
        + p.env() + ".", "Call sql_list_schemas to see the tables you can use.");
  }

  /** Reads call built-in functions; another function may change data, so a person decides (refused on prod). */
  private static Classification functions(Analysis a, SqlPolicy p, String tool) {
    FunctionRules.Verdict v = FunctionRules.check(a.functions(), p.mysql(), p.builtIns(), p.deniedFunctions(),
        p.allowedFunctions());
    if (v.notBuiltIn().isEmpty()) {
      return Classification.read();
    }
    String names = String.join(", ", v.notBuiltIn());
    if (p.prod()) {
      return Classification.deny(p.rule("function-not-built-in"), names + (v.notBuiltIn().size() == 1 ? " isn't a "
          + "built-in function" : " aren't built-in functions") + ", and on prod " + tool + " runs only built-in "
          + "ones.", "If it only reads, a person can add it to the connection's allowed functions.");
    }
    ObjectNode facts = facts(a);
    ArrayNode fns = facts.putArray("functions");
    v.notBuiltIn().forEach(fns::add);
    return Classification.mutation("Calls " + names + ", which " + (v.notBuiltIn().size() == 1 ? "isn't a built-in "
        + "function" : "aren't built-in functions") + ": it may change data, so a person approves it", facts);
  }

  private static Classification blockDdl(Analysis a, SqlPolicy p) {
    String type = a.statements().stream().filter(s -> s.kind() == Kind.DDL).map(StatementFacts::type).findFirst()
        .orElse("DDL");
    return Classification.deny(p.rule("block_ddl"), "DDL is not allowed on " + p.env() + " (" + type + ").",
        "Ask a person to make schema changes outside MCP Gateway.");
  }

  private static String changeReason(Analysis a) {
    if (a.count() == 1) {
      return a.statements().getFirst().type() + " changes data";
    }
    Set<String> types = new LinkedHashSet<>();
    a.statements().stream().filter(s -> s.kind() != Kind.READ).forEach(s -> types.add(s.type()));
    return a.count() + " statements change data (" + String.join(", ", types) + ")";
  }

  private static String detail(Analysis a) {
    return a.problem() == null ? "The gateway couldn't analyze this statement." : a.problem();
  }

  /** Non-secret facts for the audit: statement types and the tables the parser named. */
  static ObjectNode facts(Analysis a) {
    ObjectNode facts = JsonNodeFactory.instance.objectNode();
    facts.put("kind", a.kind().name().toLowerCase(java.util.Locale.ROOT));
    ArrayNode statements = facts.putArray("statements");
    for (StatementFacts s : a.statements()) {
      ObjectNode o = statements.addObject().put("type", s.type());
      if (s.tables() != null) {
        ArrayNode t = o.putArray("tables");
        s.tables().forEach(t::add);
      }
    }
    return facts;
  }

  /** The tables a change targets, resolved, for "Target table" in the request. */
  static List<String> targets(Analysis a, boolean mysql, String defaultSchema) {
    List<String> out = new ArrayList<>();
    for (StatementFacts s : a.statements()) {
      if (s.kind() == Kind.READ || s.targets() == null) {
        continue;
      }
      for (String name : s.targets()) {
        TableRef t = IdentifierRules.table(name, mysql, defaultSchema);
        String shown = t == null ? name : t.name().equals(name) ? name : IdentifierRules.display(t, mysql);
        if (!out.contains(shown)) {
          out.add(shown);
        }
      }
    }
    return out;
  }
}
