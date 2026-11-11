import { TestBed } from '@angular/core/testing';
import { ActionSegment } from '../core/api/approvals.api';
import { provideKitIcons } from '../kit/icons';
import { GwCodeBlock } from './code-block';
import { CodeLine, textSegments, toLines } from './code-lines';
import { SqlDialect, sqlDialectOf } from './sql-lexer';

/**
 * The colours of an exact action never make text that runs look like text that doesn't (audit M-R2): a character is a
 * string or a comment only where the server (the shell, the YAML reader) reads it so, across lines and whatever the
 * dialect.
 */

const text = (lines: readonly CodeLine[]) => lines.map((l) => l.map((p) => p.text).join('')).join('\n');

/** The classes the characters of `needle` (its first occurrence on the line) are painted with, each once. */
function painted(line: CodeLine, needle: string): (string | null)[] {
  const chars: (string | null)[] = [];
  for (const p of line) for (let k = 0; k < p.text.length; k++) chars.push(p.cls);
  const at = line.map((p) => p.text).join('').indexOf(needle);
  expect(at, `"${needle}" on the line`).toBeGreaterThanOrEqual(0);
  return [...new Set(chars.slice(at, at + needle.length))];
}

const inert = (classes: readonly (string | null)[]) => classes.some((c) => c === 'gw-tok-string' || c === 'gw-tok-comment');

const sql = (source: string) => toLines(textSegments(source), 'sql');
const sqlOn = (source: string, dialect: SqlDialect | null) => toLines(textSegments(source), 'sql', dialect);
const shell = (source: string) => toLines(textSegments(source), 'sh');
const yaml = (source: string) => toLines(textSegments(source), 'yaml');

describe('SQL colours follow the server across lines', () => {
  it('a string that ends on a later line ends there: what follows it is code', () => {
    const lines = sql("UPDATE notes SET body = 'first line\nsecond line' WHERE id = 7");
    expect(painted(lines[0], "'first line")).toEqual(['gw-tok-string']);
    expect(painted(lines[1], "second line'")).toEqual(['gw-tok-string']);
    expect(painted(lines[1], 'WHERE')).toEqual(['gw-tok-keyword']);
    expect(painted(lines[1], '7')).toEqual(['gw-tok-number']);
  });

  it('a quoted name that ends on a later line ends there too', () => {
    const lines = sql('SELECT "a\nb" FROM t');
    expect(painted(lines[1], 'FROM')).toEqual(['gw-tok-keyword']);
  });

  it('a PostgreSQL dollar quote runs over lines as one string', () => {
    const lines = sqlOn("SELECT $body$ it's\nstill $body$, 1", 'postgres');
    expect(painted(lines[0], "$body$ it's")).toEqual(['gw-tok-string']);
    expect(painted(lines[1], 'still $body$')).toEqual(['gw-tok-string']);
    expect(painted(lines[1], '1')).toEqual(['gw-tok-number']);
  });

  it('nested PostgreSQL block comments end where the server ends them', () => {
    const lines = sqlOn('SELECT /* a /* b */ still a\ncomment */ 1', 'postgres');
    expect(painted(lines[0], 'still a')).toEqual(['gw-tok-comment']);
    expect(painted(lines[1], '1')).toEqual(['gw-tok-number']);
    // MySQL's comments don't nest: the first */ ends it, and what follows runs.
    expect(painted(sqlOn('SELECT /* a /* b */ c', 'mysql')[0], 'c')).toEqual([null]);
  });
});

