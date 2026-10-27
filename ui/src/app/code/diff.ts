import { ChangeDetectionStrategy, Component, booleanAttribute, computed, input } from '@angular/core';
import { ActionSegment, Diff, DiffHunk } from '../core/api/approvals.api';
import { confusableTitle, inspectPlain, invisibleTitle } from './unicode';

/** One painted run of a diff line: text, an invisible character's escape, or a look-alike letter (with its name). */
export interface DiffPart {
  readonly cls: 'gw-tok-invisible' | 'gw-tok-confusable' | null;
  readonly v: string;
  readonly title: string | null;
}

export interface DiffRow {
  /** `hunk` is an `@@ … @@` line; `meta` a file header line (`--- a/…`) the server flagged a character in. */
  readonly kind: 'hunk' | 'meta' | 'context' | 'add' | 'del';
  readonly oldNo: number | null;
  readonly newNo: number | null;
  readonly parts: readonly DiffPart[];
}

/**
 * Numbers the lines of server hunks from their `@@ -a,b +c,d @@` headers (context lines advance both sides, an added
 * line the new side, a removed line the old side) and marks what a person can't see or could misread.
 *
 * With the action's `segments` (an approval's exact action, cut by the server's `ActionInspector`) the marks are the
 * server's own, laid onto the lines — so the warning's count above the diff and the boxes in it are the same set.
 * Without them (a client config diff), or if the segments don't spell the hunks (another diff shape), each line is
 * inspected here with the same rules (`inspectPlain`).
 */
export function diffRows(hunks: readonly DiffHunk[], segments?: readonly ActionSegment[] | null): DiffRow[] {
  const mapped = segments?.length ? fromServer(hunks, segments) : null;
  if (mapped) return mapped;
  const rows: DiffRow[] = [];
  for (const h of hunks) {
    let { oldNo, newNo } = start(h.header);
    rows.push({ kind: 'hunk', oldNo: null, newNo: null, parts: paint(inspectPlain(h.header)) });
    for (const l of h.lines) {
      const parts = paint(inspectPlain(l.v));
      if (l.t === 'add') rows.push({ kind: 'add', oldNo: null, newNo: newNo++, parts });
      else if (l.t === 'del') rows.push({ kind: 'del', oldNo: oldNo++, newNo: null, parts });
      else rows.push({ kind: 'context', oldNo: oldNo++, newNo: newNo++, parts });
    }
  }
  return rows;
}

function start(header: string): { oldNo: number; newNo: number } {
  const m = /@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(header);
  return { oldNo: m ? Number(m[1]) : 1, newNo: m ? Number(m[2]) : 1 };
}

function paint(segments: readonly ActionSegment[]): DiffPart[] {
  return segments.map((s) =>
    s.t === 'invisible'
      ? { cls: 'gw-tok-invisible', v: s.v, title: invisibleTitle(s.v) }
      : s.t === 'confusable'
        ? { cls: 'gw-tok-confusable', v: s.v, title: confusableTitle(s.v) }
        : { cls: null, v: s.v, title: null },
  );
}

/** One character of the original text and how the server segmented it. */
interface Atom {
  readonly raw: string;
  readonly seg: ActionSegment;
}

/**
 * Lays the server's segments of the whole diff text onto its lines, the way `ApprovalDtos.diff` cut the hunks: lines
 * split on `\n`, those before the first `@@` are file headers, then `+` / `-` / ` ` markers are dropped and `\` lines
 * left out. Returns null unless the rebuilt lines spell the server's hunks exactly, character for character.
 */
function fromServer(hunks: readonly DiffHunk[], segments: readonly ActionSegment[]): DiffRow[] | null {
  const lines: Atom[][] = [[]];
  for (const s of segments) {
    if (s.t === 'text') {
      for (const ch of s.v) {
        if (ch === '\n') lines.push([]);
        else lines[lines.length - 1].push({ raw: ch, seg: { t: 'text', v: ch } });
      }
    } else if (s.t === 'invisible') {
      const m = /^‹U\+([0-9A-F]{4,6})›$/.exec(s.v);
      if (!m) return null;
      lines[lines.length - 1].push({ raw: String.fromCodePoint(parseInt(m[1], 16)), seg: s });
    } else {
      lines[lines.length - 1].push({ raw: s.v, seg: s });
    }
  }
  const text = (atoms: readonly Atom[]) => atoms.map((a) => a.raw).join('');

  const rows: DiffRow[] = [];
  const meta: Atom[][] = [];
  // Java's split("\n") drops trailing empty strings: empty lines at the very end are not diff lines.
  let last = lines.length - 1;
  while (last >= 0 && !lines[last].length) last--;
  let hunk = -1;
  let line = 0;
  let oldNo = 1;
  let newNo = 1;
  for (let i = 0; i <= last; i++) {
    const atoms = lines[i];
    const raw = text(atoms);
    if (raw.startsWith('@@')) {
      if (hunk >= 0 && line !== hunks[hunk].lines.length) return null;
      hunk++;
      line = 0;
      if (hunk >= hunks.length || hunks[hunk].header !== raw) return null;
      ({ oldNo, newNo } = start(raw));
      rows.push({ kind: 'hunk', oldNo: null, newNo: null, parts: paint(merge(atoms)) });
    } else if (hunk < 0) {
      meta.push(atoms);
    } else if (!raw.startsWith('\\')) {
      const expected = hunks[hunk].lines[line++];
      const t = raw.startsWith('+') ? 'add' : raw.startsWith('-') ? 'del' : 'context';
      // The server drops one UTF-16 unit as the marker; a line that starts with an astral character doesn't map.
      if (atoms.length && atoms[0].raw.length !== 1) return null;
      const body = atoms.slice(1);
      if (!expected || expected.t !== t || expected.v !== text(body)) return null;
      // A marker the server flagged (a context line that starts with U+202E) stays on screen: it is in the count.
      const parts = paint(merge(atoms.length && atoms[0].seg.t !== 'text' ? atoms : body));
      if (t === 'add') rows.push({ kind: 'add', oldNo: null, newNo: newNo++, parts });
      else if (t === 'del') rows.push({ kind: 'del', oldNo: oldNo++, newNo: null, parts });
      else rows.push({ kind: 'context', oldNo: oldNo++, newNo: newNo++, parts });
    }
  }
  if (hunk !== hunks.length - 1 || (hunk >= 0 && line !== hunks[hunk].lines.length)) return null;
  // File headers aren't part of the kit's diff, but a character the server flagged in one is counted in the warning,
  // so such a header is shown (and only then): every mark the warning promises is on screen.
  const flagged = meta.filter((atoms) => atoms.some((a) => a.seg.t !== 'text'));
  return [...flagged.map((atoms) => ({ kind: 'meta' as const, oldNo: null, newNo: null, parts: paint(merge(atoms)) })), ...rows];
}

