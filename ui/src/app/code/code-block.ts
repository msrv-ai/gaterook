import { ChangeDetectionStrategy, Component, booleanAttribute, computed, input } from '@angular/core';
import { ActionSegment } from '../core/api/approvals.api';
import { GwCopy } from '../kit/copy';
import { literalParts } from '../kit/elements/shared/literals';
import { GwIcon } from '../kit/icons';
import { textSegments, toLines } from './code-lines';

let nextId = 0;

/**
 * `<gw-code-block>`: the kit's `gw-code-block` for an exact action or any literal text (ARCHITECTURE.md §8.5). It
 * draws the server's segments (`ActionInspector`: text, `gw-tok-invisible` escapes, `gw-tok-confusable` letters)
 * and never re-parses or normalizes the action; syntax colour is decoration over the text runs. Copy copies what is
 * shown, escapes included. The body is a keyboard-scrollable named region.
 */
@Component({
  selector: 'gw-code-block',
  imports: [GwCopy, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-code-block',
    '[class.gw-code-block--numbered]': 'numbered()',
    '[class.gw-code-block--wrap]': 'wrap()',
    '[class.gw-code-block--scroll]': 'scroll()',
  },
  template: `
    @if (showHeader()) {
      <div class="gw-code-block__header">
        @if (lang()) {
          <span class="gw-code-block__lang">{{ lang() }}</span>
        }
        @if (filename()) {
          <span class="gw-code-block__filename" [attr.title]="filename()">@for (part of filenameParts(); track $index) {@if (part.code) {<span class="gw-mono">{{ part.text }}</span>} @else {{{ part.text }}}}</span>
        }
        @if (copy()) {
          <div class="gw-code-block__actions">
            <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" [gwCopyFrom]="'#' + preId()">
              <span class="gw-code-block__copy"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy<span class="gw-sr-only"> {{ copyLabel() }}</span></span>
              <span class="gw-code-block__copied"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Copied</span>
            </button>
          </div>
        }
      </div>
    }
    <div class="gw-code-block__body" tabindex="0" role="region" [attr.aria-label]="regionLabel()" [attr.aria-describedby]="describedBy()">
      <pre class="gw-code-block__pre" [id]="preId()"><code>@for (line of lines(); track $index) {@if (lineSpans()) {<span class="gw-code-block__line">@for (p of line; track $index) {@if (p.cls) {<span [class]="p.cls" [attr.title]="p.title">{{ p.text }}</span>} @else {{{ p.text }}}}</span>} @else {@for (p of line; track $index) {@if (p.cls) {<span [class]="p.cls" [attr.title]="p.title">{{ p.text }}</span>} @else {{{ p.text }}}}}@if (!$last) {{{ newline }}}}</code></pre>
    </div>
  `,
})
export class GwCodeBlock {
  /** The server's segments (exact action). */
  readonly segments = input<readonly ActionSegment[] | null>(null);
  /** Or plain text (a snippet, a config, a log). */
  readonly text = input<string | null>(null);
  /** For colour only: sql, json, sh, yaml, toml, http. */
  readonly language = input<string | null>(null);
  /** Header label (overline): "SQL", "JSON". */
  readonly lang = input<string | null>(null);
  /** Header context, mono: "sql_query · dev-db". */
  readonly filename = input<string | null>(null);
  /** Names in a sentence-like header set in mono: "Run as an admin on `app`, then use `gw_reader` as the user". */
  readonly filenameLiterals = input<readonly string[]>([]);
  readonly copy = input(true, { transform: booleanAttribute });
  /** The object of the Copy button for screen readers: "SQL query". */
  readonly copyLabel = input('code');
  readonly regionLabel = input('Code');
  readonly describedBy = input<string | null>(null);
  readonly numbered = input(false, { transform: booleanAttribute });
  readonly wrap = input(false, { transform: booleanAttribute });
  readonly scroll = input(false, { transform: booleanAttribute });
  /** Render each source line as a `gw-code-block__line`: the kit needs them for `--numbered` only. */
  readonly lineSpansInput = input<boolean | null>(null, { alias: 'lineSpans' });
  readonly id = input<string | null>(null);

  protected readonly newline = '\n';
  private readonly fallbackId = `gw-code-${++nextId}`;
  protected readonly preId = computed(() => this.id() ?? this.fallbackId);
  protected readonly lineSpans = computed(() => this.lineSpansInput() ?? this.numbered());
  protected readonly showHeader = computed(() => !!(this.lang() || this.filename() || this.copy()));
  protected readonly filenameParts = computed(() => literalParts(this.filename() ?? '', this.filenameLiterals()));
  protected readonly lines = computed(() => toLines(this.segments() ?? textSegments(this.text() ?? ''), this.language()));
}
