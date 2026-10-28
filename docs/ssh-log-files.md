# SSH log files

The **SSH log files** connection reads explicitly selected remote files over SFTP. Configure it in **Connections → Add connection → SSH log files** with a host, user, password or local private-key path, and trusted server key. Browse the remote folders, select a root and allow individual files. Selecting a folder alone does not grant access to every file in it. New rotated filenames must be selected explicitly.

The account needs SFTP and read access to those files. No shell commands run. Symbolic links and nonregular files are rejected. SSH policies control credential methods and output size; Limits controls line counts, response size and deadlines; Logs controls the maximum query period.

## Tools

| Tool | Use |
| --- | --- |
| `ssh_logs_list` | List the selected files and their sizes. |
| `ssh_logs_read` | Read one file, including its last N records or records in a time range. |
| `ssh_logs_search` | Filter one, several, or all allowed files by literal text, regex, level and event time. |

Each physical UTF-8 line is one record. JSON Lines must contain one JSON object per line. A stack trace is therefore several records; continuation lines are not automatically assigned the preceding line's timestamp. Compressed archives are not a supported log format.

The tools return `entries` with file names, byte offsets, parsed timestamps, levels and masked text. `lines` contains the same text for compatibility. Matching uses masked values, including JSON field masking, so hidden values cannot be recovered by guessing a filter. JSON records may be serialized to normalized JSON after masking.

## Last N records

Read the last 100 records of one selected file:

```json
{
  "connection": "server-logs",
  "file": "app.log",
  "tail": true,
  "maxLines": 100
}
```

Pass this to `ssh_logs_read`. The reader works backwards through the file until it finds the requested records or reaches a scan, response or time limit. It does not restrict the search to a single response-sized byte window. The effective policy may reduce `maxLines`; the result reports that effective value.

To obtain the latest matching records, use `ssh_logs_search` with `tail: true` and the desired filters. This is a snapshot query. Repeat it without a cursor for a fresh view; it is not a live subscription.

## Search and time ranges

Find warnings or errors matching either phrase, without case sensitivity, across two selected files:

```json
{
  "connection": "server-logs",
  "files": ["app.log", "worker.log"],
  "query": "timeout|connection refused",
  "matchMode": "regex",
  "caseSensitive": false,
  "levels": ["ERROR", "WARN"],
  "from": "-1h",
  "to": "now",
  "maxLines": 100
}
```

Pass this to `ssh_logs_search`. `matchMode` defaults to `literal`; regex uses [RE2/J syntax](https://github.com/google/re2j), including alternation, grouping, character classes and anchors. Backreferences and look-around are unsupported. Levels accept common aliases such as `WARN` and `WARNING`.

For every record in a period, omit `query` and `levels`:

```json
{
  "connection": "server-logs",
  "file": "app.log",
  "from": "2026-10-28T10:00:00+01:00",
  "to": "2026-10-28T11:00:00+01:00",
  "maxLines": 200
}
```

Time filtering uses the **event timestamp in the record**, not the file's modification time. `from` is inclusive and `to` is exclusive. Both accept ISO-8601 with a UTC offset or relative values such as `-15m`, `-1h`, `now-2d` and `now`. A supplied `from` defaults `to` to now. A supplied `to` without `from` selects the maximum allowed range ending at `to`. Omitting both disables time filtering.

Omit both `file` and `files` on `ssh_logs_search` to scan the whole configured allowlist. Entries follow selected-file order and physical byte order within a page, not a global timestamp sort. A tail scan visits files in reverse selection order and moves towards older file positions. Selecting multiple files does not establish which one contains the newest timestamps.

## Text and JSON timestamps

The collapsed **Log format** section controls parsing:

| Setting | Behavior |
| --- | --- |
| Format | `auto` treats lines beginning with `{` as JSON Lines and other lines as text; `text` and `jsonl` select an explicit format. Malformed JSON does not fall back to text timestamp parsing. |
| Time zone | Defaults to `UTC` for timestamps without an offset. Set an IANA zone such as `Europe/Madrid` when appropriate. Explicit timestamp offsets take precedence. |
| JSON timestamp field | Auto-detects `@timestamp`, `timestamp`, `time` or `ts`. An override can name an exact key, a dotted path or a JSON Pointer. |
| JSON level field | Auto-detects common level fields; an override supports the same path forms. |
| Timestamp pattern | An optional Java date-time pattern for the text prefix or a JSON string, such as `uuuu-MM-dd HH:mm:ss,SSS`. |
| Numeric timestamp unit | Numeric JSON timestamps use Unix milliseconds by default; select seconds explicitly for Unix seconds. |

Automatic text parsing recognizes common year-month-day dates with a time, optional fractional seconds and optional UTC offset, including ISO timestamps. A timestamp without a year is not guessed. Ambiguous or nonexistent local times during daylight-saving transitions are not guessed either; use timestamps with an explicit offset when possible.

With a time filter, records with missing or invalid timestamps are excluded and counted in `unparsedLines`. This includes stack-trace continuation lines without their own timestamp. Review the count and warnings when interpreting an empty result.

## Complete results and continuation

Source scanning and returned output have separate bounds. `scanBytes` defaults to 16 MiB per call and accepts 1–64 MiB. The smaller output limit remains enforced. When `scanComplete` is false, repeat the same request with its `nextCursor` to inspect the remaining positions, even when the current page has no matches. `truncationReason` distinguishes line, output, scan and time limits.

Relative time bounds remain anchored to the first call across pages. The cursor is signed and bound to the connection version, selected files and query. It expires after one hour and is invalidated by a connector or core restart. It checks the current file's snapshot and rejects detected file changes; do not silently resume an old byte offset after rotation or truncation. Files reached later in a multi-file scan are first snapshotted when opened, so this is not an atomic snapshot of the whole directory.

The maximum physical line size is bounded. Oversized lines are skipped with `skippedLines` and a warning instead of being passed off as complete records. A finished scan with skipped lines is not evidence that every source record matched or failed the filters. Output too small for one matching record produces an explicit error. Use the legacy `offset` only for single-file forward reads; cursors provide the stronger continuation checks.
