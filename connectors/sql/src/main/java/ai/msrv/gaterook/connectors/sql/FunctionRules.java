package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Which functions a read may call (report 04 §6.2, ARCHITECTURE.md §9 "SQL reads cannot write").
 *
 * <p>For PostgreSQL and MySQL, a READ ONLY transaction stops writes a function makes inside it, but not effects outside
 * it: advisory locks, signals to other sessions, files of the server, a new connection through {@code dblink}. So:
 * <ul>
 *   <li>the <b>denylist</b> is refused wherever it appears, by name (with or without a schema, any case), plus the
 *       policy's {@code deniedFunctions};</li>
 *   <li>a read calls only <b>built-in</b> functions: PostgreSQL's are the names in {@code pg_catalog} that Test
 *       connection stores in the connection's metadata (a curated list stands in until the first test), MySQL's a
 *       fixed list; a schema-qualified name counts as built in only with {@code pg_catalog}; a quoted name only spelled
 *       exactly as the catalog spells it. The connection's {@code allowedFunctions} adds the person's own pure
 *       functions. Any other function makes the call one that may write: an approval on dev and stage, refused on
 *       prod.</li>
 * </ul>
 * Oracle accepts only unquoted SQL built-ins: stored routines may use autonomous transactions whose commits survive
 * the caller's rollback. This static check does not resolve view definitions or parameterless stored functions;
 * database privileges remain the boundary for those indirect calls.
 */
final class FunctionRules {

  /** A function call as the parser saw it: the name's parts as written, the last one the function. */
  record FunctionRef(List<String> parts) {

    /** {@code pg_catalog.now}, {@code "NOW"}, {@code myschema.f}. */
    String written() {
      return String.join(".", parts);
    }
  }

  /** What the rules say about the functions of a statement. */
  record Verdict(List<String> denied, List<String> notBuiltIn) {

    boolean clean() {
      return denied.isEmpty() && notBuiltIn.isEmpty();
    }
  }

  static final List<String> POSTGRES_DENIED = List.of(
      "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf", "pg_rotate_logfile", "pg_promote",
      "pg_switch_wal", "pg_create_restore_point", "pg_backup_start", "pg_backup_stop", "pg_start_backup",
      "pg_stop_backup", "pg_*replication_slot*", "pg_replication_origin_*", "pg_logical_*", "pg_advisory_*",
      "pg_try_advisory_*", "set_config", "pg_notify", "pg_read_file", "pg_read_binary_file", "pg_ls_*",
      "pg_stat_file", "pg_stat_reset*", "pg_file_*", "lo_*", "dblink*", "query_to_xml*", "cursor_to_xml*",
      "table_to_xml*", "schema_to_xml*", "database_to_xml*", "pg_log_backend_memory_contexts", "pg_wal_replay_*",
      "pg_import_system_collations",
      // Index maintenance writes the index in a READ ONLY transaction too, and ROLLBACK doesn't undo it (PG 16).
      "brin_summarize_*", "brin_desummarize_range", "gin_clean_pending_list");

  static final List<String> MYSQL_DENIED = List.of(
      "get_lock", "release_lock", "release_all_locks", "load_file", "benchmark", "sys_exec", "sys_eval",
      "master_pos_wait", "source_pos_wait", "wait_for_executed_gtid_set", "wait_until_sql_thread_after_gtids",
      "master_gtid_wait");

  static final List<String> SQLSERVER_DENIED = List.of(
      "openrowset", "openquery", "opendatasource", "openxml", "xp_*", "sp_*", "fn_get_audit_file",
      "fn_trace_gettable", "fn_xe_file_target_read_file");

