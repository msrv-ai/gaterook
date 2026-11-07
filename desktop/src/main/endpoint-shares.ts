// Native file selection and authenticated sharing. Only sanitized previews cross into the renderer.
import type { OpenDialogOptions, OpenDialogReturnValue, SaveDialogOptions, SaveDialogReturnValue } from 'electron';
import { randomUUID } from 'node:crypto';
import { lstatSync, realpathSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, sep, win32 } from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeFileAtomic } from './atomic-file.ts';
import { callCore, callCoreOk } from './core-client.ts';
import type { ReadyCore } from './core-supervisor.ts';
import { readBackupFile } from './full-backup-files.ts';
import { decryptEndpointShare, encryptEndpointShare, MAX_SHARE_BUNDLE_BYTES, MAX_SHARE_BYTES, validateSharePassphrase } from './endpoint-share-archive.ts';
import type { EndpointShareConfirmation, EndpointShareExport, EndpointShareImport, EndpointShareFile } from '../shared/contract.ts';

const filters = [{ name: 'Encrypted GateRook endpoint share', extensions: ['grshare'] }];
const REVIEW_MS = 15 * 60 * 1000;
interface Review { handle: string; payload: Buffer; instanceNonce: string; timer: ReturnType<typeof setTimeout> }
export interface EndpointShareHost {
  home: string;
  core: () => ReadyCore | null;
  saveDialog: (options: SaveDialogOptions) => Promise<SaveDialogReturnValue>;
  openDialog: (options: OpenDialogOptions) => Promise<OpenDialogReturnValue>;
}

/** OS activation may name a file, but cannot read it or commit an import. */
export function endpointSharePath(value: unknown, platform: NodeJS.Platform = process.platform): string | null {
  const absolute = platform === 'win32' ? win32.isAbsolute : isAbsolute;
  return typeof value === 'string' && value.length <= 32_768 && !/[\x00-\x1f]/.test(value)
    && absolute(value) && value.toLowerCase().endsWith('.grshare') ? value : null;
}
export function endpointShareInArgv(argv: readonly string[], platform: NodeJS.Platform = process.platform): string | null {
  for (const arg of argv) {
    const file = endpointSharePath(arg, platform);
    if (file) return file;
    // Linux desktop entries use %U, so local files can arrive as encoded file URLs. No URL is accepted by IPC.
    if (!/^file:\/\//i.test(arg) || arg.length > 32_768 || /[\x00-\x1f?#]/.test(arg)) continue;
    try {
      const url = new URL(arg);
      if (url.protocol !== 'file:' || url.hostname || url.username || url.password || url.port
          || url.search || url.hash || url.pathname.startsWith('//')) continue;
      const decoded = endpointSharePath(fileURLToPath(url, { windows: platform === 'win32' }), platform);
      if (decoded) return decoded;
    } catch { /* Invalid encodings, authorities and escaped path separators are not file selections. */ }
  }
  return null;
}
function destination(home: string, file: string): string {
  if (!endpointSharePath(file)) throw new Error('Use the .grshare file extension.');
  const target = join(realpathSync(dirname(file)), basename(file));
  const root = realpathSync(home);
  const fold = (path: string): string => process.platform === 'linux' ? path : path.normalize('NFC').toLowerCase();
  if (fold(target).startsWith(fold(root) + sep)) throw new Error('Save the endpoint share outside the GateRook data folder.');
  try {
    const stat = lstatSync(target);
    if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Choose a regular destination file.');
  } catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
  return target;
}
function exportOptions(value: unknown): EndpointShareExport {
  const raw = value as Partial<EndpointShareExport> | null;
  if (!raw || typeof raw.endpointSlug !== 'string' || raw.endpointSlug.length > 200
      || !Array.isArray(raw.connectionNames) || raw.connectionNames.length > 100
      || !raw.connectionNames.every((name) => typeof name === 'string' && name.length <= 200)
      || typeof raw.includeSecrets !== 'boolean') throw new Error('Choose an endpoint and its connections.');
  return { endpointSlug: raw.endpointSlug, connectionNames: raw.connectionNames, includeSecrets: raw.includeSecrets };
}
function importOptions(value: unknown): EndpointShareImport {
  const raw = value as Partial<EndpointShareImport> | null;
  if (!raw || typeof raw.handle !== 'string' || !Array.isArray(raw.connectionIds) || raw.connectionIds.length > 100
      || !raw.connectionIds.every((id) => typeof id === 'string' && id.length <= 200)
      || typeof raw.includeEndpoint !== 'boolean' || typeof raw.includeSecrets !== 'boolean'
      || (raw.endpointSlug !== undefined && (typeof raw.endpointSlug !== 'string' || raw.endpointSlug.length > 200))
      || (raw.reason !== undefined && (typeof raw.reason !== 'string' || raw.reason.length > 500))
      || (raw.confirm !== undefined && raw.confirm !== 'danger')) {
    throw new Error('Choose what to import from the reviewed file.');
  }
  return { handle: raw.handle, connectionIds: raw.connectionIds, includeEndpoint: raw.includeEndpoint,
    includeSecrets: raw.includeSecrets, ...(raw.endpointSlug !== undefined ? { endpointSlug: raw.endpointSlug } : {}),
    ...(raw.reason !== undefined ? { reason: raw.reason } : {}),
    ...(raw.confirm === 'danger' ? { confirm: 'danger' } : {}) };
}

/** The core's preflight warning contains display text, never configuration or secret values. */
function importConfirmation(value: unknown): EndpointShareConfirmation | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const problem = value as Record<string, unknown>;
  if (problem['confirm'] !== 'danger' || typeof problem['title'] !== 'string'
      || typeof problem['detail'] !== 'string' || !Array.isArray(problem['errors'])
      || problem['errors'].length > 100) return null;
  const errors: { field: string; message: string }[] = [];
  for (const entry of problem['errors']) {
    if (!entry || typeof entry !== 'object' || typeof entry.field !== 'string'
        || typeof entry.message !== 'string') return null;
    errors.push({ field: entry.field.slice(0, 1_000), message: entry.message.slice(0, 16_000) });
  }
  // Do not forward arbitrary ProblemDetail extensions across IPC.
  return { confirm: 'danger', status: 409, title: problem['title'].slice(0, 200),
    detail: problem['detail'].slice(0, 64_000), errors };
}