describe('SQL comment and literal rules are the dialect’s', () => {
  it('# is a comment on MySQL and ClickHouse, an operator elsewhere; unknown: code', () => {
    expect(painted(sqlOn('SELECT 3 # 5', 'mysql')[0], '# 5')).toEqual(['gw-tok-comment']);
    expect(painted(sqlOn('SELECT 3 # 5', 'clickhouse')[0], '# 5')).toEqual(['gw-tok-comment']);
    expect(inert(painted(sqlOn('SELECT 3 # 5', 'postgres')[0], '# 5'))).toBe(false);
    expect(inert(painted(sql('SELECT 3 # 5')[0], '# 5'))).toBe(false);
  });

  it('-- without a space after it is no comment on MySQL; unknown: code', () => {
    expect(painted(sqlOn('SELECT 3 --5', 'postgres')[0], '--5')).toEqual(['gw-tok-comment']);
    expect(inert(painted(sqlOn('SELECT 3 --5', 'mysql')[0], '--5'))).toBe(false);
    expect(inert(painted(sql('SELECT 3 --5')[0], '--5'))).toBe(false);
    // With a space every dialect agrees.
    expect(painted(sql('SELECT 3 -- 5')[0], '-- 5')).toEqual(['gw-tok-comment']);
  });

  it('MySQL executable comments and optimizer hints are code', () => {
    expect(inert(painted(sqlOn('SELECT /*!50000 1 */ 2', 'mysql')[0], '/*!50000 1 */'))).toBe(false);
    expect(inert(painted(sqlOn('SELECT /*+ NO_INDEX(t) */ 2', 'mysql')[0], '/*+ NO_INDEX(t) */'))).toBe(false);
    expect(inert(painted(sql('SELECT /*!50000 1 */ 2')[0], '/*!50000 1 */'))).toBe(false);
    expect(inert(painted(sqlOn('SELECT /*+ hint */ 2', 'postgres')[0], '/*+ hint */'))).toBe(false);
    expect(inert(painted(sqlOn('SELECT --+ hint\n2 FROM dual', 'oracle')[0], '--+ hint'))).toBe(false);
    // A plain comment stays a comment.
    expect(painted(sqlOn('SELECT /* note */ 2', 'mysql')[0], '/* note */')).toEqual(['gw-tok-comment']);
  });

  it('a backslash escapes a quote only where the dialect says so; unknown: code where they differ', () => {
    const source = "SELECT 'it\\'s', 2";
    expect(painted(sqlOn(source, 'mysql')[0], "'it\\'s'")).toEqual(['gw-tok-string']);
    expect(painted(sqlOn(source, 'mysql')[0], '2')).toEqual(['gw-tok-number']);
    expect(painted(sqlOn(source, 'postgres')[0], "'it\\'")).toEqual(['gw-tok-string']);
    expect(painted(sqlOn(source, 'postgres')[0], 's')).toEqual([null]);
    expect(painted(sql(source)[0], '2')).toEqual(['gw-tok-number']);
    // PostgreSQL's E'…' takes backslash escapes.
    expect(painted(sqlOn("SELECT E'it\\'s', 2", 'postgres')[0], '2')).toEqual(['gw-tok-number']);
  });

  it('Oracle alternative quotes and SQL Server brackets hold what would start a comment elsewhere', () => {
    const q = sqlOn("SELECT q'[it's -- here]' FROM dual", 'oracle')[0];
    expect(painted(q, "q'[it's -- here]'")).toEqual(['gw-tok-string']);
    expect(painted(q, 'FROM')).toEqual(['gw-tok-keyword']);
    const b = sqlOn('SELECT [a--b] FROM t', 'sqlserver')[0];
    expect(painted(b, 'FROM')).toEqual(['gw-tok-keyword']);
    // Unknown dialect: the models disagree after the quote, so all of it is code.
    expect(painted(sql("SELECT q'[it's -- here]' FROM dual")[0], 'FROM')).toEqual(['gw-tok-keyword']);
    expect(painted(sql('SELECT [a--b] FROM t')[0], 'FROM')).toEqual(['gw-tok-keyword']);
  });

  it('ClickHouse: // comments, heredocs, and code after what the model cannot follow', () => {
    expect(painted(sqlOn('SELECT 1 // c', 'clickhouse')[0], '// c')).toEqual(['gw-tok-comment']);
    expect(inert(painted(sql('SELECT 1 // c')[0], '// c'))).toBe(false);
    expect(painted(sqlOn("SELECT $x$ it's $x$, 1", 'clickhouse')[0], '1')).toEqual(['gw-tok-number']);
    const typographic = sqlOn("SELECT ‘a’ -- 'b'", 'clickhouse')[0];
    expect(inert(painted(typographic, "-- 'b'"))).toBe(false);
  });

  it('a dialect it has no model of takes nothing for a literal or a comment', () => {
    const line = sqlOn("SELECT 'a' -- b", 'snowflake' as SqlDialect)[0];
    expect(inert(painted(line, "'a' -- b"))).toBe(false);
  });

  it('maps connection types to dialects (MariaDB uses the MySQL type)', () => {
    expect(['postgres', 'mysql', 'mariadb', 'oracle', 'sqlserver', 'sqlite', 'clickhouse', 'redis', null].map(sqlDialectOf))
      .toEqual(['postgres', 'mysql', 'mysql', 'oracle', 'sqlserver', 'sqlite', 'clickhouse', null, null]);
  });

  it('SQLite: flat block comments, backtick names and brackets that end at the first ]', () => {
    // SQLite ends the comment at the first */: what follows runs.
    const flat = sqlOn('DELETE FROM t /* a /* b */ WHERE id = 1 -- */', 'sqlite')[0];
    expect(painted(flat, 'WHERE')).toEqual(['gw-tok-keyword']);
    const tick = sqlOn("SELECT `it's` FROM t WHERE a = 'x'", 'sqlite')[0];
    expect(painted(tick, 'FROM')).toEqual(['gw-tok-keyword']);
    const bracket = sqlOn('SELECT [a]] FROM t', 'sqlite')[0];
    expect(painted(bracket, 'FROM')).toEqual(['gw-tok-keyword']);
    expect(painted(sqlOn("SELECT 'a\\' FROM t", 'sqlite')[0], 'FROM')).toEqual(['gw-tok-keyword']);
  });
});

