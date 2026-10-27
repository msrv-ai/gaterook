import { ChangeDetectionStrategy, Component, booleanAttribute, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EndpointRisk, RiskLevel } from '../../../core/api/endpoints.api';
import { JsonValue } from '../../../core/api/types';
import { TimeService } from '../../../core/time/time.service';
import { GwDialog, GwDialogClose, GwDialogOpen } from '../../dialog';
import { GwIcon } from '../../icons';
import { TextPart, literalParts } from '../shared/literals';

export type Ingredient = 'privateData' | 'untrustedContent' | 'externalChannel';

const INGREDIENTS: readonly Ingredient[] = ['privateData', 'untrustedContent', 'externalChannel'];

const NAMES: Record<Ingredient, { readonly icon: string; readonly name: string; readonly lower: string; readonly none: string; readonly absent: string }> = {
  privateData: {
    icon: 'database',
    name: 'Private data',
    lower: 'private data',
    none: 'No private data',
    absent: 'No tool reads private data',
  },
  untrustedContent: {
    icon: 'file-warning',
    name: 'Untrusted content',
    lower: 'untrusted content',
    none: 'No untrusted content',
    absent: 'No tool returns rows, logs or responses others wrote',
  },
  externalChannel: {
    icon: 'send-to-back',
    name: 'External channel',
    lower: 'external channel',
    none: 'No external channel',
    absent: 'No tool can send data out',
  },
};

/** One ingredient as a badge (with the sr-only "present") and the line that names its source. */
export interface IngredientView {
  readonly id: Ingredient;
  readonly level: RiskLevel;
  readonly tone: 'danger' | 'warning' | 'info' | 'neutral';
  readonly icon: string;
  readonly label: string;
  readonly present: boolean;
  readonly source: TextPart[];
}

/** The badges of one endpoint's ingredients: red when all three meet, amber otherwise, neutral "No …" when absent. */
export function ingredientViews(risk: EndpointRisk, tone: 'danger' | 'warning' | 'info' = 'warning'): IngredientView[] {
  return INGREDIENTS.map((id) => {
    const level = risk.ingredients[id];
    const n = NAMES[id];
    const src = risk.sources.find((s) => s.ingredient === id);
    if (level === 'absent') {
      return { id, level, tone: 'neutral', icon: 'check', label: n.none, present: false, source: [{ text: n.absent, code: false }] };
    }
    return {
      id,
      level,
      tone,
      icon: n.icon,
      label: level === 'limited' ? `${n.name} · limited` : n.name,
      present: true,
      source: src ? literalParts(src.text, [src.tool, src.connection]) : [],
    };
  });
}

/**
 * The tool that opens the channel: the first that can send data out, else the first limited one (fixed runbooks).
 * The core lists every source of an ingredient in tool order; for a present channel, the runbooks of `ssh_run` are not
 * what makes it a channel.
 */
export function channelSource(risk: EndpointRisk): EndpointRisk['sources'][number] | null {
  const channel = risk.sources.filter((s) => s.ingredient === 'externalChannel');
  if (risk.ingredients.externalChannel === 'present') {
    return channel.find((s) => !/\bfixed runbooks\b/.test(s.text)) ?? channel[0] ?? null;
  }
  return channel[0] ?? null;
}

/**
 * The compact line, as parts (literals in code): "private data + untrusted content + external channel". The Endpoints
 * card (`review`) names the channel's tool and a limited channel's runbooks, as pages/endpoints.html does:
 * "… + external channel through `http_request`", "… + limited channel (2 fixed runbooks)".
 */
export function compactLine(risk: EndpointRisk, review = false, runbooks: number | null = null): TextPart[] {
  const parts: TextPart[] = [];
  let text = '';
  for (const i of INGREDIENTS) {
    const level = risk.ingredients[i];
    if (level === 'absent') continue;
    if (text || parts.length) text += ' + ';
    if (i === 'externalChannel' && level === 'limited') {
      const n = review && runbooks ? `${runbooks} fixed ${runbooks === 1 ? 'runbook' : 'runbooks'}` : 'fixed runbooks';
      text += `limited channel (${n})`;
      continue;
    }
    text += NAMES[i].lower;
    const tool = i === 'externalChannel' && review ? channelSource(risk)?.tool : null;
    if (tool) {
      parts.push({ text: `${text} through `, code: false }, { text: tool, code: true });
      text = '';
    }
  }
  if (risk.published) text += ', published to the internet';
  if (text) parts.push({ text, code: false });
  return parts;
}

