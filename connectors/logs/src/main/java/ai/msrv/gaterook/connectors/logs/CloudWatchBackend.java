package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.AwsSessionPolicy;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetQueryResultsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.cloudwatchlogs.model.QueryLanguage;
import software.amazon.awssdk.services.cloudwatchlogs.model.QueryStatus;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResultField;
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * AWS CloudWatch Logs through the SDK (report 07 §3.4): searches with Logs Insights ({@code StartQuery} →
 * {@code GetQueryResults}, always the CWLI language, always the connection's log groups) or with filter patterns
 * ({@code FilterLogEvents}, newest first); tails with {@code FilterLogEvents} oldest first, one log group per call.
 * Credentials are the call's (AssumeRole with ExternalId and a read-only session policy when a role is set), and
 * every value STS hands out is tainted. {@code FilterLogEvents} is 5 calls per second per account in many regions,
 * shared with people: the gateway keeps to 2 through {@code ExecContext.acquire}.
 */
final class CloudWatchBackend implements Backend {

  /** What an assumed role may do in this session, whatever the role itself allows (report 09 §6). */
  static final String SESSION_POLICY = AwsSessionPolicy.builder()
      .allow(List.of("logs:DescribeLogGroups", "logs:FilterLogEvents", "logs:StartQuery", "logs:GetQueryResults",
          "logs:StopQuery"), List.of())
      .deny(List.of("logs:Unmask"), List.of())
      .toJson();
  static final int MAX_PAGES = 3;
  private static final Pattern HAS_PROJECTION = Pattern.compile("(?i)(^|\\|)\\s*(fields|display|stats)\\b");
  private static final Pattern HAS_ORDER = Pattern.compile("(?i)(^|\\|)\\s*(sort|stats)\\b");
  private static final Pattern HAS_STATS = Pattern.compile("(?i)(^|\\|)\\s*stats\\b");
  private static final String DEFAULT_FIELDS = "fields @timestamp, @message, @logStream, @log";
  private static final DateTimeFormatter INSIGHTS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Settings.CloudWatch s;
  private final Call call;

  CloudWatchBackend(Settings.CloudWatch s, Call call) {
    this.s = s;
    this.call = call;
  }

  /**
   * The query as it goes to {@code StartQuery}: the agent's, with {@code fields …} in front when it projects nothing
   * and {@code | sort @timestamp desc} behind when it orders nothing (newest first, like every search).
   */
  static String insightsQuery(String query) {
    String q = query.strip();
    if (q.isEmpty()) {
      return DEFAULT_FIELDS + " | sort @timestamp desc";
    }
    String out = HAS_PROJECTION.matcher(q).find() ? q : DEFAULT_FIELDS + " | " + q;
    return HAS_ORDER.matcher(q).find() ? out : out + " | sort @timestamp desc";
  }

  @Override
  public Page newest(String query, String source, long fromNs, long toNs, int want, String continuation)
      throws ConnectorException {
    if (s.insights()) {
      return insights(query, source, fromNs, toNs, want);
    }
    return filter(query, group(source), fromNs, toNs, want, continuation, false);
  }

  @Override
  public Page oldest(String query, String source, long fromNs, long toNs, int want) throws ConnectorException {
    return filter(query, group(source), fromNs, toNs, want, null, true);
  }

  @Override
  public Duration pollEvery() {
    return Duration.ofSeconds(2);
  }

  @Override
  public Duration delay() {
    return Duration.ofSeconds(5);
  }

  @Override
  public boolean searchCursor() {
    return !s.insights();
  }

  /**
   * The log groups of a query: by name when they are all names, by identifier when one is an ARN (another account's
   * group through a monitoring account). {@code StartQuery} takes exactly one of the two, and names are what every
   * implementation of the API understands.
   */
  private static StartQueryRequest.Builder groups(StartQueryRequest.Builder r, List<String> groups) {
    if (groups.stream().noneMatch(g -> g.startsWith("arn:"))) {
      return r.logGroupNames(groups);
    }
    return r.logGroupIdentifiers(groups.stream().map(g -> g.endsWith(":*") ? g.substring(0, g.length() - 2) : g)
        .toList());
  }

  /** The one log group of a filter-pattern call: the agent's {@code source}, else the first of the connection. */
  String group(String source) {
    return source != null ? source : s.logGroups().get(0);
  }

  // ── Logs Insights ───────────────────────────────────────────────────────────────────────────────────────────

  private Page insights(String query, String source, long fromNs, long toNs, int want) throws ConnectorException {
    CloudWatchLogsClient cw = client();
    String effective = insightsQuery(query);
    List<String> groups = source != null ? List.of(source) : s.logGroups();
    acquire("StartQuery", 300);
    AwsRequestOverrideConfiguration start = override();
    String queryId = aws("StartQuery", () -> cw.startQuery(r -> groups(r.overrideConfiguration(start)
        .queryLanguage(QueryLanguage.CWLI), groups)
        .startTime(Math.floorDiv(fromNs, 1_000_000_000L)).endTime(ceilDiv(toNs, 1_000_000_000L))
        .queryString(effective).limit(Math.min(want, 10_000))).queryId());
    int requests = 1;
    GetQueryResultsResponse res = null;
    long sleep = 250;
    while (true) {
      if (call.ctx().remaining().toMillis() < sleep + 300) {
        break;
      }
      pause(sleep);
      sleep = Math.min(1_000, sleep * 2);
      if (!call.ctx().acquire(quotaKey("getqueryresults"), 300)) {
        break;
      }
      AwsRequestOverrideConfiguration poll = override();
      res = aws("GetQueryResults", () -> cw.getQueryResults(r -> r.overrideConfiguration(poll)
          .queryId(queryId)));
      requests++;
      QueryStatus status = res.status();
      if (status != QueryStatus.SCHEDULED && status != QueryStatus.RUNNING) {
        break;
      }
    }
    String partial = null;
    if (res == null || res.status() == QueryStatus.SCHEDULED || res.status() == QueryStatus.RUNNING) {
      // The deadline came first: what Insights found so far is still an answer (it returns partial rows).
      stopQuietly(cw, queryId);
      requests++;
      partial = "partial";
    } else if (res.status() != QueryStatus.COMPLETE) {
      throw ConnectorException.resourceError("Logs Insights stopped the query: " + res.statusAsString()
          + ". Narrow the range or the log groups.", null);
    }
    long bytes = res == null || res.statistics() == null || res.statistics().bytesScanned() == null ? -1
        : Math.round(res.statistics().bytesScanned());
    long records = res == null || res.statistics() == null || res.statistics().recordsScanned() == null ? -1
        : Math.round(res.statistics().recordsScanned());
    Page.Stats stats = new Page.Stats(requests, bytes, records);
    List<List<ResultField>> rows = res == null ? List.of() : res.results();
    if (HAS_STATS.matcher(effective).find() || rows.stream().anyMatch(r -> value(r, "@timestamp") == null)) {
      return new Page(List.of(), false, null, partial, aggregate(rows), stats);
    }
    List<LogEntry> entries = new ArrayList<>();
    for (List<ResultField> row : rows) {
      LogEntry e = insightsEntry(row);
      if (e.nanos() >= fromNs && e.nanos() <= toNs) {
        entries.add(e);
      }
    }
    entries.sort(LokiBackend.NEWEST_FIRST);
    Page p = Page.of(entries, rows.size() >= Math.min(want, 10_000), null, stats);
    return partial == null ? p : p.withPartial(partial);
  }

  private LogEntry insightsEntry(List<ResultField> row) {
    Map<String, String> labels = new LinkedHashMap<>();
    ObjectNode fields = JsonNodeFactory.instance.objectNode();
    String message = "";
    String ptr = null;
    long ns = 0;
    for (ResultField f : row) {
      String name = f.field();
      String v = f.value();
      if (name == null || v == null) {
        continue;
      }
      switch (name) {
        case "@timestamp" -> ns = insightsTime(v);
        case "@message" -> message = v;
        case "@logStream" -> labels.put("logStream", v);
        case "@log" -> labels.put("log", v);
        case "@ptr" -> ptr = v;
        default -> fields.put(name, v);
      }
    }
    String id = ptr != null ? ptr : LogEntry.hashId(Long.toString(ns), labels.toString(), message);
    return LogEntry.of(ns, severity(message), message, labels, fields, id, call.maxChars());
  }

  private static Page.Aggregate aggregate(List<List<ResultField>> rows) {
    List<String> columns = new ArrayList<>();
    for (List<ResultField> row : rows) {
      for (ResultField f : row) {
        if (f.field() != null && !f.field().equals("@ptr") && !columns.contains(f.field())) {
          columns.add(f.field());
        }
      }
    }
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    for (List<ResultField> row : rows) {
      ArrayNode r = out.addArray();
      for (String c : columns) {
        String v = value(row, c);
        if (v == null) {
          r.addNull();
        } else {
          r.add(v);
        }
      }
    }
    return new Page.Aggregate(List.copyOf(columns), out);
  }

  private static String value(List<ResultField> row, String field) {
    for (ResultField f : row) {
      if (field.equals(f.field())) {
        return f.value();
      }
    }
    return null;
  }

  /** {@code 2026-10-22 12:29:58.123} (UTC), ISO-8601 or epoch milliseconds: Insights has used all three. */
  static long insightsTime(String v) {
    String t = v.strip();
    try {
      if (t.chars().allMatch(Character::isDigit)) {
        return Math.multiplyExact(Long.parseLong(t), 1_000_000L);
      }
      if (t.length() >= 19 && t.charAt(10) == ' ') {
        String withMillis = t.length() == 19 ? t + ".000" : t;
        Instant i = LocalDateTime.parse(withMillis.substring(0, Math.min(23, withMillis.length())), INSIGHTS_TIME)
            .toInstant(ZoneOffset.UTC);
        return i.toEpochMilli() * 1_000_000L;
      }
      Instant i = Instant.parse(t);
      return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
    } catch (DateTimeParseException | NumberFormatException | ArithmeticException e) {
      return 0;
    }
  }

  private void stopQuietly(CloudWatchLogsClient cw, String queryId) {
    try {
      AwsCredentialsProvider p = credentials();
      cw.stopQuery(r -> r.overrideConfiguration(o -> o.credentialsProvider(p)
          .apiCallTimeout(Duration.ofSeconds(2))).queryId(queryId));
    } catch (SdkException | ConnectorException e) {
      // The query ends on its own; stopping it only saves scanned bytes.
      call.ctx().log().log(System.Logger.Level.DEBUG, "StopQuery failed: " + e.getMessage());
    }
  }

  // ── Filter patterns ─────────────────────────────────────────────────────────────────────────────────────────

  private Page filter(String query, String group, long fromNs, long toNs, int want, String continuation,
      boolean ascending) throws ConnectorException {
    CloudWatchLogsClient cw = client();
    // CloudWatch counts milliseconds; the window's ends are inclusive.
    long startMs = ceilDiv(fromNs, 1_000_000L);
    long endMs = Math.floorDiv(toNs, 1_000_000L);
    List<LogEntry> entries = new ArrayList<>();
    String token = continuation;
    int requests = 0;
    String partial = null;
    while (requests < MAX_PAGES) {
      if (!call.ctx().acquire(quotaKey("filterlogevents"), 120)) {
        if (requests == 0) {
          throw ConnectorException.resourceError("MCP Gateway keeps CloudWatch FilterLogEvents to 2 calls per second"
              + " for this account and region, and they are used up. Try again in a few seconds.", null);
        }
        partial = "partial";
        break;
      }
      String next = token;
      int size = Math.max(1, Math.min(10_000, want - entries.size()));
      FilterLogEventsRequest.Builder b = FilterLogEventsRequest.builder().overrideConfiguration(override())
          .startTime(startMs).endTime(endMs).limit(size).startFromHead(ascending).unmask(false);
      if (group.startsWith("arn:")) {
        b.logGroupIdentifier(group.endsWith(":*") ? group.substring(0, group.length() - 2) : group);
      } else {
        b.logGroupName(group);
      }
      if (!query.isBlank()) {
        b.filterPattern(query);
      }
      if (next != null) {
        b.nextToken(next);
      }
      FilterLogEventsResponse r = aws("FilterLogEvents", () -> cw.filterLogEvents(b.build()));
      requests++;
      for (FilteredLogEvent e : r.events()) {
        entries.add(filterEntry(e, group));
      }
      token = r.nextToken();
      if (token != null && (token.isBlank() || token.equals(next))) {
        token = null;
      }
      if (entries.size() >= want || token == null) {
        break;
      }
      if (call.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        partial = "timeout";
        break;
      }
    }
    entries.removeIf(e -> e.nanos() < fromNs || e.nanos() > toNs);
    entries.sort(ascending ? LokiBackend.OLDEST_FIRST : LokiBackend.NEWEST_FIRST);
    Page p = Page.of(entries, token != null || entries.size() >= want, token, Page.Stats.requests(requests));
    return partial == null ? p : p.withPartial(partial);
  }

  private LogEntry filterEntry(FilteredLogEvent e, String group) {
    long ns = Math.multiplyExact(e.timestamp() == null ? 0 : e.timestamp(), 1_000_000L);
    Map<String, String> labels = new LinkedHashMap<>();
    if (e.logStreamName() != null) {
      labels.put("logStream", e.logStreamName());
    }
    labels.put("log", group);
    String message = e.message() == null ? "" : e.message();
    String id = e.eventId() != null ? e.eventId() : LogEntry.hashId(Long.toString(ns), e.logStreamName(), message);
    return LogEntry.of(ns, severity(message), message, labels, null, id, call.maxChars());
  }

  /** {@code level} of a JSON message, else the level word a plain line starts with. */
  static Severity severity(String message) {
    String m = message == null ? "" : message.stripLeading();
    if (m.startsWith("{") && m.length() < 64 * 1024) {
      try {
        JsonNode tree = JSON.readTree(m);
        for (String field : List.of("level", "severity", "log.level", "levelname", "lvl")) {
          JsonNode n = tree.get(field);
          if (n != null && n.isValueNode()) {
            return Severity.of(n.asString());
          }
        }
        JsonNode nested = tree.at("/log/level");
        if (nested.isValueNode()) {
          return Severity.of(nested.asString());
        }
      } catch (JacksonException e) {
        // Not JSON after all: read it as a line.
      }
    }
    return Severity.ofLine(m);
  }

  // ── Test, health, describe ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test() {
    Probe probe = new Probe(call.ctx());
    List<TestResult.Hint> hints = new ArrayList<>();
    long dataMs;
    String arn;
    try {
      CloudCredentials.CallerIdentity identity = probe.step("identity",
          () -> call.cloud().callerIdentity(credentials(), s.region()), CloudCredentials.CallerIdentity::arn);
      arn = identity.arn();
      CloudWatchLogsClient cw = client();
      for (String g : s.logGroups()) {
        String name = g.startsWith("arn:") ? g.replaceFirst("^.*:log-group:", "").replaceFirst(":\\*$", "") : g;
        probe.step("log group " + name, () -> {
          LogGroup found = findGroup(cw, name);
          if (found == null) {
            throw ConnectorException.resourceError("Log group " + name + " not found in " + s.region() + ".", null);
          }
          return found;
        }, lg -> (lg.retentionInDays() == null ? "kept forever" : "kept " + lg.retentionInDays() + " days")
            + (lg.storedBytes() == null ? "" : ", " + lg.storedBytes() / (1024 * 1024) + " MB"));
      }
      long now = call.ctx().clock().instant().toEpochMilli() * 1_000_000L;
      long started = System.nanoTime();
      probe.step("read", () -> filter("", s.logGroups().get(0), now - Duration.ofHours(1).toNanos(), now, 1, null,
          false), p -> p.entries().isEmpty() ? "no events in the last hour" : "newest event "
          + p.entries().get(0).timestamp());
      dataMs = (System.nanoTime() - started) / 1_000_000;
      if (s.insights()) {
        probe.step("insights", () -> {
          acquire("StartQuery", 300);
          String id;
          try {
            AwsRequestOverrideConfiguration o = override();
            id = aws("StartQuery", () -> cw.startQuery(r -> groups(r.overrideConfiguration(o)
                .queryLanguage(QueryLanguage.CWLI), List.of(s.logGroups().get(0)))
                .startTime(now / 1_000_000_000L - 300).endTime(now / 1_000_000_000L)
                .queryString("fields @timestamp | limit 1").limit(1)).queryId());
          } catch (ConnectorException e) {
            if (e.getCause() instanceof AwsServiceException ase && ase.awsErrorDetails() != null
                && ase.awsErrorDetails().errorCode().startsWith("AccessDenied")) {
              throw ConnectorException.resourceError("Logs Insights isn't allowed for this role: allow "
                  + "logs:StartQuery and logs:GetQueryResults, or switch the connection to filter patterns.", e);
            }
            throw e;
          }
          stopQuietly(cw, id);
          return "allowed";
        });
      }
    } catch (ConnectorException e) {
      return probe.failure(e);
    }
    AwsSpec spec = s.credentials();
    if (spec.roleArn() == null && spec.source() == AwsSpec.Source.ACCESS_KEY && call.cfg().env() == Env.PROD) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "logs.long-lived-key", "/credentials/roleArn",
          "Use AssumeRole on prod: the access key doesn't expire, a role session lasts 15 min."));
    }
    for (String w : call.cloud().profileWarnings(spec)) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "logs.profile", "/credentials/profile", w));
    }
    TestResult result = TestResult.success(dataMs, "CloudWatch Logs", arn,
        new TestResult.Permissions(TestResult.Level.UNKNOWN, "Reads " + String.join(", ", s.logGroups()),
            spec.roleArn() != null ? "None in this session (read-only session policy)" : "Unknown", List.of(),
            null))
        .withChecks(probe.checks())
        .withMetadata(JsonNodeFactory.instance.objectNode().put("serverVersion", "CloudWatch Logs"));
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  @Override
  public HealthResult health() {
    long started = System.nanoTime();
    try {
      String g = s.logGroups().get(0);
      String name = g.startsWith("arn:") ? g.replaceFirst("^.*:log-group:", "").replaceFirst(":\\*$", "") : g;
      CloudWatchLogsClient cw = client();
      AwsRequestOverrideConfiguration o = override();
      aws("DescribeLogGroups", () -> cw.describeLogGroups(r -> r.overrideConfiguration(o)
          .logGroupNamePrefix(name).limit(1)));
      return HealthResult.healthy((System.nanoTime() - started) / 1_000_000);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public ObjectNode describe(String object) throws ConnectorException {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("region", s.region());
    ArrayNode groups = out.putArray("logGroups");
    s.logGroups().forEach(groups::add);
    out.put("searchApi", s.insights() ? "insights" : "filter");
    ArrayNode examples = out.putArray("examples");
    if (s.insights()) {
      examples.add("filter @message like /ERROR/");
      examples.add("stats count(*) by bin(5m)");
    }
    examples.add("ERROR");
    examples.add("{ $.level = \"error\" }");
    if (object != null && !object.isBlank()) {
      if (!s.logGroups().contains(object)) {
        throw ConnectorException.badInput(object + " isn't one of this connection's log groups.");
      }
      String name = object.startsWith("arn:") ? object.replaceFirst("^.*:log-group:", "").replaceFirst(":\\*$", "")
          : object;
      LogGroup g = findGroup(client(), name);
      if (g != null) {
        ObjectNode d = out.putObject("logGroup");
        d.put("name", g.logGroupName());
        if (g.retentionInDays() != null) {
          d.put("retentionInDays", g.retentionInDays());
        }
        if (g.storedBytes() != null) {
          d.put("storedBytes", g.storedBytes());
        }
        if (g.logGroupClassAsString() != null) {
          d.put("class", g.logGroupClassAsString());
        }
      }
    }
    return out;
  }

  private LogGroup findGroup(CloudWatchLogsClient cw, String name) throws ConnectorException {
    AwsRequestOverrideConfiguration o = override();
    DescribeLogGroupsResponse r = aws("DescribeLogGroups", () -> cw.describeLogGroups(q -> q
        .overrideConfiguration(o).logGroupNamePrefix(name).limit(50)));
    for (LogGroup g : r.logGroups()) {
      if (name.equals(g.logGroupName())) {
        return g;
      }
    }
    return null;
  }

  // ── SDK plumbing ────────────────────────────────────────────────────────────────────────────────────────────

  private CloudWatchLogsClient client() {
    return call.clients().cloudWatch(call.cfg(), s.region(), call.cloud().endpoints().service("aws.logs"));
  }

  /** The call's credentials: every secret key and session token they resolve to joins the call's taint. */
  private AwsCredentialsProvider credentials() throws ConnectorException {
    AwsCredentialsProvider base = call.cloud().aws(new CloudCredentials.Owner(call.cfg().id(), call.cfg().version(),
        call.cfg().name()), s.credentials(), SESSION_POLICY, call.secrets());
    return CloudCredentials.tainting(base, call.ctx()::taint);
  }

  private AwsRequestOverrideConfiguration override() throws ConnectorException {
    Duration left = call.ctx().remaining().minus(Http.MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout("CloudWatch Logs didn't answer in time.");
    }
    return AwsRequestOverrideConfiguration.builder().credentialsProvider(credentials()).apiCallTimeout(left).build();
  }

  private void acquire(String api, int perMinute) throws ConnectorException {
    if (!call.ctx().acquire(quotaKey(api), perMinute)) {
      throw ConnectorException.resourceError("CloudWatch " + api + " calls of this account are at MCP Gateway's "
          + "limit. Try again in a few seconds.", null);
    }
  }

  private String quotaKey(String api) {
    return ("aws:" + s.account() + ":" + s.region() + ":" + api).toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9:._/-]", "-");
  }

  /** One request of the SDK. */
  @FunctionalInterface
  private interface SdkCall<T> {
    T run() throws ConnectorException;
  }

  /** One SDK call, its failure told in words: the operation, what AWS said, and what to change. */
  private <T> T aws(String operation, SdkCall<T> request) throws ConnectorException {
    try {
      return request.run();
    } catch (ApiCallTimeoutException e) {
      throw ConnectorException.timeout("CloudWatch Logs " + operation + " didn't answer in time.");
    } catch (AwsServiceException e) {
      String code = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
      String message = e.awsErrorDetails() == null || e.awsErrorDetails().errorMessage() == null ? e.getMessage()
          : e.awsErrorDetails().errorMessage();
      throw switch (code) {
        case "ResourceNotFoundException" -> ConnectorException.resourceError("CloudWatch Logs " + operation + ": "
            + message + " (region " + s.region() + ").", e);
        case "AccessDeniedException", "AccessDenied", "UnauthorizedOperation" -> ConnectorException.resourceError(
            "AWS denied " + operation + ": " + message + ". Allow logs:" + operation + " on the log groups.", e);
        case "ThrottlingException", "LimitExceededException", "TooManyRequestsException" ->
            ConnectorException.resourceError("CloudWatch is throttling " + operation + ", try again in a few "
                + "seconds.", e);
        case "InvalidParameterException", "MalformedQueryException", "ValidationException" ->
            ConnectorException.badInput("CloudWatch Logs refused the query: " + message);
        case "UnrecognizedClientException", "InvalidSignatureException", "ExpiredTokenException" ->
            ConnectorException.unavailable("AWS refused the credentials: " + message + ". Check them under "
                + "Credentials, then test again.", e);
        default -> e.statusCode() >= 500
            ? ConnectorException.unavailable("CloudWatch Logs " + operation + " failed: " + message, e)
            : ConnectorException.resourceError("CloudWatch Logs " + operation + ": " + message, e);
      };
    } catch (SecurityException e) {
      throw ConnectorException.denied(e.getMessage());
    } catch (SdkException e) {
      String m = e.getMessage() == null ? "" : e.getMessage();
      if (m.contains("Unable to load credentials") || m.contains("credentials")) {
        throw ConnectorException.unavailable("AWS credentials couldn't be loaded: " + m, e);
      }
      throw ConnectorException.unavailable("Can't reach CloudWatch Logs in " + s.region() + ": " + m, e);
    }
  }

  private static void pause(long millis) throws ConnectorException {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The search was cancelled.");
    }
  }

  private static long ceilDiv(long a, long b) {
    return -Math.floorDiv(-a, b);
  }
}
