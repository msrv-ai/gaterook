import { ChangeDetectionStrategy, Component, computed, input, linkedSignal, output } from '@angular/core';
import { StoredSecret, TestOutcome } from '../../../core/api/connections.api';
import { SchemaNode, getAt } from '../../../schema-form/compile';
import { GwSchemaField } from '../../../schema-form/fields';
import { SchemaForm } from '../../../schema-form/schema-form';
import { GwIcon } from '../../icons';
import { ConnectionFormValue } from './connection-form';

export interface ProjectDiscoveryRequest {
  /** In-memory only: includes credentials. Never log or render this fingerprint. */
  readonly key: string;
  readonly value: ConnectionFormValue;
}

interface Project { readonly projectId: string; readonly displayName: string }
interface ProjectDiscovery {
  readonly request: ProjectDiscoveryRequest;
  readonly state: 'loading' | 'ready' | 'error';
  readonly projects: readonly Project[];
  readonly complete: boolean;
  readonly message: string | null;
  readonly hints: readonly string[];
}

/** Optional credential-based project suggestions alongside the connector's editable resource scopes. */
@Component({
  selector: 'gw-cloud-projects',
  imports: [GwIcon, GwSchemaField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cloud-projects.html',
})
export class GwCloudProjects {
  readonly form = input.required<SchemaForm>();
  readonly node = input.required<SchemaNode>();
  readonly value = input.required<ConnectionFormValue>();
  readonly type = input.required<string>();
  readonly stored = input<readonly StoredSecret[]>([]);
  readonly idPrefix = input.required<string>();
  readonly discover = output<ProjectDiscoveryRequest>();

  private readonly key = computed(() => {
    const value = this.value();
    return JSON.stringify([this.type(), value.env, value.config['backend'], value.config['credentials'], value.secrets,
      this.form().source(), this.stored().map((secret) => [secret.field, secret.version, secret.state, secret.source])]);
  });
  readonly result = linkedSignal(() => { this.form(); this.key(); return null as ProjectDiscovery | null; });
  readonly busy = computed(() => this.result()?.state === 'loading');
  readonly selected = computed(() => {
    const value = getAt(this.form().model(), this.node().path);
    return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
  });

  current(request: ProjectDiscoveryRequest): boolean {
    return request.key === this.key() && request === this.result()?.request;
  }

  load(): void {
    if (this.busy()) return;
    const form = this.form();
    const submitted = form.submitted();
    if (!form.submit([this.node().pointer])) return;
    // Discovering choices is not an attempt to save the as-yet unselected scope.
    form.submitted.set(submitted);
    const request = { key: this.key(), value: this.value() };
    this.result.set({ request, state: 'loading', projects: [], complete: true, message: null, hints: [] });
    this.discover.emit(request);
  }

  start(request: ProjectDiscoveryRequest): boolean {
    if (!this.current(request)) return false;
    this.result.update((result) => result ? { ...result, state: 'loading', message: null } : result);
    return true;
  }

  complete(request: ProjectDiscoveryRequest, outcome: TestOutcome | null, error: string | null = null): boolean {
    if (!this.current(request)) return false;
    const details = outcome?.details;
    const data = details && typeof details === 'object' && !Array.isArray(details) ? details : null;
    const found = data?.['projects'];
    const ok = outcome?.ok === true && Array.isArray(found);
    const projects = new Map<string, Project>();
    if (ok) for (const project of found) {
      if (!project || typeof project !== 'object' || Array.isArray(project)) continue;
      const id = project['projectId'];
      if (typeof id !== 'string' || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(id)) continue;
      projects.set(id, { projectId: id, displayName: typeof project['displayName'] === 'string' ? project['displayName'] : id });
    }
    this.result.set({ request, state: ok ? 'ready' : 'error', projects: [...projects.values()],
      complete: data?.['discoveryComplete'] !== false,
      message: error ?? outcome?.error?.message ?? (ok ? null : 'The server did not return a project list.'),
      hints: (outcome?.hints ?? []).map((hint) => hint.text),
    });
    return true;
  }

  choose(select: HTMLSelectElement): void {
    const id = select.value;
    select.value = '';
    if (this.result()?.state !== 'ready' || !this.result()?.projects.some((project) => project.projectId === id)) return;
    const resource = `projects/${id}`;
    if (!this.selected().includes(resource)) this.form().set(this.node(), [...this.selected(), resource]);
    this.form().touch(this.node());
  }

  reset(): void { this.result.set(null); }
}