  /** A conservative set of SQL Server functions that do not change database or session state. */
  static final Set<String> SQLSERVER_BUILT_IN = names(
      "avg", "checksum_agg", "count", "count_big", "grouping", "grouping_id", "max", "min", "stdev", "stdevp",
      "sum", "var", "varp", "string_agg", "row_number", "rank", "dense_rank", "ntile", "cume_dist",
      "first_value", "last_value", "lag", "lead", "percent_rank", "percentile_cont", "percentile_disc",
      "ascii", "char", "charindex", "concat", "concat_ws", "datalength", "difference", "format", "left", "len",
      "lower", "ltrim", "nchar", "patindex", "quotename", "replace", "replicate", "reverse", "right", "rtrim",
      "soundex", "space", "str", "string_escape", "string_split", "stuff", "substring", "translate", "trim",
      "unicode", "upper", "abs", "acos", "asin", "atan", "atn2", "ceiling", "cos", "cot", "degrees", "exp",
      "floor", "log", "log10", "pi", "power", "radians", "round", "sign", "sin", "sqrt", "square", "tan",
      "cast", "convert", "try_cast", "try_convert", "parse", "try_parse", "choose", "coalesce", "iif",
      "isnull", "nullif", "greatest", "least", "isnumeric", "isdate", "dateadd", "datediff", "datediff_big",
      "datefromparts", "datename", "datepart", "datetrunc", "datetime2fromparts", "datetimefromparts",
      "datetimeoffsetfromparts", "day", "eomonth", "getdate", "getutcdate", "month", "smalldatetimefromparts",
      "switchoffset", "sysdatetime", "sysdatetimeoffset", "sysutcdatetime", "timefromparts", "todatetimeoffset",
      "year", "isjson", "json_value", "json_query", "json_modify", "json_path_exists", "json_object", "json_array",
      "openjson", "binary_checksum", "checksum", "hashbytes", "newid", "db_id", "db_name", "schema_id",
      "schema_name", "object_id", "object_name", "object_schema_name", "col_length", "col_name", "type_id",
      "type_name", "user_id", "user_name", "suser_id", "suser_sname", "original_login", "session_context",
      "serverproperty", "databasepropertyex", "objectproperty", "objectpropertyex", "has_perms_by_name",
      "is_member", "is_rolemember", "is_srvrolemember", "host_name", "app_name", "scope_identity",
      "ident_current", "ident_incr", "ident_seed");

