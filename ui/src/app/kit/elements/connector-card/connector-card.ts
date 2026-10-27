import {
  ChangeDetectionStrategy,
  Component,
  Directive,
  booleanAttribute,
  computed,
  contentChild,
  input,
  model,
  numberAttribute,
  output,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ConnectorCard, ConnectorGroup, ConnectorKind } from '../../../core/api/connector-types.api';
import { GwAriaDisabled } from '../../aria-disabled';
import { GwDialog, GwDialogClose, GwDialogOpen } from '../../dialog';
import { GwEnableWhen } from '../../enable-when';
import { GwIcon } from '../../icons';
import { literalParts } from '../shared/literals';

/** The kind badge: Built-in (neutral, it is also the trust), Template, Plugin, MCP server (outline). */
const KINDS: Record<string, { readonly icon: string; readonly text: string; readonly cls: string }> = {
  'built-in': { icon: 'package', text: 'Built-in', cls: 'gw-badge--neutral' },
  template: { icon: 'file-code', text: 'Template', cls: 'gw-badge--outline' },
  plugin: { icon: 'puzzle', text: 'Plugin', cls: 'gw-badge--outline' },
  'mcp-server': { icon: 'plug', text: 'MCP server', cls: 'gw-badge--outline' },
};

export function kindBadge(kind: ConnectorKind) {
  return KINDS[kind] ?? { icon: 'puzzle', text: kind, cls: 'gw-badge--outline' };
}

/**
 * What the card's one action is: add a connection of a ready type, install, configure, update. `null`: the page
 * projects its own (`[gwConnectorCardActions]`: Update and a menu of an installed plugin, Add to endpoint, Open).
 */
export type ConnectorAction = 'add' | 'install' | 'configure' | 'update' | null;

/**
 * The footer's status in place of the tool count (BLUEPRINT §7 tones): "Update available · v1.3.0", "Running · 54 MB",
 * "Sleeping", "Installed as github-mcp". `mono` is the literal at its end (a version, a server name).
 */
export interface ConnectorCardStatus {
  readonly tone: 'success' | 'info' | 'warning' | 'danger' | 'neutral';
  readonly text: string;
  readonly mono?: string | null;
}

/** One card of the catalog grid on Connectors. */
export interface ConnectorCardView {
  readonly type: string;
  readonly variant: string | null;
  readonly name: string;
  readonly icon: string;
  readonly kind: ConnectorKind;
  readonly version: string | null;
  /** `null` for built-ins (their one badge is the trust), true / false for packages. */
  readonly signed: boolean | null;
  readonly description: string;
  /** Tool names in the description ("`pg_slow_queries` and `pg_locks`…") set in code. */
  readonly literals?: readonly string[];
  readonly tools: number | null;
  readonly action: ConnectorAction;
  /** "v1.3.0" when an update is available. */
  readonly updateTo?: string | null;
  /** The footer's status instead of the tool count; `updateTo` alone shows "Update available · <version>". */
  readonly status?: ConnectorCardStatus | null;
  /** The installed version a catalog update replaces: the description starts "You run v1.2.0." */
  readonly runs?: string | null;
  /** The unsigned-install confirmation: the package's file, size and digest. */
  readonly package?: { readonly file: string; readonly size: string; readonly sha256: string } | null;
}

let nextId = 0;

/**
 * Marks the line under an installed card's description (connectors.html › Installed): "3 connections · View
 * activity", "5 tools · no connections yet · Add connection", "Running · not on an endpoint yet". With it the body
 * stacks the description and this line (`gw-stack--sm`), as the kit's Installed cards do.
 */
@Directive({ selector: '[gwConnectorCardNote]' })
export class GwConnectorCardNote {}

/** Marks the footer actions a page puts on a card without a built-in action (`action: null`). */
@Directive({ selector: '[gwConnectorCardActions]' })
export class GwConnectorCardActions {}

/**
 * `<article gwConnectorCard [card]="…" (act)="…">` (elements/connector-card.html): one connector type — what it
 * connects to, where it comes from (Built-in, Template, Plugin, MCP server), whether the catalog signed it, how many
 * tools it brings and the one thing to do next. Installing an unsigned plugin asks first in a danger confirmation
 * that names the risk; Install stays disabled until the acknowledgement is checked, focus starts on Cancel.
 *
 * Connectors › Installed and Catalog (pages/connectors.html) add a line under the description
 * (`[gwConnectorCardNote]`), a status in the footer (`card.status`), their own footer actions
 * (`[gwConnectorCardActions]` with `action: null`), and `confirmUnsigned=false` where the page reviews the package
 * before it confirms (its check report names the file and the SHA-256).
 */