/** Joins neighbouring text characters back into runs. */
function merge(atoms: readonly Atom[]): ActionSegment[] {
  const out: ActionSegment[] = [];
  for (const a of atoms) {
    const last = out[out.length - 1];
    if (a.seg.t === 'text' && last?.t === 'text') out[out.length - 1] = { t: 'text', v: last.v + a.raw };
    else out.push(a.seg.t === 'text' ? { t: 'text', v: a.raw } : a.seg);
  }
  return out;
}

/**
 * `<gw-diff>`: the kit's unified diff from the server's hunks (client config changes, `k8s_apply`). Markers `+` / `−`
 * are text and screen readers hear "Added: " / "Removed: ", so the change reads in greyscale, high contrast and forced
 * colors. Invisible characters are escaped (`gw-tok-invisible`) and look-alike letters boxed (`gw-tok-confusable`),
 * as in the exact action; for an approval pass the action's `segments` so the marks are the server's.
 */
@Component({
  selector: 'gw-diff',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-diff', '[class.gw-diff--wrap]': 'wrap()' },
  template: `
    @if (filename() || summary()) {
      <div class="gw-diff__header">
        @if (filename()) {
          <span class="gw-diff__filename">{{ filename() }}</span>
        }
        @if (summary()) {
          <span class="gw-diff__summary">{{ summary() }}</span>
        }
      </div>
    }
    <div class="gw-diff__body" tabindex="0" role="region" [attr.aria-label]="regionLabel()" [attr.aria-describedby]="describedBy()">
      @for (r of rows(); track $index) {
        @if (r.kind === 'hunk' || r.kind === 'meta') {
          <div class="gw-diff__line gw-diff__line--hunk"><span class="gw-diff__text">@for (p of r.parts; track $index) {@if (p.cls) {<span [class]="p.cls" [attr.title]="p.title">{{ p.v }}</span>} @else {{{ p.v }}}}</span></div>
        } @else {
          <div [class]="'gw-diff__line gw-diff__line--' + r.kind">
            <span class="gw-diff__gutter" aria-hidden="true">{{ r.oldNo ?? '' }}</span><span class="gw-diff__gutter" aria-hidden="true">{{ r.newNo ?? '' }}</span><span class="gw-diff__marker" aria-hidden="true">{{ r.kind === 'add' ? '+' : r.kind === 'del' ? '−' : '' }}</span>@if (r.kind === 'add') {<span class="gw-sr-only">Added: </span>} @else if (r.kind === 'del') {<span class="gw-sr-only">Removed: </span>}
            <span class="gw-diff__text">@for (p of r.parts; track $index) {@if (p.cls) {<span [class]="p.cls" [attr.title]="p.title">{{ p.v }}</span>} @else {{{ p.v }}}}</span>
          </div>
        }
      }
    </div>
  `,
})
export class GwDiff {
  readonly diff = input.required<Diff>();
  /** The server's segments of the whole diff text (an approval's exact action): its marks, not the UI's. */
  readonly segments = input<readonly ActionSegment[] | null>(null);
  readonly filename = input<string | null>(null);
  /** "6 lines added · backup kept"; default from the diff's counts. */
  readonly summaryInput = input<string | null>(null, { alias: 'summary' });
  readonly regionLabel = input('Changes');
  /** The warning that counts the hidden characters, read with the region. */
  readonly describedBy = input<string | null>(null);
  readonly wrap = input(false, { transform: booleanAttribute });

  protected readonly rows = computed(() => diffRows(this.diff().hunks, this.segments()));
  protected readonly summary = computed(() => this.summaryInput() ?? defaultSummary(this.diff()));
}

function defaultSummary(d: Diff): string {
  const parts: string[] = [];
  if (d.added) parts.push(`${d.added} ${d.added === 1 ? 'line' : 'lines'} added`);
  if (d.removed) parts.push(`${d.removed} ${d.removed === 1 ? 'line' : 'lines'} removed`);
  return parts.join(' · ');
}