  /**
   * PostgreSQL built-ins known without a test: aggregates, window, string, math, date and time, JSON, arrays and the
   * catalog helpers people write in reads. Test connection replaces it with the server's own {@code pg_catalog} list.
   */
  static final Set<String> POSTGRES_BUILT_IN = names(
      // aggregates and window
      "avg", "count", "sum", "min", "max", "string_agg", "array_agg", "bool_and", "bool_or", "every", "json_agg",
      "jsonb_agg", "json_object_agg", "jsonb_object_agg", "bit_and", "bit_or", "bit_xor", "stddev", "stddev_pop",
      "stddev_samp", "variance", "var_pop", "var_samp", "percentile_cont", "percentile_disc", "mode", "corr",
      "covar_pop", "covar_samp", "regr_avgx", "regr_avgy", "regr_count", "regr_intercept", "regr_r2", "regr_slope",
      "regr_sxx", "regr_sxy", "regr_syy", "range_agg", "range_intersect_agg", "any_value", "row_number", "rank",
      "dense_rank", "percent_rank", "cume_dist", "ntile", "lag", "lead", "first_value", "last_value", "nth_value",
      // math
      "abs", "cbrt", "ceil", "ceiling", "degrees", "div", "exp", "factorial", "floor", "gcd", "lcm", "ln", "log",
      "log10", "mod", "pi", "power", "radians", "round", "scale", "sign", "sqrt", "trunc", "width_bucket", "random",
      "random_normal", "acos", "asin", "atan", "atan2", "cos", "cot", "sin", "tan", "acosd", "asind", "atand",
      "atan2d", "cosd", "cotd", "sind", "tand", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "min_scale",
      "trim_scale", "erf", "erfc",
      // strings and bytes
      "ascii", "bit_length", "btrim", "char_length", "character_length", "chr", "concat", "concat_ws", "convert",
      "convert_from", "convert_to", "decode", "encode", "format", "initcap", "left", "length", "lower", "lpad", "ltrim",
      "md5", "normalize", "octet_length", "overlay", "position", "quote_ident", "quote_literal", "quote_nullable",
      "regexp_count", "regexp_instr", "regexp_like", "regexp_match", "regexp_matches", "regexp_replace",
      "regexp_split_to_array", "regexp_split_to_table", "regexp_substr", "repeat", "replace", "reverse", "right",
      "rpad", "rtrim", "split_part", "starts_with", "string_to_array", "string_to_table", "strpos", "substr",
      "substring", "to_ascii", "to_hex", "translate", "trim", "unistr", "upper", "sha224", "sha256", "sha384",
      "sha512", "get_bit", "get_byte", "set_bit", "set_byte", "bit_count", "casefold", "text", "uuid_extract_timestamp",
      "uuid_extract_version",
      // date and time
      "age", "clock_timestamp", "date_add", "date_bin", "date_part", "date_subtract", "date_trunc", "extract",
      "isfinite", "justify_days", "justify_hours", "justify_interval", "make_date", "make_interval", "make_time",
      "make_timestamp", "make_timestamptz", "now", "statement_timestamp", "timeofday", "transaction_timestamp",
      "to_char", "to_date", "to_number", "to_timestamp", "timezone", "pg_sleep", "pg_sleep_for", "pg_sleep_until",
      // JSON
      "json_array_length", "jsonb_array_length", "json_build_array", "jsonb_build_array", "json_build_object",
      "jsonb_build_object", "json_each", "jsonb_each", "json_each_text", "jsonb_each_text", "json_extract_path",
      "jsonb_extract_path", "json_extract_path_text", "jsonb_extract_path_text", "json_object_keys",
      "jsonb_object_keys", "json_populate_record", "jsonb_populate_record", "json_populate_recordset",
      "jsonb_populate_recordset", "json_to_record", "jsonb_to_record", "json_to_recordset", "jsonb_to_recordset",
      "json_array_elements", "jsonb_array_elements", "json_array_elements_text", "jsonb_array_elements_text",
      "json_typeof", "jsonb_typeof", "json_strip_nulls", "jsonb_strip_nulls", "jsonb_set", "jsonb_set_lax",
      "jsonb_insert", "jsonb_pretty", "jsonb_path_query", "jsonb_path_exists", "jsonb_path_match",
      "jsonb_path_query_array", "jsonb_path_query_first", "to_json", "to_jsonb", "row_to_json", "array_to_json",
      "json_object", "jsonb_object", "json_scalar", "json_serialize",
      // arrays and ranges
      "array_append", "array_cat", "array_dims", "array_fill", "array_length", "array_lower", "array_ndims",
      "array_position", "array_positions", "array_prepend", "array_remove", "array_replace", "array_sample",
      "array_shuffle", "array_to_string", "array_upper", "cardinality", "trim_array", "unnest", "generate_series",
      "generate_subscripts", "lower_inc", "upper_inc", "lower_inf", "upper_inf", "isempty", "range_merge",
      "int4range", "int8range", "numrange", "tsrange", "tstzrange", "daterange",
      // conditions, types, catalog helpers
      "coalesce", "nullif", "greatest", "least", "pg_typeof", "format_type", "gen_random_uuid", "version",
      "current_database", "current_schema", "current_schemas", "current_setting", "pg_size_pretty",
      "pg_size_bytes", "pg_relation_size", "pg_total_relation_size", "pg_table_size", "pg_indexes_size",
      "pg_database_size", "obj_description", "col_description", "shobj_description", "pg_get_viewdef",
      "pg_get_constraintdef", "pg_get_indexdef", "pg_get_expr", "pg_get_functiondef", "pg_get_triggerdef",
      "pg_get_userbyid", "pg_get_serial_sequence", "has_table_privilege", "has_column_privilege",
      "has_schema_privilege", "has_database_privilege", "has_function_privilege", "has_sequence_privilege",
      "pg_has_role", "to_regclass", "to_regtype", "to_regproc", "to_regrole", "to_regnamespace", "pg_is_in_recovery",
      "pg_postmaster_start_time", "pg_conf_load_time", "pg_backend_pid", "inet_client_addr", "inet_server_addr",
      "host", "hostmask", "netmask", "network", "masklen", "abbrev", "broadcast", "family", "inet_merge",
      "inet_same_family", "set_masklen",
      // sequences: built in, and the READ ONLY transaction refuses the ones that write
      "nextval", "currval", "setval", "lastval",
      // full text
      "to_tsvector", "to_tsquery", "plainto_tsquery", "phraseto_tsquery", "websearch_to_tsquery", "ts_rank",
      "ts_rank_cd", "ts_headline", "setweight", "strip", "numnode", "querytree", "array_to_tsvector",
      "tsvector_to_array", "ts_delete", "ts_filter");