/** What a dismissal says: who, when, why (`EndpointRisk.dismissed`). */
interface Dismissal {
  readonly at: string | null;
  readonly by: string | null;
  readonly reason: string | null;
}

function dismissal(v: JsonValue): Dismissal | null {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return null;
  const s = (k: string) => (typeof v[k] === 'string' ? (v[k] as string) : null);
  return { at: s('at'), by: s('by'), reason: s('reason') };
}

let nextId = 0;

/**
 * `<div gwRiskCallout [risk]="endpoint.risk" slug="dev">` (elements/risk-callout.html): warns when one endpoint mixes
 * private data, untrusted content and a way to send data out. All three: present ingredients in red in an amber alert
 * (a person decides what to change), with Review tools, Split endpoint and Dismiss; two of three while building an
 * endpoint: amber; contained (the only way out is fixed SSH runbooks): an info alert that says what would break it.
 * `compact` is the one-line form of the Endpoints table. Dismissed: one neutral line with who, when and why, and Show
 * warning. Dismiss asks for a reason (the audit keeps it); Split opens the page's split dialog (`split` output).
 */
@Component({
  selector: 'div[gwRiskCallout]',
  imports: [GwIcon, RouterLink, GwDialog, GwDialogOpen, GwDialogClose],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class]': 'hostClass()',
    '[attr.data-state]': 'shown()',
  },
  templateUrl: './risk-callout.html',
})
export class GwRiskCallout {
  private readonly time = inject(TimeService);

  readonly risk = input.required<EndpointRisk>();
  /** The endpoint's slug; null while it is being created ("this new endpoint"). */
  readonly slug = input<string | null>(null);
  readonly compact = input(false, { transform: booleanAttribute });
  /**
   * The compact line of an Endpoints card (pages/endpoints.html): it names the tool that opens the channel
   * ("external channel through `http_request`"), a limited channel's runbooks, and ends with Review, a link to the
   * endpoint's risk callout (`/endpoints/<slug>#ep-risk`).
   */
  readonly review = input(false, { transform: booleanAttribute });
  /** How many fixed runbooks make a limited channel, when the page knows ("limited channel (2 fixed runbooks)"). */
  readonly runbooks = input<number | null>(null);
  /** Whether Split and Dismiss are offered (not while building an endpoint). */
  readonly actions = input(true, { transform: booleanAttribute });
  /** The dismissed line links to the Setup change it wrote (endpoint-detail: "View in Setup changes"). */
  readonly changesLink = input(false, { transform: booleanAttribute });

  readonly split = output<void>();
  readonly dismiss = output<string>();
  readonly restore = output<void>();

  protected readonly uid = `rc-${++nextId}`;
  protected readonly reason = signal('');

  /** `present`, `partial`, `limited`, `dismissed` (or `none`: nothing to show). */
  protected readonly shown = computed(() => this.risk().state);
  protected readonly dismissedBy = computed(() => dismissal(this.risk().dismissed));
  protected readonly limited = computed(() => this.shown() === 'limited' || this.shown() === 'contained');
  protected readonly hostClass = computed(() => {
    const s = this.shown();
    if (s === 'none') return 'gw-risk-callout';
    const tone = s === 'dismissed' ? 'neutral' : this.limited() ? 'info' : 'warning';
    const compact = this.compact() || s === 'dismissed' ? ' gw-alert--compact gw-risk-callout--compact' : '';
    return `gw-alert gw-alert--${tone}${compact} gw-risk-callout`;
  });
  protected readonly where = computed(() => (this.slug() ? `endpoint ${this.slug()}` : 'this new endpoint'));
  protected readonly ingredients = computed(() =>
    ingredientViews(this.risk(), this.shown() === 'present' ? 'danger' : 'warning'),
  );
  protected readonly channelTool = computed(() => channelSource(this.risk())?.tool ?? null);
  protected readonly missing = computed(() => INGREDIENTS.find((i) => this.risk().ingredients[i] === 'absent') ?? null);
  protected readonly line = computed(() => compactLine(this.risk(), this.review(), this.runbooks()));

  protected relative(at: string): string {
    return this.time.relative(at);
  }

  protected absolute(at: string): string {
    return this.time.absolute(at);
  }

  protected iso(at: string): string {
    return this.time.iso(at);
  }

  protected onDismissClosed(value: string): void {
    if (value === 'dismiss' && this.reason().trim()) this.dismiss.emit(this.reason().trim());
    this.reason.set('');
  }
}
