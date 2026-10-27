import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, booleanAttribute, computed, input, numberAttribute } from '@angular/core';
import { Params, RouterLink } from '@angular/router';
import { Env } from '../../../core/api/types';
import { GwIcon } from '../../icons';
import { GwClientMark } from '../shared/client-mark';

/** The tile's state in words: a `gw-status` tone and its label ("Healthy", "Slow · 1.8 s", "Not connected"). */
export interface TileState {
  readonly tone: 'success' | 'warning' | 'danger' | 'neutral' | 'info' | 'brand';
  readonly label: string;
}

/** A run of the meta line: text, or a literal set in mono ("HTTP API · `billing.stage.acme.dev`"). */
export interface TilePart {
  readonly text: string;
  readonly mono?: boolean;
}

let nextId = 0;

/**
 * `<li gwStatusTile name="dev-db" icon="database" env="dev" [state]="…" [link]="['/connections', 'dev-db']">`
 * (design/elements/status-tile.html, COMPONENTS.md › status-tile): one compact card that names one thing — a connection,
 * an AI app — and says its state in words, with at most one action, projected as the tile's content (Connect, Test
 * again, How to install).
 *
 * - **Link or not.** A thing with its own page gets `link`: the card is `gw-card--interactive` and the name is its
 *   stretched `gw-card__link`; only the action sits above that link. When the action is the tile's only destination
 *   (an app to connect, one to install), there is no `link`: the title is plain text and the tile is not interactive.
 * - **Title** — a heading one level below the set's heading (`level` 3 in a section, 4 inside a `gw-stepper--guide`
 *   step), with the environment marker after a connection's name.
 * - **Busy** — while the action runs (Test again), `checking` turns the state into `gw-status--checking`
 *   (`role="status"`, "Checking…"); the action points its `aria-describedby` at `stateId` (`#tile="gwStatusTile"`).
 *
 * The set is the page's: a `<ul class="gw-grid gw-grid--3" role="list" aria-label>` (`--2` for the four AI apps),
 * problems and tiles with an action first.
 */
@Component({
  selector: 'li[gwStatusTile]',
  exportAs: 'gwStatusTile',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwClientMark],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-card gw-status-tile',
    '[class.gw-card--interactive]': 'link() !== null',
  },
  template: `
    @if (client(); as c) {
      <span [gwClientMark]="c"></span>
    } @else {
      <span class="gw-icon-tile gw-icon-tile--neutral" aria-hidden="true"><svg [lucideIcon]="icon()" class="gw-icon"></svg></span>
    }
    <div class="gw-status-tile__main">
      @if (level() === 4) {
        <h4 class="gw-status-tile__title gw-text-ui"><ng-container [ngTemplateOutlet]="title" /></h4>
      } @else {
        <h3 class="gw-status-tile__title gw-text-ui"><ng-container [ngTemplateOutlet]="title" /></h3>
      }
      @if (metaParts().length) {
        <!-- A line with an address wraps (kit: "HTTP API · billing.stage.acme.dev"); a plain one is cut, whole in title. -->
        <p class="gw-text-caption gw-fg-muted" [class.gw-truncate]="!hasLiteral()" [attr.title]="hasLiteral() ? null : metaText()">@for (p of metaParts(); track $index) {@if (p.mono) {<span class="gw-mono">{{ p.text }}</span>} @else {{{ p.text }}}}</p>
      }
    </div>
    <div class="gw-status-tile__end">
      @if (checking()) {
        <span class="gw-status gw-status--checking" role="status" [id]="stateId"><span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span><span class="gw-status__label">Checking…<span class="gw-sr-only"> {{ name() }}</span></span></span>
      } @else if (state(); as s) {
        <span [class]="'gw-status gw-status--' + s.tone" [id]="stateId"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">{{ s.label }}</span></span>
      }
      <ng-content />
    </div>
    <ng-template #title>
      @if (link(); as l) {
        <a class="gw-card__link" [class.gw-mono]="mono()" [routerLink]="l" [fragment]="fragment() ?? undefined" [queryParams]="query()">{{ name() }}</a>
      } @else if (mono()) {
        <span class="gw-mono">{{ name() }}</span>
      } @else {
        {{ name() }}
      }
      @if (env(); as e) {
        <span class="gw-sr-only">environment</span> <span [class]="'gw-env gw-env--sm gw-env--' + e">{{ e }}</span>
      }
    </ng-template>
  `,
})
export class GwStatusTile {
  /** What the tile names: a connection (`dev-db`, set in mono) or an AI app ("Cursor"). */
  readonly name = input.required<string>();
  /** The title's heading level: 3 under a section's h2, 4 inside a guide step's h3. */
  readonly level = input(3, { transform: numberAttribute });
  /** The thing's own page; null: the tile isn't a link (its action is the only way on). */
  readonly link = input<string | readonly unknown[] | null>(null);
  readonly fragment = input<string | null>(null);
  readonly query = input<Params | null>(null);
  /** The name is a literal (connections). */
  readonly mono = input(false, { transform: booleanAttribute });
  /** The connection's environment marker after its name. */
  readonly env = input<Env | null>(null);
  /** A connection's type icon (`gw-icon-tile --neutral`). */
  readonly icon = input('plug');
  /** An AI app's mark instead of the icon tile (`claude-desktop`, `codex`…). */
  readonly client = input<string | null>(null);
  /** The one meta line: type, where it connects, what it can use. */
  readonly meta = input<string | readonly TilePart[] | null>(null);
  readonly state = input<TileState | null>(null);
  /** The tile's action runs: the state says "Checking…" (role=status) until the result comes. */
  readonly checking = input(false, { transform: booleanAttribute });

  /** The state's id, for the action's `aria-describedby` while it is busy. */
  readonly stateId = `st-${++nextId}`;

  protected readonly metaParts = computed<readonly TilePart[]>(() => {
    const m = this.meta();
    if (!m) return [];
    return typeof m === 'string' ? [{ text: m }] : m;
  });
  protected readonly metaText = computed(() => this.metaParts().map((p) => p.text).join(''));
  protected readonly hasLiteral = computed(() => this.metaParts().some((p) => p.mono));
}
