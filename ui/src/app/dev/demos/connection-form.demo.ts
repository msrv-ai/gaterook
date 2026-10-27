import { ChangeDetectionStrategy, Component, Injector, afterNextRender, inject, signal, viewChild } from '@angular/core';
import { ConnectionDetail, ConnectionDraft, ConnectionsApi } from '../../core/api/connections.api';
import { ConnectionFormSpec, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { GwConnectionForm } from '../../kit/elements/connection-form/connection-form';
import { GwConnectionTest } from '../../kit/elements/connection-form/connection-test';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_REFERENCES, DEMO_SECRET_SOURCES, DEMO_STORED_PASSWORD, DEMO_TEST } from '../fixtures';

interface Loaded {
  readonly spec: ConnectionFormSpec;
  readonly prodSpec: ConnectionFormSpec;
  readonly withStores: ConnectionFormSpec;
  readonly draft: ConnectionDraft;
  readonly devDb: ConnectionDetail;
  readonly stageDb: ConnectionDetail;
  readonly prodReplica: ConnectionDetail;
}

/**
 * Dev only: design/elements/connection-form.html. The form spec and the configs are the demo world's, live from the
 * core (`GET /api/connector-types/postgres/form`, `/api/connections/dev-db/duplicate`, …); the secret stores, the
 * references and the test results are fixtures, since the demo world connects no store and its connectors don't run.
 */
@Component({
  selector: 'gw-connection-form-demo',
  imports: [GwDemoHeader, GwConnectionForm, GwConnectionTest],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  templateUrl: './connection-form.demo.html',
})
export class ConnectionFormDemo {
  private readonly types = inject(ConnectorTypesApi);
  private readonly connections = inject(ConnectionsApi);
  protected readonly el = kitElement('connection-form');
  protected readonly data = signal<Loaded | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly test = DEMO_TEST;
  protected readonly references = DEMO_REFERENCES;
  protected readonly storedPassword = DEMO_STORED_PASSWORD;
  private readonly invalid = viewChild<GwConnectionForm>('invalid');
  private readonly injector = inject(Injector);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const [spec, prodSpec, draft, devDb, stageDb, prodReplica] = await Promise.all([
        this.types.form('postgres', 'dev'),
        this.types.form('postgres', 'prod'),
        this.connections.duplicate('dev-db'),
        this.connections.get('dev-db'),
        this.connections.get('stage-db'),
        this.connections.get('prod-replica'),
      ]);
      this.data.set({ spec, prodSpec, withStores: { ...spec, secretSources: DEMO_SECRET_SOURCES }, draft, devDb, stageDb, prodReplica });
      // "Validation after submit": the form as it looks after Save with a name that isn't a slug.
      afterNextRender(() => this.invalid()?.showErrors(), { injector: this.injector });
    } catch (e) {
      this.failure.set(e instanceof Error ? e.message : String(e));
    }
  }
}