@Component({
  selector: 'article[gwConnectorCard]',
  imports: [GwIcon, RouterLink, GwDialog, GwDialogOpen, GwDialogClose, GwEnableWhen],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card gw-connector-card', '[attr.aria-labelledby]': 'uid + "-t"' },
  templateUrl: './connector-card.html',
})
export class GwConnectorCard {
  readonly card = input.required<ConnectorCardView>();
  readonly level = input(3, { transform: numberAttribute });
  /** Install of an unsigned plugin asks in the card's own danger confirmation first (false: the page reviews it). */
  readonly confirmUnsigned = input(true, { transform: booleanAttribute });
  /** The action runs (Install while the core checks the package): `aria-busy` with a spinner, inert, label kept. */
  readonly busy = input(false, { transform: booleanAttribute });
  /** The card's action, after the unsigned confirmation where there is one. */
  readonly act = output<ConnectorCardView>();

  protected readonly uid = `cn-${++nextId}`;
  protected readonly note = contentChild(GwConnectorCardNote);
  protected readonly kind = computed(() => kindBadge(this.card().kind));
  protected readonly description = computed(() => literalParts(this.card().description, this.card().literals ?? []));
  protected readonly status = computed<ConnectorCardStatus | null>(() => {
    const c = this.card();
    if (c.status) return c.status;
    return c.updateTo ? { tone: 'info', text: 'Update available · ', mono: c.updateTo } : null;
  });
  protected readonly unsigned = computed(
    () => this.confirmUnsigned() && this.card().signed === false && this.card().action === 'install',
  );
  protected readonly what = computed(() => {
    const c = this.card();
    if (c.kind === 'plugin') return `${c.signed === false ? 'unsigned ' : ''}${c.name} plugin`;
    if (c.kind === 'mcp-server') return `${c.name} MCP server`;
    return c.name;
  });
  protected readonly query = computed(() => {
    const c = this.card();
    return c.variant ? { type: c.type, variant: c.variant } : { type: c.type };
  });

  protected onConfirm(value: string): void {
    if (value === 'install') this.act.emit(this.card());
  }

  protected run(): void {
    if (!this.busy()) this.act.emit(this.card());
  }
}

/**
 * `<fieldset gwConnectorPicker [groups]="catalog.groups" [(value)]="type">` (elements/connector-card.html, Picker): step
 * 1 of Add connection — radio cards grouped by category, one radio group across all of them so the arrow keys move
 * through every type. Built-in types say so with their tool count; packages carry their kind and trust; a type that
 * isn't installed stays visible and focusable but unavailable (`aria-disabled`, its reason as the description).
 * `error` marks every card and says what to do ("Choose a connector type to continue.").
 */
@Component({
  selector: 'fieldset[gwConnectorPicker]',
  imports: [GwIcon, GwAriaDisabled],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-connector-card__picker', '[attr.aria-describedby]': 'error() ? uid + "-err" : null' },
  templateUrl: './connector-picker.html',
})
export class GwConnectorPicker {
  readonly groups = input.required<readonly ConnectorGroup[]>();
  /** The picked card: `type` or `type:variant`. */
  readonly value = model<string | null>(null);
  readonly error = input<string | null>(null);
  readonly required = input(false, { transform: booleanAttribute });
  readonly level = input(3, { transform: numberAttribute });

  protected readonly uid = `pk-${++nextId}`;
  protected readonly kindBadge = kindBadge;

  protected key(c: ConnectorCard): string {
    return c.variant ? `${c.type}:${c.variant}` : c.type;
  }

  protected id(c: ConnectorCard, part: string): string {
    return `${this.uid}-${this.key(c).replace(/[^a-z0-9-]/gi, '-')}-${part}`;
  }

  /** "Built-in · 4 tools", "Built-in Logs · 2 tools", "v1.2.0 · 5 tools", "Tools from the file". */
  protected meta(c: ConnectorCard, group: ConnectorGroup): string {
    const tools = c.tools !== null ? `${c.tools} ${c.tools === 1 ? 'tool' : 'tools'}` : `Tools from the ${c.toolsFrom ?? 'server'}`;
    if (c.kind === 'built-in') return c.tools !== null ? `Built-in${c.variant ? ` ${group.name}` : ''} · ${tools}` : tools;
    if (c.kind === 'plugin' && c.version && c.available) return `${c.version.startsWith('v') ? c.version : `v${c.version}`} · ${tools}`;
    return tools;
  }

  protected pick(c: ConnectorCard): void {
    if (c.available) this.value.set(this.key(c));
  }
}
