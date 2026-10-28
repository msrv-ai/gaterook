import { ChangeDetectionStrategy, Component, booleanAttribute, computed, input, linkedSignal } from '@angular/core';
import { ActionSegment } from '../core/api/approvals.api';
import { GwCopy } from '../kit/copy';
import { literalParts } from '../kit/elements/shared/literals';
import { GwIcon } from '../kit/icons';
import { textSegments, toLines } from './code-lines';
import { prettyJsonSegments } from './pretty-json';

let nextId = 0;

/**
 * `<gw-code-block>`: the kit's `gw-code-block` for an exact action or any literal text (ARCHITECTURE.md §8.5). It
 * draws the server's segments (`ActionInspector`: text, `gw-tok-invisible` escapes, `gw-tok-confusable` letters)
 * without changing their values. Opt-in JSON formatting changes structural whitespace only, with an Original view
 * of the server's segments. Copy copies the active view, escapes included. The body is a keyboard-scrollable region.
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
        @if (copy() || formatted()) {
          <div class="gw-code-block__actions">
            @if (formatted()) {
              <fieldset class="gw-segmented gw-segmented--sm">
                <legend class="gw-sr-only">Display format for {{ copyLabel() }}</legend>
                <label class="gw-segmented__option">
                  <input class="gw-segmented__input" type="radio" [name]="fallbackId + '-format'" value="pretty" [checked]="prettyView()" [attr.aria-controls]="preId()" (change)="prettyView.set(true)">
                  <span class="gw-segmented__label">Pretty</span>
                </label>
                <label class="gw-segmented__option">
                  <input class="gw-segmented__input" type="radio" [name]="fallbackId + '-format'" value="original" [checked]="!prettyView()" [attr.aria-controls]="preId()" (change)="prettyView.set(false)">
                  <span class="gw-segmented__label">Original</span>
                </label>
              </fieldset>
            }
            @if (copy()) {
              <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" [gwCopyFrom]="'#' + preId()">
                <span class="gw-code-block__copy"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy<span class="gw-sr-only"> {{ copyLabel() }}{{ showingPretty() ? ' (pretty JSON)' : '' }}</span></span>
                <span class="gw-code-block__copied"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Copied</span>
              </button>
            }
          </div>
        }
      </div>
    }
    <div class="gw-code-block__body" tabindex="0" role="region" [attr.aria-label]="regionLabel() + (showingPretty() ? ' (pretty JSON)' : '')" [attr.aria-describedby]="describedBy()">
      <pre class="gw-code-block__pre" [id]="preId()"><code>@for (line of lines(); track $index) {@if (lineSpans()) {<span class="gw-code-block__line">@for (p of line; track $index) {@if (p.cls) {<span [class]="p.cls" [attr.title]="p.title">{{ p.text }}</span>} @else {{{ p.text }}}}</span>} @else {@for (p of line; track $index) {@if (p.cls) {<span [class]="p.cls" [attr.title]="p.title">{{ p.text }}</span>} @else {{{ p.text }}}}}@if (!$last) {{{ newline }}}}</code></pre>
    </div>
  `,
})
export class GwCodeBlock {
  /** The server's segments (exact action). */
  readonly segments = input<readonly ActionSegment[] | null>(null);
  /** Or plain text (a snippet, a config, a log). */
  readonly text = input<string | null>(null);
  /** Syntax language: sql, json, sh, yaml, toml, http. Only JSON supports pretty formatting. */
  readonly language = input<string | null>(null);
  /** Offer whitespace-only Pretty / Original views for JSON objects and arrays. */
  readonly pretty = input(false, { transform: booleanAttribute });
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
  protected readonly fallbackId = `gw-code-${++nextId}`;
  protected readonly preId = computed(() => this.id() ?? this.fallbackId);
  protected readonly lineSpans = computed(() => this.lineSpansInput() ?? this.numbered());
  protected readonly showHeader = computed(() => !!(this.lang() || this.filename() || this.copy() || this.formatted()));
  protected readonly filenameParts = computed(() => literalParts(this.filename() ?? '', this.filenameLiterals()));
  private readonly sourceSegments = computed(() => this.segments() ?? textSegments(this.text() ?? ''));
  private readonly sourceText = computed(() => this.sourceSegments().map((s) => s.v).join(''));
  protected readonly prettyView = linkedSignal({ source: this.sourceText, computation: () => true });
  protected readonly formatted = computed(() => {
    if (!this.pretty() || this.language()?.toLowerCase() !== 'json') return null;
    const formatted = prettyJsonSegments(this.sourceSegments());
    return formatted && formatted.map((s) => s.v).join('') !== this.sourceText() ? formatted : null;
  });
  protected readonly showingPretty = computed(() => this.prettyView() && this.formatted() !== null);
  protected readonly lines = computed(() => toLines(this.showingPretty() ? this.formatted()! : this.sourceSegments(), this.language()));
}