export class EndpointShares {
  private selected: string | null = null;
  private review: Review | null = null;
  private revision = 0;
  private busy = false;
  private readonly host: EndpointShareHost;
  constructor(host: EndpointShareHost) { this.host = host; }

  isBusy(): boolean { return this.busy; }
  invalidateReview(): void { this.clearReview(); this.revision++; }
  selectedFile(): EndpointShareFile | null { return this.selected ? { fileName: basename(this.selected) } : null; }
  openFile(path: unknown): EndpointShareFile | null {
    const file = endpointSharePath(path);
    if (!file) return null;
    this.discard();
    this.selected = file;
    return this.selectedFile();
  }
  /** A stale page cannot discard a newer OS-selected file. */
  discard(handle?: unknown): void {
    if (handle !== undefined && this.review?.handle !== handle) return;
    this.clearReview();
    this.selected = null;
    this.revision++;
  }
  private clearReview(): void {
    if (this.review) { clearTimeout(this.review.timer); this.review.payload.fill(0); this.review = null; }
  }
  coreChanged(): void {
    const core = this.host.core();
    if (this.review && (!core || core.locked || core.instanceNonce !== this.review.instanceNonce)) this.clearReview();
  }
  private ready(): ReadyCore {
    const core = this.host.core();
    if (!core || core.locked) throw new Error('Wait for GateRook to start and unlock its master key, then try again.');
    return core;
  }
  private current(core: ReadyCore, revision: number): void {
    if (this.host.core()?.instanceNonce !== core.instanceNonce || revision !== this.revision) {
      throw new Error('The selected file or GateRook changed. Review the file again.');
    }
  }
  private async run<T>(work: () => Promise<T>): Promise<T> {
    if (this.busy) throw new Error('An endpoint share operation is already running.');
    this.busy = true;
    try { return await work(); } finally { this.busy = false; }
  }
  choose(): Promise<EndpointShareFile | null> {
    return this.run(async () => {
      const choice = await this.host.openDialog({ title: 'Import endpoint share', filters, properties: ['openFile'] });
      if (choice.canceled || !choice.filePaths[0]) return null;
      return this.openFile(choice.filePaths[0]);
    });
  }
  create(raw: unknown, passphrase: unknown): Promise<{ cancelled: boolean; fileName?: string }> {
    return this.run(async () => {
      validateSharePassphrase(passphrase);
      const options = exportOptions(raw);
      const core = this.ready();
      const chosen = await this.host.saveDialog({ title: 'Share endpoint', filters,
        defaultPath: `gaterook-${options.endpointSlug.replace(/[^a-zA-Z0-9_-]/g, '-')}.grshare`,
        properties: ['createDirectory', 'showOverwriteConfirmation'] });
      if (chosen.canceled || !chosen.filePath) return { cancelled: true };
      const path = destination(this.host.home, chosen.filePath);
      let payload: Buffer | null = null;
      let encrypted: Buffer | null = null;
      try {
        const bundle = await callCoreOk(core, 'POST', '/internal/endpoint-shares/export', options);
        if (this.host.core()?.instanceNonce !== core.instanceNonce) throw new Error('GateRook restarted. Try sharing again.');
        payload = Buffer.from(JSON.stringify(bundle), 'utf8');
        if (payload.length > MAX_SHARE_BUNDLE_BYTES) throw new Error('The selected endpoint exceeds the 6 MiB sharing limit.');
        encrypted = await encryptEndpointShare(payload, passphrase);
        writeFileAtomic(path, encrypted);
        return { cancelled: false, fileName: basename(path) };
      } finally { payload?.fill(0); encrypted?.fill(0); }
    });
  }
  preview(passphrase: unknown): Promise<{ handle: string; preview: unknown }> {
    return this.run(async () => {
      validateSharePassphrase(passphrase);
      const core = this.ready();
      const path = this.selected;
      if (!path) throw new Error('Choose an endpoint share file first.');
      const revision = this.revision;
      this.clearReview();
      let encrypted: Buffer | null = null;
      let payload: Buffer | null = null;
      try {
        encrypted = readBackupFile(path, MAX_SHARE_BYTES);
        payload = await decryptEndpointShare(encrypted, passphrase);
        if (payload.length > MAX_SHARE_BUNDLE_BYTES) throw new Error('The endpoint share exceeds the 6 MiB sharing limit.');
        this.current(core, revision);
        let bundle: unknown;
        try { bundle = JSON.parse(payload.toString('utf8')); }
        catch { throw new Error('The endpoint share contains invalid data.'); }
        const preview = await callCoreOk(core, 'POST', '/internal/endpoint-shares/preview', { bundle });
        this.current(core, revision);
        const handle = randomUUID();
        const timer = setTimeout(() => { if (this.review?.handle === handle) this.clearReview(); }, REVIEW_MS);
        timer.unref();
        this.review = { handle, payload, instanceNonce: core.instanceNonce, timer };
        payload = null;
        return { handle, preview };
      } finally { encrypted?.fill(0); payload?.fill(0); }
    });
  }
  import(raw: unknown): Promise<unknown> {
    return this.run(async () => {
      const options = importOptions(raw);
      const core = this.ready();
      const review = this.review;
      if (!review || review.handle !== options.handle || review.instanceNonce !== core.instanceNonce) {
        throw new Error('This import review expired. Open and review the file again.');
      }
      const revision = this.revision;
      const { handle: _handle, ...selection } = options;
      let retryable = false;
      try {
        const bundle: unknown = JSON.parse(review.payload.toString('utf8'));
        // run() prevents concurrent writes. Only an authenticated preflight refusal permits a retry.
        const answer = await callCore(core, 'POST', '/internal/endpoint-shares/import', { bundle, ...selection });
        const confirmation = answer.status === 409 ? importConfirmation(answer.body) : null;
        if (confirmation) {
          this.current(core, revision);
          if (this.review !== review) throw new Error('The import review expired.');
          retryable = true;
          return { confirmationRequired: confirmation };
        }
        if (answer.status < 200 || answer.status >= 300) throw new Error('The import failed.');
        if (this.revision === revision) this.discard();
        return answer.body;
      } catch {
        // Transport errors and other failures may follow a write. Consume the review and omit raw response data.
        throw new Error('The endpoint share could not be imported. Open and review the file again before retrying.');
      } finally { if (!retryable && this.review === review) this.clearReview(); }
    });
  }
}
