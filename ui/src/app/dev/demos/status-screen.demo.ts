import { ChangeDetectionStrategy, Component } from '@angular/core';
import { GwStatusScreen, StatusScreenView } from '../../kit/elements/status-screen/status-screen';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';

const STARTING: StatusScreenView = {
  kind: 'starting',
  steps: [
    { id: 'runtime', state: 'complete', description: 'Native image · 0.1 s' },
    { id: 'master-key', state: 'complete', description: 'Loaded', mono: '~/.mcp-gateway/master.keyset' },
    { id: 'db-migrations', state: 'complete', description: 'Up to date · 2 applied in 0.4 s' },
    { id: 'local-api', state: 'current', description: 'Binding', mono: '127.0.0.1:7465' },
  ],
};

const PORT: StatusScreenView = {
  kind: 'port-in-use',
  portOwner: null,
  steps: [
    { id: 'runtime', state: 'complete' },
    { id: 'master-key', state: 'complete' },
    { id: 'db-migrations', state: 'complete' },
    { id: 'local-api', state: 'error', description: 'Address already in use:', mono: '127.0.0.1:7465' },
  ],
};

const LOCKED: StatusScreenView = {
  kind: 'locked',
  reason: 'MASTER_KEY_MISSING',
  keyPath: '~/.mcp-gateway/master.keyset',
  secrets: 8,
  references: 3,
  lastBackupAt: '2026-10-10T14:30:00+02:00',
  // The kit's sentence (pages/core-starting.html), as /api/master-key's protectsBy words it.
  breakdown: [
    { text: '6 connections, the ', code: false },
    { text: 'github-mcp', code: true },
    { text: ' server token and the Vault sign-in', code: false },
  ],
  referenceTo: 'Vault, GCP and AWS',
  steps: [
    { id: 'runtime', state: 'complete' },
    { id: 'master-key', state: 'error', description: 'No file at', mono: '~/.mcp-gateway/master.keyset' },
    { id: 'db-migrations', state: 'upcoming' },
    { id: 'local-api', state: 'upcoming' },
  ],
};

const STOPPED: StatusScreenView = {
  kind: 'stopped',
  at: '2026-10-22T14:29:58+02:00',
  exitCode: 3,
  reason: 'java.lang.OutOfMemoryError: Java heap space',
  uptimeMs: (3 * 60 + 12) * 60_000,
  logLines: [
    '14:29:57.981 WARN  c.g.audit.Writer     - Audit queue at 92% (46,080 events)',
    '14:29:58.402 ERROR c.g.core.Main        - Uncaught in worker-7: java.lang.OutOfMemoryError: Java heap space',
    '14:29:58.405 INFO  c.g.core.Main        - Shutting down: 3 pending approval requests kept',
    '14:29:58.411 INFO  c.g.core.Main        - Terminating (exit 3)',
  ].join('\n'),
};

/** Dev only: design/elements/status-screen.html with the demo world. */
@Component({
  selector: 'gw-status-screen-demo',
  imports: [GwDemoHeader, GwStatusScreen],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="s-starting">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-starting">Starting</h2>
        <p class="gw-section__description">No estimate, so the bar is indeterminate; the steps show what is already done and what runs now. Screen readers hear one status line per step.</p>
      </header>
      <div class="gw-card gw-status-screen__frame">
        <gw-status-screen contained [level]="3" version="0.4.0" [view]="starting" />
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-port">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-port">Failed: port already in use</h2>
        <p class="gw-section__description">The failing step turns red with its reason; the command that finds the other process sits under a disclosure, ready to copy.</p>
      </header>
      <div class="gw-card gw-status-screen__frame">
        <gw-status-screen contained [level]="3" version="0.4.0" [view]="port" />
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-key">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-key">Failed: master key not found</h2>
        <p class="gw-section__description">Restoring the backup is the safe way on. Creating a new key is possible but destructive, so it sits in a danger notice that says what stops working and asks again in a confirmation.</p>
      </header>
      <div class="gw-card gw-status-screen__frame">
        <gw-status-screen contained [level]="3" version="0.4.0" [view]="locked" />
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-stopped">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-stopped">Stopped unexpectedly</h2>
        <p class="gw-section__description">The core ran and then exited: the facts (exit code, reason, uptime, log file), the last log lines on demand, and Restart core as the one main action.</p>
      </header>
      <div class="gw-card gw-status-screen__frame">
        <gw-status-screen contained [level]="3" version="0.4.0" [view]="stopped" />
      </div>
    </section>
  `,
})
export class StatusScreenDemo {
  protected readonly el = kitElement('status-screen');
  protected readonly starting = STARTING;
  protected readonly port = PORT;
  protected readonly locked = LOCKED;
  protected readonly stopped = STOPPED;
}