describe('SQL colours read the characters the server reads', () => {
  it('an escaped carriage return ends a line comment where the server ends it', () => {
    const segments: ActionSegment[] = [
      { t: 'text', v: 'SELECT 1 -- note' },
      { t: 'invisible', v: '‹U+000D›' },
      { t: 'text', v: 'DELETE FROM t' },
    ];
    const line = toLines(segments, 'sql', 'postgres')[0];
    expect(painted(line, 'DELETE')).toEqual(['gw-tok-keyword']);
    expect(line.find((p) => p.cls === 'gw-tok-invisible')?.text).toBe('‹U+000D›');
  });

  it('an invisible character or a look-alike letter inside a string leaves the string open', () => {
    const segments: ActionSegment[] = [
      { t: 'text', v: "SELECT 'a" },
      { t: 'invisible', v: '‹U+200B›' },
      { t: 'text', v: "b' -- x\nFROM t WHERE n = '" },
      { t: 'confusable', v: 'а' },
      { t: 'text', v: "'" },
    ];
    const lines = toLines(segments, 'sql');
    expect(text(lines)).toBe(segments.map((s) => s.v).join(''));
    expect(painted(lines[0], "b'")).toEqual(['gw-tok-string']);
    expect(painted(lines[0], '-- x')).toEqual(['gw-tok-comment']);
    expect(painted(lines[1], 'FROM')).toEqual(['gw-tok-keyword']);
    expect(lines[1].at(-1)).toEqual({ cls: 'gw-tok-string', text: "'", title: null });
  });

  it('one part per source line, whatever runs across the lines', () => {
    const source = "INSERT INTO t VALUES ('a\nb\nc');\n/* d\ne */ SELECT 1";
    const lines = sql(source);
    expect(lines).toHaveLength(5);
    expect(text(lines)).toBe(source);
    expect(painted(lines[2], "c'")).toEqual(['gw-tok-string']);
    expect(painted(lines[2], ');')).toEqual(['gw-tok-punctuation']);
    expect(painted(lines[4], 'SELECT')).toEqual(['gw-tok-keyword']);
  });
});