  /** MySQL 8 and MariaDB built-ins (fixed: their catalogs don't list built-in functions). */
  static final Set<String> MYSQL_BUILT_IN = names(
      // aggregates and window
      "avg", "bit_and", "bit_or", "bit_xor", "count", "group_concat", "json_arrayagg", "json_objectagg", "max", "min",
      "std", "stddev", "stddev_pop", "stddev_samp", "sum", "var_pop", "var_samp", "variance", "any_value", "grouping",
      "cume_dist", "dense_rank", "first_value", "lag", "last_value", "lead", "nth_value", "ntile", "percent_rank",
      "rank", "row_number", "median", "percentile_cont", "percentile_disc",
      // strings
      "ascii", "bin", "bit_length", "char", "char_length", "character_length", "concat", "concat_ws", "elt",
      "export_set", "field", "find_in_set", "format", "from_base64", "hex", "insert", "instr", "lcase", "left",
      "length", "locate", "lower", "lpad", "ltrim", "make_set", "mid", "oct", "octet_length", "ord", "position",
      "quote", "regexp_instr", "regexp_like", "regexp_replace", "regexp_substr", "repeat", "replace", "reverse",
      "right", "rpad", "rtrim", "soundex", "space", "strcmp", "substr", "substring", "substring_index", "to_base64",
      "trim", "ucase", "unhex", "upper", "weight_string", "char_length", "natural_sort_key", "sformat", "to_char",
      "length_b", "lengthb", "chr", "nvl", "nvl2", "decode", "decode_oracle", "instr", "lpad", "rpad",
      // numbers
      "abs", "acos", "asin", "atan", "atan2", "ceil", "ceiling", "conv", "cos", "cot", "crc32", "degrees", "exp",
      "floor", "ln", "log", "log10", "log2", "mod", "pi", "pow", "power", "radians", "rand", "round", "sign", "sin",
      "sqrt", "tan", "truncate", "format_bytes", "format_pico_time",
      // date and time
      "adddate", "addtime", "convert_tz", "curdate", "current_date", "current_time", "current_timestamp", "curtime",
      "date", "date_add", "date_format", "date_sub", "datediff", "day", "dayname", "dayofmonth", "dayofweek",
      "dayofyear", "extract", "from_days", "from_unixtime", "get_format", "hour", "last_day", "localtime",
      "localtimestamp", "makedate", "maketime", "microsecond", "minute", "month", "monthname", "now", "period_add",
      "period_diff", "quarter", "sec_to_time", "second", "str_to_date", "subdate", "subtime", "sysdate", "time",
      "time_format", "time_to_sec", "timediff", "timestamp", "timestampadd", "timestampdiff", "to_days",
      "to_seconds", "unix_timestamp", "utc_date", "utc_time", "utc_timestamp", "week", "weekday", "weekofyear",
      "year", "yearweek", "sleep", "add_months", "months_between",
      // conditions and casts
      "if", "ifnull", "nullif", "coalesce", "greatest", "least", "isnull", "interval", "cast", "convert", "binary",
      // JSON
      "json_array", "json_array_append", "json_array_insert", "json_contains", "json_contains_path", "json_depth",
      "json_extract", "json_insert", "json_keys", "json_length", "json_merge", "json_merge_patch",
      "json_merge_preserve", "json_object", "json_overlaps", "json_pretty", "json_quote", "json_remove",
      "json_replace", "json_schema_valid", "json_schema_validation_report", "json_search", "json_set",
      "json_storage_free", "json_storage_size", "json_table", "json_type", "json_unquote", "json_valid", "json_value",
      "json_detailed", "json_compact", "json_loose", "json_exists", "json_query", "json_equals", "json_normalize",
      "json_array_intersect", "json_object_filter_keys", "json_object_to_array",
      // hashes, ids, network, information
      "md5", "sha", "sha1", "sha2", "uuid", "uuid_short", "uuid_to_bin", "bin_to_uuid", "is_uuid", "random_bytes",
      "compress", "uncompress", "uncompressed_length", "inet_aton", "inet_ntoa", "inet6_aton", "inet6_ntoa",
      "is_ipv4", "is_ipv4_compat", "is_ipv4_mapped", "is_ipv6", "database", "schema", "user", "current_user",
      "session_user", "system_user", "version", "connection_id", "found_rows", "row_count", "last_insert_id",
      "charset", "collation", "coercibility", "icu_version", "roles_graphml", "current_role", "is_free_lock",
      "is_used_lock", "name_const", "values", "default", "statement_digest", "statement_digest_text");

