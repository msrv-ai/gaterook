import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { Env } from '../../core/api/types';
import { CoreStore } from '../../core/stores/core.store';
import { GwCodeBlock } from '../../code/code-block';
import { GwToggle } from '../../kit/disclosure';
import { GwIcon } from '../../kit/icons';
import { GwTabs } from '../../kit/tabs';
import { PoliciesData } from './policies.data';
import { ENV_TITLES, envConnections } from './policies-view';
import { GwPolicyPanel } from './policy-panel';
import { ENVS } from './policy-model';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;

/** The environment a deep link names: `#prod`, `#prod-approvals`. */
export function envOfFragment(fragment: string | null | undefined): Env | null {
  if (!fragment) return null;
  const head = fragment.split('-')[0];
  return (ENVS as readonly string[]).includes(head) ? (head as Env) : null;
}

/**
 * Policies (design/pages/policies.html; ARCHITECTURE.md §8.4 "policies"): guardrails per environment.
 *
 * - The header links to the denied calls; the invariant ("Every mutation needs a human approval") is an info alert
 *   with no switch anywhere.
 * - Tabs dev / stage / prod (`/policies/:env`, deep links `#<env>` and `#<env>-<section>` select the tab and scroll
 *   to the section): each a toolbar and a form of the sections SQL, SSH (with its runbooks), Kubernetes, Cloud,
 *   HTTP egress, Logs, Masking, Limits (meters of the last minute) and Approvals; a section of a kind the
 *   environment has no connection of shows the kit's empty card and its rules read only.
 * - Save, Discard, Reset, the prod `ssh_exec` confirmation and "changed outside MCP Gateway" are the panel's.
 *
 * States: nothing for 300 ms, then a skeleton; an error with Try again and Details; "may be out of date" when a reload
 * failed with values on screen.
 */
@Component({
  selector: 'gw-policies-page',
  imports: [RouterLink, GwIcon, GwTabs, GwToggle, GwCodeBlock, GwPolicyPanel],
  providers: [PoliciesData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page gw-page--narrow' },
  templateUrl: './policies.page.html',
})
export class PoliciesPage {
  protected readonly data = inject(PoliciesData);
  private readonly core = inject(CoreStore);
  private readonly router = inject(Router);
  private readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });

  /** Route parameter `env` (withComponentInputBinding). */
  readonly envParam = input<string | null>(null, { alias: 'env' });

  protected readonly envs = ENVS;
  protected readonly titles = ENV_TITLES;
  protected readonly env = computed<Env>(() => {
    const e = this.envParam();
    return e && (ENVS as readonly string[]).includes(e) ? (e as Env) : 'dev';
  });

  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.policies() && !this.data.error());

  /** Connections per environment, for the tab counters (left out when the list didn't answer). */
  protected readonly counts = computed(() => {
    const conns = this.data.connections();
    if (!conns) return null;
    return Object.fromEntries(ENVS.map((e) => [e, envConnections(e, conns).length])) as Record<Env, number>;
  });

  /** The kit says stage and prod inherit from dev; the core lets only stage inherit (ARCHITECTURE §8.6, item 14). */
  protected readonly description = computed(() => {
    const p = this.data.policies();
    const inheriting = p ? ENVS.filter((e) => p[e].inherits === 'dev') : ['stage'];
    return inheriting.length > 1
      ? "Guardrails for every connection, per environment: what agents may read, how much and how fast, and who approves changes. Stage and prod inherit each value they don't set from dev; prod starts with stricter defaults."
      : "Guardrails for every connection, per environment: what agents may read, how much and how fast, and who approves changes. Stage inherits each value it doesn't set from dev; prod has its own, stricter defaults.";
  });

  constructor() {
    void this.data.start();
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });
    // `/policies/foo` is no environment: the base one. A deep link into another tab (`/policies/dev#prod-sql`, kit
    // links `#prod-approvals`) moves the route there, so the tab the URL names is the one shown.
    effect(() => {
      const param = this.envParam();
      const fragment = this.fragment();
      untracked(() => {
        const target = envOfFragment(fragment);
        if (target && target !== param) void this.router.navigate(['/policies', target], { fragment: fragment ?? undefined, replaceUrl: true });
        else if (param && !(ENVS as readonly string[]).includes(param)) void this.router.navigate(['/policies', 'dev'], { replaceUrl: true });
      });
    });
    // The core came back (it was away, or another instance answers): the values may be stale.
    let wasRunning = this.core.running();
    let instance = this.core.instanceChanges();
    effect(() => {
      const running = this.core.running();
      const changes = this.core.instanceChanges();
      untracked(() => {
        if (running && (!wasRunning || changes !== instance)) void this.data.refresh();
        wasRunning = running;
        instance = changes;
      });
    });
  }

  /** A tab chosen by click or arrows: the URL follows, so a reload opens the same environment. */
  protected onTab(id: string): void {
    const env = id.replace(/^pol-tab-/, '');
    if (env !== this.env()) void this.router.navigate(['/policies', env], { replaceUrl: true });
  }

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : 'See the core log for details, then try again.';
  }

  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/policies → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }
}
