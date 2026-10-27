import { ChangeDetectionStrategy, Component, booleanAttribute, computed, inject, input, numberAttribute, output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { GettingStarted, GettingStartedStep, GettingStartedStepId } from '../../../core/api/overview.api';
import { JsonValue } from '../../../core/api/types';
import { TimeService } from '../../../core/time/time.service';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { clientName } from '../shared/client-mark';

/** The kit's copy of each step (elements/getting-started.html): title, why it matters, the action and where it goes. */
interface StepCopy {
  readonly title: string;
  readonly why: string;
  readonly action: string;
  readonly route: string;
  readonly fragment?: string;
}

const COPY: Record<GettingStartedStepId, StepCopy> = {
  'master-key': {
    title: 'Protect your master key',
    why: 'It encrypts every secret. Keep a copy somewhere other than this computer.',
    action: 'Back up master key',
    route: '/secrets',
    fragment: 'master-key',
  },
  connection: {
    title: 'Add a connection',
    why: 'A database, host, cluster or API your agent may use. Its secret stays here.',
    action: 'Add connection',
    route: '/connections/new',
  },
  endpoint: {
    title: 'Create an endpoint',
    why: 'One MCP URL with only the tools and modes you choose.',
    action: 'Create endpoint',
    route: '/endpoints',
    fragment: 'create',
  },
  client: {
    title: 'Connect your AI app',
    why: 'Claude Desktop, Codex, Cursor or Antigravity. You see the config change before it is written.',
    action: 'Connect your AI app',
    route: '/clients',
  },
  playground: {
    title: 'Try a tool in Playground',
    why: 'Call a tool the way your agent would and see exactly what comes back.',
    action: 'Open Playground',
    route: '/playground',
  },
  notifications: {
    title: 'Set up approval notifications',
    why: 'Get approval requests on the desktop, in Slack or in Telegram, not only in this window.',
    action: 'Set up notifications',
    route: '/settings/notifications',
  },
};

/** One part of a step's "what was done" line: text, or a literal in mono. */
export interface MetaPart {
  readonly text: string;
  readonly mono?: boolean;
}

const str = (v: JsonValue | undefined): string | null => (typeof v === 'string' && v ? v : null);
const num = (v: JsonValue | undefined): number | null => (typeof v === 'number' ? v : null);
const list = (v: JsonValue | undefined): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);

/** "Desktop and Slack", "Claude Desktop, Codex and Cursor": names in a sentence. */
export function joinNames(names: readonly string[]): string {
  if (names.length <= 1) return names[0] ?? '';
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/**
 * The folded line of a done step from the core's facts (`/api/overview` getting-started `detail`): "Backed up 12 days
 * ago", "`dev-db` · PostgreSQL 16", "`dev` · 22 tools", "Claude Desktop, Codex".
 */
export function stepMeta(step: GettingStartedStep, relative: (at: string) => string): MetaPart[] {
  const d = step.detail;
  switch (step.id) {
    case 'master-key': {
      const at = str(d['lastBackupAt']);
      return at ? [{ text: `Backed up ${relative(at)}` }] : [];
    }
    case 'connection': {
      const name = str(d['name']);
      const type = str(d['typeName']);
      return name ? [{ text: name, mono: true }, ...(type ? [{ text: ` · ${type}` }] : [])] : [];
    }
    case 'endpoint': {
      const name = str(d['name']);
      const tools = num(d['tools']);
      return name ? [{ text: name, mono: true }, ...(tools !== null ? [{ text: ` · ${tools} ${tools === 1 ? 'tool' : 'tools'}` }] : [])] : [];
    }
    case 'client': {
      // The core lists client ids ("claude-desktop", "codex"); the line names them ("Claude Desktop, Codex").
      const names = list(d['clients']).map(clientName);
      const status = str(d['status']);
      // A folded line lists the clients like a tag list: "Claude Desktop, Codex".
      return names.length ? [{ text: names.join(', ') }] : status ? [{ text: status }] : [];
    }
    case 'playground': {
      const tool = str(d['tool']);
      const ms = num(d['latencyMs']);
      return tool ? [{ text: tool, mono: true }, ...(ms !== null ? [{ text: ` · ${ms} ms` }] : [])] : [];
    }
    case 'notifications': {
      const channels = list(d['channels']);
      const status = str(d['status']);
      return channels.length ? [{ text: joinNames(channels) }] : status ? [{ text: status }] : [];
    }
  }
}

let nextId = 0;

/**
 * `<section gwGettingStarted [checklist]="overview.gettingStarted" (dismiss)="…">` (elements/getting-started.html): the
 * first-run checklist. Six steps in order with how far the user has got; the current one carries the only primary
 * button, the others are links (any order works); done steps fold to one line with what was done. Once all are done
 * the card shrinks to "Setup complete" with the steps one click away, until dismissed.
 */
@Component({
  selector: 'section[gwGettingStarted]',
  imports: [GwIcon, GwToggle, RouterLink, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-card gw-getting-started',
    '[class.gw-getting-started--complete]': 'complete()',
    '[attr.aria-labelledby]': 'uid + "-title"',
  },
  templateUrl: './getting-started.html',
})
export class GwGettingStarted {
  private readonly time = inject(TimeService);

  readonly checklist = input.required<GettingStarted>();
  /** "Claude Desktop and Codex use endpoint `dev`": the complete card's sentence, when the page knows it. */
  readonly clients = input<readonly string[]>([]);
  readonly endpoint = input<string | null>(null);
  /** Clients found on this machine (K1): "Claude Desktop, Codex and Cursor are installed." */
  readonly detected = input<readonly string[]>([]);
  /** The tool the Playground step suggests ("Call `sql_query` the way your agent would…"). */
  readonly playgroundTool = input<string | null>(null);
  readonly level = input(2, { transform: numberAttribute });
  /**
   * The time estimate after the description ("About 5 minutes."): the element's demo and onboarding say it,
   * Overview's checklist (pages/overview.html) doesn't.
   */
  readonly estimate = input(true, { transform: booleanAttribute });
  readonly dismiss = output<void>();

  protected readonly uid = `gs-${++nextId}`;
  protected readonly copy = COPY;
  protected readonly complete = computed(() => this.checklist().done >= this.checklist().total);
  protected readonly fraction = computed(() => {
    const c = this.checklist();
    return c.total ? c.done / c.total : 0;
  });
  protected readonly clientNames = computed(() => joinNames(this.clients()));

  protected readonly detectedNames = computed(() => joinNames(this.detected()));

  protected meta(step: GettingStartedStep): MetaPart[] {
    return stepMeta(step, (at) => this.time.relative(at));
  }
}