describe('shell colours stop where the line alone cannot tell', () => {
  it('a quote still open at the end of a line makes the rest plain', () => {
    const lines = shell("echo 'first\nsecond' ; rm -f /tmp/x");
    expect(painted(lines[0], "'first")).toEqual(['gw-tok-string']);
    expect(inert(painted(lines[1], 'rm -f /tmp/x'))).toBe(false);
  });

  it("$'…' takes backslash escapes", () => {
    const line = shell("echo $'a\\'b' ; ls -l")[0];
    expect(painted(line, "$'a\\'b'")).toEqual(['gw-tok-string']);
    expect(painted(line, 'ls')).toEqual(['gw-tok-function']);
  });

  it('an escaped quote opens no string, an escaped # starts no comment', () => {
    const line = shell("echo it\\'s \\#x ; ls")[0];
    expect(inert(painted(line, "\\'s \\#x ; ls"))).toBe(false);
    expect(painted(line, 'ls')).toEqual(['gw-tok-function']);
  });

  it('expansions inside double quotes are not painted as a string', () => {
    for (const source of ['echo "$(id)" ; ls', 'echo "${id}" ; ls', 'echo "`id`" ; ls', 'echo "$[id]" ; ls']) {
      const line = shell(source)[0];
      expect(inert(painted(line, 'id')), source).toBe(false);
      expect(inert(painted(line, 'ls')), source).toBe(false);
    }
    // A plain double-quoted string still is one.
    expect(painted(shell('echo "a b" ; ls')[0], '"a b"')).toEqual(['gw-tok-string']);
  });

  it('a # inside backquotes comments out nothing after them', () => {
    const line = shell('echo `true #` ; ls')[0];
    expect(painted(line, 'ls')).toEqual(['gw-tok-function']);
  });

  it('a here-document body is plain text up to its delimiter, then colouring goes on', () => {
    const lines = shell("cat <<EOF\n# not a comment $(date)\nit's\nEOF\nls # done");
    expect(inert(painted(lines[1], '# not a comment $(date)'))).toBe(false);
    expect(inert(painted(lines[2], "it's"))).toBe(false);
    expect(painted(lines[4], 'ls')).toEqual(['gw-tok-function']);
    expect(painted(lines[4], '# done')).toEqual(['gw-tok-comment']);
    // <<- strips the delimiter's leading tabs; a quoted delimiter works the same.
    const tabs = shell("cat <<-'END'\n\tbody # x\n\tEND\nls");
    expect(inert(painted(tabs[1], 'body # x'))).toBe(false);
    expect(painted(tabs[3], 'ls')).toEqual(['gw-tok-function']);
  });

  it('<< inside arithmetic is a shift, not a here-document; $$ takes no quote with it', () => {
    const lines = shell("echo $((1 << 2)) ; ls\necho done # c");
    expect(painted(lines[0], 'ls')).toEqual(['gw-tok-function']);
    expect(painted(lines[1], '# c')).toEqual(['gw-tok-comment']);
    const pid = shell("echo $$'a\\' ; ls")[0];
    // $$ is the process id: '…' after it is an ordinary quote, which the backslash doesn't escape.
    expect(painted(pid, "'a\\'")).toEqual(['gw-tok-string']);
    expect(painted(pid, 'ls')).toEqual(['gw-tok-function']);
  });

  it('a line continued by a backslash does not start a word', () => {
    const lines = shell('echo a\\\n#b ; ls');
    expect(inert(painted(lines[1], '#b ; ls'))).toBe(false);
  });

  it('keeps ordinary colours: commands, flags, strings and comments', () => {
    const line = shell("kubectl -n app get pods | grep 'api' # why")[0];
    expect(painted(line, 'kubectl')).toEqual(['gw-tok-function']);
    expect(painted(line, '-n')).toEqual(['gw-tok-property']);
    expect(painted(line, 'grep')).toEqual(['gw-tok-function']);
    expect(painted(line, "'api'")).toEqual(['gw-tok-string']);
    expect(painted(line, '# why')).toEqual(['gw-tok-comment']);
  });
});

