# SSH log files

The **SSH log files** connection reads explicitly selected remote files over SFTP. Configure it in **Connections → Add connection → SSH log files** with a host, user, password or local private-key path, and trusted server key. Browse the remote folders, select a root and allow individual files. Selecting a folder alone does not grant access to every file in it. New rotated filenames must be selected explicitly.

For private-key authentication, **Choose file** opens the desktop application's native file dialog and fills in the absolute path. The picker does not read or upload the key. You can also type an absolute path or a path beginning with `~/`; browser development uses manual path entry because browsers cannot expose a local file's absolute path. The connector reads the key locally when it authenticates, after verifying the server's host key.

The account needs SFTP and read access to those files. No shell commands run. Symbolic links and nonregular files are rejected. SSH policies control credential methods and output size; Limits controls line counts, response size and deadlines; Logs controls the maximum query period.

Automatic health checks verify SSH sign-in, SFTP availability and the selected root folder. Their cost does not grow with the number of selected files. **Test connection** additionally checks read access to every selected file; each log tool also checks the file it uses. A healthy connection therefore does not guarantee that every selected file still exists or remains readable after rotation or a permission change.

## Tools

| Tool | Use |
| --- | --- |
| `ssh_logs_list` | List selected files and their sizes, optionally filtering by file name. |
| `ssh_logs_read` | Read one file, including its last N records or records in a time range. |
| `ssh_logs_search` | Filter one, several, or all allowed files by literal text, regex, level and event time. |

Each physical UTF-8 line is one record. JSON Lines must contain one JSON object per line. A stack trace is therefore several records; continuation lines are not automatically assigned the preceding line's timestamp. Compressed archives are not a supported log format.

The read and search tools return `entries` with file names, byte offsets, parsed timestamps, levels and masked text. `lines` contains the same text for compatibility. Content matching uses masked values, including JSON field masking, so hidden values cannot be recovered by guessing a filter. JSON records may be serialized to normalized JSON after masking.

## List files and search by name

Pass only the connection to `ssh_logs_list` to list the selected files:

```json
{
  "connection": "server-logs"
}
```

To find selected files whose names contain `worker`, ignoring case:

```json
{
  "connection": "server-logs",
  "query": "worker",
  "caseSensitive": false
}
```

`query` matches the full root-relative file name, including subfolders such as `services/worker.log`. It accepts at most 1,024 characters. Matching defaults to a case-sensitive literal substring; an empty or omitted query matches all selected files. Set `matchMode` to `regex` for RE2 matching within the name. For example, `"query": "^services/.*[.]log$"` with `"matchMode": "regex"` finds selected `.log` files below `services/`. Anchors constrain the full name; backreferences and look-around are unsupported.

The result contains `root`, a `files` array of `{ "file": "services/worker.log", "size": 1234 }` objects, and `truncated`. Sizes are in bytes, and names retain the configured selection order. Use a returned `file` name with `ssh_logs_read` or `ssh_logs_search` to inspect its contents. No matches returns an empty `files` array.

Only explicitly selected files are eligible. Name filtering runs before checking remote file metadata and applying row and response-size limits. If `truncated` is true, narrow the name query to reduce the result. Listing does not read log contents or discover unselected files.

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
