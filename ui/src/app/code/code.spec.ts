import { TestBed } from '@angular/core/testing';
import { ActionSegment } from '../core/api/approvals.api';
import { textSegments, toLines } from './code-lines';
import { GwDiff, diffRows } from './diff';
import { highlightLine } from './highlight';
import { confusableTitle, inspectPlain, invisibleTitle, isInvisible } from './unicode';

const text = (lines: ReturnType<typeof toLines>) => lines.map((l) => l.map((p) => p.text).join('')).join('\n');
const classes = (s: string, lang: string) => highlightLine(s, lang).tokens.map((t) => [t.cls, t.text]);

describe('code: exact action segments (ARCHITECTURE.md §8.3)', () => {
  const segments: ActionSegment[] = [
    { t: 'text', v: "UPDATE orders SET status = 'cancelled'\nWHERE id = 48213" },
    { t: 'invisible', v: '‹U+202E›' },
    { t: 'text', v: ';\n-- note ' },
    { t: 'confusable', v: 'о' },
    { t: 'text', v: 'rders' },
  ];

  it('the painted text is exactly the segments: highlighting changes no character', () => {
    const lines = toLines(segments, 'sql');
    expect(text(lines)).toBe(segments.map((s) => s.v).join(''));
    expect(lines).toHaveLength(3);
  });

  it('boxes invisible and confusable characters with a title naming them', () => {
    const parts = toLines(segments, 'sql').flat();
    const inv = parts.find((p) => p.cls === 'gw-tok-invisible')!;
    expect(inv.text).toBe('‹U+202E›');
    expect(inv.title).toBe('U+202E right-to-left override');
    const conf = parts.find((p) => p.cls === 'gw-tok-confusable')!;
    expect(conf.title).toBe('U+043E Cyrillic small letter o');
  });

  it('keeps highlighting invariant for every language and plain text', () => {
    const samples: [string, string][] = [
      ['sql', "SELECT id, status FROM orders WHERE created_at > now() - interval '1 hour' /* c\nmore */ LIMIT 50"],
      ['json', '{\n  "sql": "UPDATE …",\n  "n": -1.5e3,\n  "ok": true,\n  "secret": "‹masked›"\n}'],
      ['shell', 'kubectl -n app-dev rollout restart deploy/api | tee out && echo "$USER"'],
      ['yaml', 'apiVersion: apps/v1\nkind: Deployment # x\nspec:\n  replicas: 5'],
      ['http', 'POST https://billing.stage.acme.dev/v1/refunds HTTP/1.1\nContent-Type: application/json'],
      ['', 'plain ‹masked› text'],
    ];
    for (const [lang, src] of samples) expect(text(toLines(textSegments(src), lang))).toBe(src);
  });

  it('colours like the kit: SQL keywords, strings, numbers, functions, comments', () => {
    expect(classes("SELECT count(*) FROM t WHERE a = 'x' -- why", 'sql')).toEqual([
      ['keyword', 'SELECT'],
      [null, ' '],
      ['function', 'count'],
      ['punctuation', '(*)'],
      [null, ' '],
      ['keyword', 'FROM'],
      [null, ' t '],
      ['keyword', 'WHERE'],
      [null, ' a '],
      ['punctuation', '='],
      [null, ' '],
      ['string', "'x'"],
      [null, ' '],
      ['comment', '-- why'],
    ]);
  });

  it('carries a SQL block comment across lines', () => {
    const first = highlightLine('SELECT /* start', 'sql');
    const second = highlightLine('still comment */ 1', 'sql', first.state);
    expect(second.tokens[0]).toEqual({ cls: 'comment', text: 'still comment */' });
    expect(second.tokens.at(-1)).toEqual({ cls: 'number', text: '1' });
  });

  it('JSON: keys are properties, masked values are secrets', () => {
    const tokens = highlightLine('"customer_email": "‹masked›",', 'json').tokens;
    expect(tokens[0]).toEqual({ cls: 'property', text: '"customer_email"' });
    expect(tokens).toContainEqual({ cls: 'secret', text: '"‹masked›"' });
  });

  it('JSON: a ‹token› placeholder inside a longer string is the only secret part (manual setup snippets)', () => {
    const tokens = highlightLine('"Authorization": "Bearer ‹token›"', 'json').tokens;
    expect(tokens).toContainEqual({ cls: 'string', text: '"Bearer ' });
    expect(tokens).toContainEqual({ cls: 'secret', text: '‹token›' });
    expect(highlightLine('"token": "‹token›"', 'json').tokens).toContainEqual({ cls: 'secret', text: '"‹token›"' });
    expect(highlightLine('-H "Authorization: Bearer ‹token›"', 'shell').tokens).toContainEqual({ cls: 'secret', text: '‹token›' });
  });

  it('shell: the command word of each pipeline stage is a function', () => {
    const tokens = highlightLine('kubectl get pods | grep api', 'shell').tokens;
    expect(tokens.filter((t) => t.cls === 'function').map((t) => t.text)).toEqual(['kubectl', 'grep']);
  });
});