  /** SQL scalar, aggregate and analytic built-ins; stored PL/SQL packages are deliberately absent. */
  static final Set<String> ORACLE_BUILT_IN = names(
      "abs", "acos", "asin", "atan", "atan2", "bitand", "ceil", "cos", "cosh", "exp", "floor", "ln", "log",
      "mod", "nanvl", "power", "remainder", "round", "sign", "sin", "sinh", "sqrt", "tan", "tanh", "trunc",
      "width_bucket", "ascii", "asciistr", "chr", "concat", "initcap", "instr", "instrb", "instrc", "instr2",
      "instr4", "length", "lengthb", "lengthc", "length2", "length4", "lower", "lpad", "ltrim", "nchr",
      "nls_initcap", "nls_lower", "nls_upper", "nlssort", "regexp_count", "regexp_instr", "regexp_replace",
      "regexp_substr", "replace", "rpad", "rtrim", "soundex", "substr", "substrb", "substrc", "substr2",
      "substr4", "translate", "trim", "unistr", "upper", "add_months", "current_date", "current_timestamp",
      "dbtimezone", "extract", "from_tz", "last_day", "localtimestamp", "months_between", "new_time", "next_day",
      "numtodsinterval", "numtoyminterval", "sessiontimezone", "sysdate", "systimestamp", "sys_extract_utc",
      "to_char", "to_date", "to_dsinterval", "to_number", "to_timestamp", "to_timestamp_tz", "to_yminterval",
      "to_binary_double", "to_binary_float", "to_clob", "to_nchar", "to_nclob", "cast", "convert", "hextoraw",
      "rawtohex", "rawtonhex", "rowidtochar", "chartorowid", "coalesce", "decode", "greatest", "least", "nvl",
      "nvl2", "nullif", "avg", "count", "max", "min", "sum", "median", "listagg", "stddev", "stddev_pop",
      "stddev_samp", "variance", "var_pop", "var_samp", "corr", "covar_pop", "covar_samp", "grouping",
      "grouping_id", "group_id", "dense_rank", "rank", "row_number", "percent_rank", "cume_dist", "ntile",
      "lag", "lead", "first_value", "last_value", "nth_value", "percentile_cont", "percentile_disc",
      "ratio_to_report", "regr_slope", "regr_intercept", "regr_count", "regr_r2", "regr_avgx", "regr_avgy",
      "regr_sxx", "regr_syy", "regr_sxy", "json_array", "json_arrayagg", "json_object", "json_objectagg",
      "json_value", "json_query", "json_serialize", "json_exists", "json_table", "standard_hash", "ora_hash",
      "sys_context", "userenv", "uid", "user", "vsize", "dump", "sys_connect_by_path", "lnnvl");

  private FunctionRules() {}

  /** The lists above name a few functions in more than one group; a set keeps each once. */
  private static Set<String> names(String... names) {
    return Set.copyOf(new HashSet<>(List.of(names)));
  }

  /**
   * The functions of a statement against the rules.
   *
   * @param metadataBuiltIns PostgreSQL's {@code pg_catalog} names from Test connection, or empty
   * @param policyDenied     the policy's {@code deniedFunctions}
   * @param allowed          the connection's {@code allowedFunctions}: {@code fn} or {@code schema.fn}
   */
  static Verdict check(List<FunctionRef> functions, boolean mysql, Set<String> metadataBuiltIns,
      List<String> policyDenied, List<String> allowed) {
    return check(functions, SqlSyntax.of(mysql), metadataBuiltIns, policyDenied, allowed);
  }

  static Verdict check(List<FunctionRef> functions, SqlDialect dialect, Set<String> metadataBuiltIns,
      List<String> policyDenied, List<String> allowed) {
    return check(functions, dialect.syntax(), metadataBuiltIns, policyDenied, allowed);
  }

