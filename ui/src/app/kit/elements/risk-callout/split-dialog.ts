import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { EndpointRisk, SplitPreview } from '../../../core/api/endpoints.api';
import { Env } from '../../../core/api/types';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwIcon } from '../../icons';
import { Ingredient } from './risk-callout';

/** One connection that could move out, and what moving it does. */
export interface SplitOption {
  readonly connection: string;
  readonly env: Env;
  readonly ingredient: Ingredient;
  /** The tools that move with it (`http_request`, `http_get`). */
  readonly tools: readonly string[];
  /** "Agents on dev can no longer send data out." */
  readonly effect: string;
  /** "Recommended · removes the risk · Codex called http_request today". */
  readonly meta: string;
  readonly recommended: boolean;
}

/** A client that may need the moved tools; checked ones get the new URL as a second server. */
export interface SplitClient {
  readonly id: string;
  readonly name: string;
  /** "called http_request today". */
  readonly note: string | null;
  readonly checked: boolean;
}

/** What the page sends to `/api/endpoints/{slug}/split(/preview)`. */
export interface SplitRequest {
  readonly move: string[];
  readonly newSlug: string;
  readonly clients: string[];
}

const OPTION_TITLE: Record<Ingredient, { readonly name: string; readonly icon: string }> = {
  privateData: { name: 'Private data', icon: 'database' },
  untrustedContent: { name: 'Untrusted content', icon: 'file-warning' },
  externalChannel: { name: 'External channel', icon: 'send-to-back' },
};

const BADGES: readonly { readonly id: Ingredient; readonly icon: string; readonly name: string; readonly none: string }[] = [
  { id: 'privateData', icon: 'database', name: 'Private data', none: 'No private data' },
  { id: 'untrustedContent', icon: 'file-warning', name: 'Untrusted content', none: 'No untrusted content' },
  { id: 'externalChannel', icon: 'send-to-back', name: 'External channel', none: 'No external channel' },
];

let nextId = 0;

const GENERIC_WORDS = new Set(['api', 'app', 'db', 'vm', 'dev', 'stage', 'prod', 'svc', 'service', 'server', 'http', 'sql']);

/**
 * The new endpoint's suggested slug: the endpoint's slug and the word that says what the moving connection is
 * (kit: billing-api → dev-billing, app-logs → dev-logs); "<slug>-split" when its name has no such word.
 */
export function splitSlug(slug: string, connection: string): string {
  const word = connection.split('-').find((w) => w && !GENERIC_WORDS.has(w)) ?? 'split';
  return `${slug}-${word}`.slice(0, 40).replace(/-+$/, '');
}

/**
 * The Split endpoint dialog (elements/risk-callout.html, endpoint-detail `#ep-dlg-split`): move one ingredient's
 * connection to a new endpoint so neither holds the whole combination. The recommended move is pre-selected (focus
 * starts there); before / after shows both endpoints' ingredients from the core's preview; the checked clients get the
 * new URL next, each through its own Connect step. `changed` asks the page for a new preview; `create` confirms.
 */
@Component({
  selector: 'gw-split-dialog',
  imports: [GwIcon, GwDialog, GwDialogClose, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './split-dialog.html',
})
export class GwSplitDialog {
  readonly slug = input.required<string>();
  readonly options = input.required<readonly SplitOption[]>();
  readonly clients = input<readonly SplitClient[]>([]);
  readonly preview = input<SplitPreview | null>(null);
  readonly address = input('127.0.0.1:7465');
  /** Published endpoints keep their public URL; the dialog says the new one is local only. */
  readonly published = input(false);

  readonly changed = output<SplitRequest>();
  readonly create = output<SplitRequest>();

  protected readonly uid = `split-${++nextId}`;
  protected readonly titles = OPTION_TITLE;
  protected readonly badges = BADGES;
  private readonly dialog = viewChild.required<GwDialog>('dlg');

  protected readonly move = linkedSignal(
    () => this.options().find((o) => o.recommended)?.connection ?? this.options()[0]?.connection ?? '',
  );
  /** The kit's suggestion for what moves ("dev-billing"), until the person types a name of their own. */
  protected readonly newSlug = linkedSignal<string, string>({
    source: () => splitSlug(this.slug(), this.move()),
    computation: (suggested, previous) => (previous && previous.value !== previous.source ? previous.value : suggested),
  });
  protected readonly picked = linkedSignal(() => new Set(this.clients().filter((c) => c.checked).map((c) => c.id)));
  protected readonly slugValid = computed(() => /^[a-z0-9]+(-[a-z0-9]+)*$/.test(this.newSlug()) && this.newSlug().length <= 40);
  protected readonly busy = signal(false);

  open(opener?: HTMLElement | null): void {
    this.dialog().open(opener);
    this.changed.emit(this.request());
  }

  close(): void {
    this.dialog().close('');
  }

  protected present(risk: EndpointRisk | undefined, id: Ingredient): boolean {
    return !!risk && risk.ingredients[id] !== 'absent';
  }

  /** "No risky combination: nothing on dev can send data out." */
  protected verdict(risk: EndpointRisk | undefined, name: string): string {
    if (!risk) return '';
    if (this.risky(risk)) return `Still a risky combination on ${name}.`;
    // The only way out left is fixed SSH runbooks (the callout's "Contained").
    if (risk.state === 'limited' || risk.state === 'contained') return `Contained on ${name}: the only way out is fixed runbooks.`;
    if (risk.ingredients.externalChannel === 'absent') return `No risky combination: nothing on ${name} can send data out.`;
    if (risk.ingredients.privateData === 'absent') return 'No risky combination: no private data to send.';
    return 'No risky combination: nothing untrusted is read.';
  }

  /** Still all three ingredients after the split: warning badges and a shield-alert, as the kit's "Still risky". */
  protected risky(risk: EndpointRisk | undefined): boolean {
    return !!risk && (risk.state === 'present' || risk.state === 'partial');
  }

  protected pick(connection: string): void {
    this.move.set(connection);
    this.changed.emit(this.request());
  }

  protected setSlug(value: string): void {
    this.newSlug.set(value);
    if (this.slugValid()) this.changed.emit(this.request());
  }

  protected toggleClient(id: string, on: boolean): void {
    this.picked.update((s) => {
      const next = new Set(s);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  protected onClosed(value: string): void {
    if (value === 'split' && this.slugValid()) this.create.emit(this.request());
  }

  private request(): SplitRequest {
    return { move: [this.move()], newSlug: this.newSlug(), clients: [...this.picked()] };
  }
}