describe('code: unicode names and escapes', () => {
  it('names escapes the way the server writes them', () => {
    expect(invisibleTitle('‹U+200B›')).toBe('U+200B zero width space');
    expect(invisibleTitle('‹U+0007›')).toBe('U+0007 control character');
    expect(confusableTitle('Α')).toBe('U+0391 Greek capital letter ALPHA');
  });

  it('escapes what a diff line hides: bidi, zero width, tabs, trailing spaces', () => {
    expect(inspectPlain('a\u202Eb\tc  ')).toEqual([
      { t: 'text', v: 'a' },
      { t: 'invisible', v: '‹U+202E›' },
      { t: 'text', v: 'b' },
      { t: 'invisible', v: '‹U+0009›' },
      { t: 'text', v: 'c' },
      { t: 'invisible', v: '‹U+0020›' },
      { t: 'invisible', v: '‹U+0020›' },
    ]);
    expect(inspectPlain('plain text')).toEqual([{ t: 'text', v: 'plain text' }]);
  });

  it('flags what ActionInspector.invisible() flags: tag characters, Hangul fillers, non-ASCII spaces', () => {
    // U+E0041 hides an "A" (ASCII smuggling), U+3164 is a letter by category but blank, U+00A0 / U+202F look like
    // spaces but are not U+0020.
    expect(inspectPlain('x\u{E0041}y')).toEqual([
      { t: 'text', v: 'x' },
      { t: 'invisible', v: '‹U+E0041›' },
      { t: 'text', v: 'y' },
    ]);
    expect(inspectPlain('a\u3164b').map((p) => p.t)).toEqual(['text', 'invisible', 'text']);
    expect(inspectPlain('rm\u00A0-rf').filter((p) => p.t === 'invisible')).toEqual([{ t: 'invisible', v: '‹U+00A0›' }]);
    expect(inspectPlain('1\u202F000')).toContainEqual({ t: 'invisible', v: '‹U+202F›' });
    expect(invisibleTitle('‹U+E0041›')).toBe('U+E0041 tag character A');
    expect(invisibleTitle('‹U+3164›')).toBe('U+3164 Hangul filler');
    expect(invisibleTitle('‹U+202F›')).toBe('U+202F narrow no-break space');
    for (const cp of [0x0, 0x7, 0x9, 0x7f, 0x85, 0xad, 0x61c, 0x115f, 0x1160, 0x180e, 0x2000, 0x200b, 0x2028, 0x2029,
      0x202e, 0x2060, 0x2064, 0x2066, 0x3000, 0x3164, 0xfeff, 0xffa0, 0xe0001, 0xe007f])
      expect(isInvisible(cp), cp.toString(16)).toBe(true);
    for (const cp of [0x0a, 0x20, 0x41, 0x430, 0x2010, 0x4e00]) expect(isInvisible(cp), cp.toString(16)).toBe(false);
  });

  it('boxes every non-ASCII letter of text the server did not segment', () => {
    expect(inspectPlain('registry.acme.dev/\u0430pi:1.43.0')).toEqual([
      { t: 'text', v: 'registry.acme.dev/' },
      { t: 'confusable', v: '\u0430' },
      { t: 'text', v: 'pi:1.43.0' },
    ]);
    expect(confusableTitle('\u0430')).toBe('U+0430 Cyrillic small letter a');
    // A line break is never escaped, \r\n included; a lone \r is.
    expect(inspectPlain('a\r\nb\rc').map((p) => p.v)).toEqual(['a\r\nb', '‹U+000D›', 'c']);
  });
});