  static Verdict check(List<FunctionRef> functions, SqlSyntax syntax, Set<String> metadataBuiltIns,
      List<String> policyDenied, List<String> allowed) {
    List<String> denied = new ArrayList<>();
    List<String> notBuiltIn = new ArrayList<>();
    List<String> denylist = new ArrayList<>(switch (syntax) {
      case MYSQL -> MYSQL_DENIED;
      case POSTGRES -> POSTGRES_DENIED;
      case SQLSERVER -> SQLSERVER_DENIED;
      case ORACLE -> List.of("dbms_*", "utl_*", "nextval", "currval");
    });
    policyDenied.forEach(d -> denylist.add(d.toLowerCase(Locale.ROOT)));
    Set<String> seen = new HashSet<>();
    for (FunctionRef f : functions) {
      if (f.parts().isEmpty() || !seen.add(f.written())) {
        continue;
      }
      String bare = unquote(f.parts().getLast()).toLowerCase(Locale.ROOT);
      if (Glob.anyText(denylist, bare)
          || (syntax == SqlSyntax.ORACLE && f.parts().stream()
              .map(part -> unquote(part).toLowerCase(Locale.ROOT)).anyMatch(part -> Glob.anyText(denylist, part)))) {
        denied.add(f.written());
        continue;
      }
      if (syntax == SqlSyntax.ORACLE) {
        // Autonomous PL/SQL can commit outside the read-only transaction, even when the caller rolls back.
        // Neither approval nor allowedFunctions can turn a stored routine into a safe read.
        if (f.parts().size() != 1 || quoted(f.parts().getFirst()) || !ORACLE_BUILT_IN.contains(bare)) {
          denied.add(f.written());
        }
      } else if (syntax == SqlSyntax.SQLSERVER) {
        // SQL Server cannot contain unknown functions in a read-only transaction; explicit allowlists do not help.
        if (f.parts().size() != 1 || quoted(f.parts().getFirst()) || !SQLSERVER_BUILT_IN.contains(bare)) {
          notBuiltIn.add(f.written());
        }
      } else if (!builtIn(f, syntax == SqlSyntax.MYSQL, metadataBuiltIns)
          && !allowed(f, syntax == SqlSyntax.MYSQL, allowed)) {
        notBuiltIn.add(f.written());
      }
    }
    return new Verdict(List.copyOf(denied), List.copyOf(notBuiltIn));
  }

  private static boolean builtIn(FunctionRef f, boolean mysql, Set<String> metadataBuiltIns) {
    String last = f.parts().getLast();
    if (mysql) {
      // A schema-qualified call is a stored function: never built in.
      return f.parts().size() == 1 && !quoted(last) && MYSQL_BUILT_IN.contains(last.toLowerCase(Locale.ROOT));
    }
    if (f.parts().size() > 2) {
      return false;
    }
    if (f.parts().size() == 2 && !"pg_catalog".equals(normalizePostgres(f.parts().getFirst()))) {
      return false;
    }
    String name = normalizePostgres(last);
    return metadataBuiltIns.contains(name) || POSTGRES_BUILT_IN.contains(name);
  }

  private static boolean allowed(FunctionRef f, boolean mysql, List<String> allowed) {
    if (allowed.isEmpty()) {
      return false;
    }
    String written = String.join(".", f.parts().stream().map(p -> mysql ? unquote(p).toLowerCase(Locale.ROOT)
        : normalizePostgres(p)).toList());
    String bare = mysql ? unquote(f.parts().getLast()).toLowerCase(Locale.ROOT) : normalizePostgres(f.parts().getLast());
    for (String a : allowed) {
      // Entries are plain names (the form's pattern): PostgreSQL folds them like unquoted names, MySQL compares
      // function names without case.
      String entry = a.strip().toLowerCase(Locale.ROOT);
      if (entry.equals(written) || (entry.indexOf('.') < 0 && entry.equals(bare) && f.parts().size() == 1)) {
        return true;
      }
    }
    return false;
  }

  /** The {@code pg_catalog} names stored by Test connection ({@code metadata.builtinFunctions}). */
  static Set<String> fromMetadata(JsonNode metadata) {
    if (metadata == null || !metadata.path("builtinFunctions").isArray()) {
      return Set.of();
    }
    Set<String> out = new HashSet<>();
    for (JsonNode n : metadata.get("builtinFunctions")) {
      if (n.isString()) {
        out.add(n.asString());
      }
    }
    return Set.copyOf(out);
  }

  private static String normalizePostgres(String part) {
    return quoted(part) ? unquote(part) : part.toLowerCase(Locale.ROOT);
  }

  private static boolean quoted(String part) {
    return part.length() >= 2 && ((part.charAt(0) == '[' && part.endsWith("]"))
        || ((part.charAt(0) == '"' || part.charAt(0) == '`') && part.charAt(part.length() - 1) == part.charAt(0)));
  }

  private static String unquote(String part) {
    if (!quoted(part)) {
      return part;
    }
    char q = part.charAt(0) == '[' ? ']' : part.charAt(0);
    return part.substring(1, part.length() - 1).replace(String.valueOf(q) + q, String.valueOf(q));
  }
}