describe('YAML: # is a comment only outside the value’s text', () => {
  it('not inside quotes, also in a flow sequence', () => {
    const line = yaml('command: ["sh", "-c", "echo a # b"]')[0];
    expect(painted(line, '"echo a # b"')).toEqual(['gw-tok-string']);
    expect(painted(line, '"]')).not.toContain('gw-tok-comment');
  });

  it('not inside a block scalar, which ends at the next line indented like its key', () => {
    const lines = yaml('script: |\n  #!/bin/sh\n  echo hi # there\nnext: 1');
    expect(painted(lines[1], '#!/bin/sh')).toEqual(['gw-tok-string']);
    expect(painted(lines[2], 'echo hi # there')).toEqual(['gw-tok-string']);
    expect(painted(lines[3], 'next')).toEqual(['gw-tok-property']);
    const item = yaml('args:\n  - |\n    # line\n  - x # y');
    expect(painted(item[2], '# line')).toEqual(['gw-tok-string']);
    expect(painted(item[3], '# y')).toEqual(['gw-tok-comment']);
    // Whatever the key looks like: an annotation with a slash, a quoted key.
    const annotation = yaml('  example.com/last-applied: |-\n    {"cmd": "a # b"}\n    # more\n  other: 1');
    expect(painted(annotation[1], '"a # b"}')).toEqual(['gw-tok-string']);
    expect(painted(annotation[2], '# more')).toEqual(['gw-tok-string']);
    expect(painted(yaml('"a key": >\n  text # here')[1], '# here')).toEqual(['gw-tok-string']);
  });

  it('not inside a quoted value that goes on over lines', () => {
    const lines = yaml('note: "first\n  # second" # third');
    expect(painted(lines[1], '# second"')).toEqual(['gw-tok-string']);
    expect(painted(lines[1], '# third')).toEqual(['gw-tok-comment']);
  });

  it('keeps ordinary colours: keys, numbers, comments, apostrophes in plain text', () => {
    const lines = yaml("replicas: 5 # scale\ndescription: don't # note\nname: 'a # b'");
    expect(painted(lines[0], 'replicas')).toEqual(['gw-tok-property']);
    expect(painted(lines[0], '5')).toEqual(['gw-tok-number']);
    expect(painted(lines[0], '# scale')).toEqual(['gw-tok-comment']);
    expect(painted(lines[1], '# note')).toEqual(['gw-tok-comment']);
    expect(painted(lines[2], "'a # b'")).toEqual(['gw-tok-string']);
  });

  it('TOML: strings, multi-line strings and comments', () => {
    const lines = toLines(textSegments('cmd = "a # b" # c\ntext = """\nnot # a comment\n"""'), 'toml');
    expect(painted(lines[0], '"a # b"')).toEqual(['gw-tok-string']);
    expect(painted(lines[0], '# c')).toEqual(['gw-tok-comment']);
    expect(painted(lines[2], 'not # a comment')).toEqual(['gw-tok-string']);
  });
});

describe('<gw-code-block [dialect]>', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideKitIcons()] }));
  afterEach(() => (document.body.innerHTML = ''));

  it('colours the action as the connection’s dialect reads it', async () => {
    const fixture = TestBed.createComponent(GwCodeBlock);
    fixture.componentRef.setInput('text', 'SELECT 3 # 5');
    fixture.componentRef.setInput('language', 'sql');
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.gw-tok-comment')).toBeNull();
    fixture.componentRef.setInput('dialect', 'mysql');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.querySelector('.gw-tok-comment')?.textContent).toBe('# 5');
    fixture.componentRef.setInput('dialect', 'postgres');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.querySelector('.gw-tok-comment')).toBeNull();
  });
});