describe('code: diff rows from server hunks', () => {
  it('numbers both sides from the hunk header', () => {
    const rows = diffRows([
      {
        header: '@@ -4,3 +4,4 @@',
        lines: [
          { t: 'context', v: 'a' },
          { t: 'del', v: 'b' },
          { t: 'add', v: 'B' },
          { t: 'add', v: 'C' },
          { t: 'context', v: 'd' },
        ],
      },
    ]);
    expect(rows.map((r) => [r.kind, r.oldNo, r.newNo])).toEqual([
      ['hunk', null, null],
      ['context', 4, 4],
      ['del', 5, null],
      ['add', null, 5],
      ['add', null, 6],
      ['context', 6, 7],
    ]);
  });

  it('escapes invisible characters in diff lines', () => {
    const rows = diffRows([{ header: '@@ spec @@', lines: [{ t: 'add', v: 'replicas: 5\u200B' }] }]);
    expect(rows[1].parts.some((p) => p.cls === 'gw-tok-invisible' && p.v === '‹U+200B›')).toBe(true);
    expect(rows[1].oldNo).toBeNull();
    expect(rows[1].newNo).toBe(1);
  });

  it('boxes a look-alike letter and a tag character in a + line without server segments', () => {
    const rows = diffRows([
      { header: '@@ -1 +1 @@', lines: [{ t: 'add', v: 'image: registry.acme.dev/\u0430pi:1.43.0\u{E0041}' }] },
    ]);
    expect(rows[1].parts.filter((p) => p.cls).map((p) => [p.cls, p.v, p.title])).toEqual([
      ['gw-tok-confusable', '\u0430', 'U+0430 Cyrillic small letter a'],
      ['gw-tok-invisible', '‹U+E0041›', 'U+E0041 tag character A'],
    ]);
  });

  // The server's ApprovalDtos.diff cut of a k8s_apply action, and ActionInspector's segments of the same text.
  const hunks = [
    {
      header: '@@ -21,2 +21,2 @@ spec:',
      lines: [
        { t: 'context' as const, v: '        - name: api' },
        { t: 'del' as const, v: '          image: registry.acme.dev/api:1.42.0' },
        { t: 'add' as const, v: '          image: registry.acme.dev/\u0430pi:1.43.0' },
        { t: 'add' as const, v: '          note: "привет"' },
      ],
    },
  ];
  const serverSegments: ActionSegment[] = [
    { t: 'text', v: '--- a/deploy.yaml\n+++ b/deploy.yaml\n@@ -21,2 +21,2 @@ spec:\n         - name: api\n-          image: registry.acme.dev/api:1.42.0\n+          image: registry.acme.dev/' },
    { t: 'confusable', v: '\u0430' },
    { t: 'text', v: 'pi:1.43.0\n+          note: "привет"\n' },
  ];

  it('lays the server segments onto the lines: its marks, no more and no fewer', () => {
    const rows = diffRows(hunks, serverSegments);
    expect(rows.map((r) => [r.kind, r.oldNo, r.newNo])).toEqual([
      ['hunk', null, null],
      ['context', 21, 21],
      ['del', 22, null],
      ['add', null, 22],
      ['add', null, 23],
    ]);
    const marks = rows.flatMap((r) => r.parts.filter((p) => p.cls));
    expect(marks).toEqual([{ cls: 'gw-tok-confusable', v: '\u0430', title: 'U+0430 Cyrillic small letter a' }]);
    // The letters inside the quoted Unicode example are a string literal for the server: not boxed, as the warning doesn't count them.
    expect(rows[4].parts).toEqual([{ cls: null, v: '          note: "привет"', title: null }]);
    expect(serverSegments.filter((s) => s.t !== 'text')).toHaveLength(marks.length);
  });

  it('shows a file header only when the server flagged a character in it', () => {
    const flagged: ActionSegment[] = [
      { t: 'text', v: '--- a/deploy.yaml' },
      { t: 'invisible', v: '‹U+202E›' },
      { t: 'text', v: '\n' },
      ...serverSegments.map((s, i) => (i === 0 ? { t: 'text' as const, v: s.v.slice(s.v.indexOf('+++')) } : s)),
    ];
    const rows = diffRows(hunks, flagged);
    expect(rows[0].kind).toBe('meta');
    expect(rows[0].parts.map((p) => p.v)).toEqual(['--- a/deploy.yaml', '‹U+202E›']);
    expect(rows.filter((r) => r.kind === 'meta')).toHaveLength(1);
  });

  it('inspects each line itself when the segments do not spell the hunks', () => {
    const rows = diffRows(hunks, [{ t: 'text', v: 'something else' }]);
    expect(rows).toEqual(diffRows(hunks));
    // Without the whole text there's no literal: every non-ASCII letter is boxed.
    expect(rows[4].parts.filter((p) => p.cls === 'gw-tok-confusable')).toHaveLength(6);
  });
});

describe('code: <gw-diff> paints the marks', () => {
  afterEach(() => (document.body.innerHTML = ''));

  it('boxes a look-alike letter of a + line with its name, next to escaped invisible characters', async () => {
    const fixture = TestBed.createComponent(GwDiff);
    fixture.componentRef.setInput('diff', {
      added: 1,
      removed: 0,
      hunks: [{ header: '@@ -1 +1 @@', lines: [{ t: 'add', v: 'image: registry.acme.dev/\u0430pi:1.43.0\u3164' }] }],
    });
    fixture.componentRef.setInput('describedBy', 'w1');
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const conf = el.querySelector('.gw-diff__line--add .gw-tok-confusable')!;
    expect(conf.textContent).toBe('\u0430');
    expect(conf.getAttribute('title')).toBe('U+0430 Cyrillic small letter a');
    expect(el.querySelector('.gw-diff__line--add .gw-tok-invisible')!.textContent).toBe('‹U+3164›');
    expect(el.querySelector('.gw-diff__line--add')!.textContent).not.toMatch(/\u3164/);
    expect(el.querySelector('.gw-diff__body')!.getAttribute('aria-describedby')).toBe('w1');
  });
});
