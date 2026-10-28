import { ChangeDetectionStrategy, Component, computed, input, linkedSignal, output, untracked } from '@angular/core';
import { StoredSecret, TestOutcome } from '../../../core/api/connections.api';
import { getAt, setAt } from '../../../schema-form/compile';
import { SchemaForm } from '../../../schema-form/schema-form';
import { GwIcon } from '../../icons';
import { ConnectionFormValue } from './connection-form';
import { FileSelection, relativeFile, withoutSelectionValue } from './file-selection';
import { testConfirmTitle, testFacts } from './connection-test';

export interface FileBrowseRequest {
  /** In-memory only: includes credentials. Never log or render this fingerprint. */
  readonly key: string;
  readonly path: string;
  readonly value: ConnectionFormValue;
}

export interface FileBrowseTrust {
  readonly request: FileBrowseRequest;
  readonly confirmationId: string;
}

interface RemoteEntry {
  readonly name: string;
  readonly path: string;
  readonly type: 'directory' | 'file';
  readonly size: number | null;
}

interface RemoteDirectory {
  readonly path: string;
  readonly parent: string | null;
  readonly entries: readonly RemoteEntry[];
  readonly truncated: boolean;
}

interface BrowseState {
  readonly request: FileBrowseRequest;
  readonly state: 'loading' | 'ready' | 'error' | 'confirmation';
  readonly directory: RemoteDirectory | null;
  readonly outcome: TestOutcome | null;
  readonly message: string | null;
}

function directoryOf(outcome: TestOutcome | null): RemoteDirectory | null {
  const d = outcome?.details;
  if (!outcome?.ok || !d || typeof d !== 'object' || Array.isArray(d) || typeof d['path'] !== 'string' || !Array.isArray(d['entries'])) return null;
  const entries: RemoteEntry[] = [];
  for (const e of d['entries']) {
    if (!e || typeof e !== 'object' || Array.isArray(e) || typeof e['name'] !== 'string' || typeof e['path'] !== 'string'
      || (e['type'] !== 'file' && e['type'] !== 'directory')) continue;
    entries.push({ name: e['name'], path: e['path'], type: e['type'], size: typeof e['size'] === 'number' ? e['size'] : null });
  }
  return { path: d['path'], parent: typeof d['parent'] === 'string' ? d['parent'] : null, entries, truncated: d['truncated'] === true };
}

/** Lightweight folder navigation and exact file selection, composed from canonical form, table and checkbox styles. */
@Component({
  selector: 'gw-remote-files',
  imports: [GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './remote-files.html',
})
export class GwRemoteFiles {
  readonly form = input.required<SchemaForm>();
  readonly selection = input.required<FileSelection>();
  readonly value = input.required<ConnectionFormValue>();
  readonly type = input.required<string>();
  readonly stored = input<readonly StoredSecret[]>([]);
  readonly idPrefix = input.required<string>();
  readonly submitted = input(false);
  readonly browse = output<FileBrowseRequest>();
  readonly trust = output<FileBrowseTrust>();

  readonly root = computed(() => {
    const value = getAt(this.form().model(), this.selection().rootPath);
    return typeof value === 'string' ? value : '';
  });
  readonly selected = computed(() => {
    const value = getAt(this.form().model(), this.selection().path);
    return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
  });
  /** Replacing a schema form (Discard or opening another connection) resets its navigation. */
  readonly address = linkedSignal(() => { this.form(); return untracked(this.root) || '/var/log'; });
  private readonly key = computed(() => {
    const value = this.value();
    const selection = this.selection();
    const config = withoutSelectionValue(withoutSelectionValue(value.config, selection.rootPath), selection.path);
    return JSON.stringify([this.type(), value.env, config, value.secrets, this.form().source(), this.address(),
      this.stored().map((secret) => [secret.field, secret.version, secret.state, secret.source])]);
  });
  readonly result = linkedSignal(() => { this.key(); return null as BrowseState | null; });
  readonly busy = computed(() => this.result()?.state === 'loading');
  readonly directory = computed(() => this.result()?.directory ?? null);
  readonly problem = computed(() => {
    if (!this.root()) return 'Choose a log folder.';
    if (!this.selected().length) return 'Select at least one log file agents may read.';
    return null;
  });
  readonly error = computed(() => {
    const form = this.form();
    for (const pointer of [this.selection().rootPointer, this.selection().pointer]) {
      const node = form.compiled.all.find((field) => field.pointer === pointer);
      const message = node ? form.error(node) : null;
      if (message) return message;
    }
    return this.submitted() ? this.problem() : null;
  });
  readonly facts = computed(() => testFacts(this.result()?.outcome?.needsConfirmation?.data));
  readonly confirmationTitle = computed(() => testConfirmTitle(this.result()?.outcome ?? null, null));

  current(request: FileBrowseRequest): boolean {
    return request.key === this.key() && request === this.result()?.request;
  }

  load(path = this.address(), dispatch: (request: FileBrowseRequest) => void = (request) => this.browse.emit(request)): void {
    if (this.busy() || !path.trim()) return;
    const selection = this.selection();
    if (!this.form().submit([selection.rootPointer, selection.pointer])) return;
    this.address.set(path);
    const request = { key: this.key(), path, value: this.value() };
    this.start(request);
    dispatch(request);
  }

  start(request: FileBrowseRequest): boolean {
    if (request.key !== this.key()) return false;
    this.result.set({ request, state: 'loading', directory: null, outcome: null, message: null });
    return true;
  }

  complete(request: FileBrowseRequest, outcome: TestOutcome | null, error: string | null = null): boolean {
    if (!this.current(request)) return false;
    const directory = directoryOf(outcome);
    this.result.set({ request, state: directory ? 'ready' : outcome?.needsConfirmation ? 'confirmation' : 'error', directory, outcome,
      message: error ?? outcome?.error?.message ?? outcome?.needsConfirmation?.text ?? (directory ? null : 'The server did not return a folder. Try again.') });
    return true;
  }

  confirm(): void {
    const result = this.result();
    const confirmationId = result?.outcome?.confirmationId;
    if (!result || !confirmationId || !this.current(result.request)) return;
    const request = result.request;
    this.start(request);
    this.trust.emit({ request, confirmationId });
  }

  chooseRoot(): void {
    const directory = this.directory();
    if (!directory || directory.path === this.root()) return;
    const selection = this.selection();
    // Changing the scope clears previous file permissions; file choices never imply directory-wide access.
    this.form().model.update((model) => setAt(setAt(model, selection.rootPath, directory.path), selection.path, []));
  }

  relative(entry: RemoteEntry): string | null { return relativeFile(this.root(), entry.path); }

  selectedEntry(entry: RemoteEntry): boolean {
    const relative = this.relative(entry);
    return relative !== null && this.selected().includes(relative);
  }

  select(entry: RemoteEntry, checked: boolean): void {
    if (this.result()?.state !== 'ready' || entry.type !== 'file' || !this.directory()?.entries.includes(entry)) return;
    const relative = this.relative(entry);
    if (relative === null) return;
    const selected = this.selected();
    this.setFiles(checked ? [...new Set([...selected, relative])] : selected.filter((path) => path !== relative));
  }

  remove(path: string): void { this.setFiles(this.selected().filter((value) => value !== path)); }

  private setFiles(paths: string[]): void {
    const node = this.form().compiled.all.find((field) => field.pointer === this.selection().pointer);
    if (node) this.form().set(node, paths);
  }

  reset(): void { this.result.set(null); this.address.set(this.root() || '/var/log'); }
}
