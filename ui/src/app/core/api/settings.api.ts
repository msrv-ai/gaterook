import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Iso, JsonObject, WithReason } from './types';

export type SettingsSectionName =
  | 'general'
  | 'appearance'
  | 'authorization'
  | 'approval-rules'
  | 'local-api'
  | 'audit'
  | 'backup';

/** One typed section of Settings (settings.SettingsService): values, a version for optimistic locking. */
export interface SettingsSection {
  readonly name: string;
  readonly values: JsonObject;
  readonly version: number;
  readonly updatedAt: Iso | null;
}

/** `GET /api/backup` (settings.DatabaseBackupJob.Status). */
export interface BackupStatus {
  readonly daily: boolean;
  readonly at: string;
  readonly lastBackupAt: Iso | null;
  readonly lastFile: string | null;
  readonly folder: string;
  readonly keep: number;
  readonly lastError: string | null;
  readonly lastAttemptAt: Iso | null;
  readonly nextRunAt: Iso | null;
}

/** `POST /api/backup/db` (settings.DatabaseBackupJob.Copy): the copy "Back up now" wrote. */
export interface BackupCopy {
  /** Where it was written: the backup folder, or the file of a save dialog. */
  readonly path: string;
  /** Its file name (`gateway-2026-10-22-1430.db`). */
  readonly file: string;
  readonly at: Iso;
  readonly sizeBytes: number;
}

/** An audit export destination (step K5): SIEM, S3 or BigQuery, each with its own schedule. */
export interface AuditExportDestination {
  readonly id: string;
  readonly kind: 'siem' | 's3' | 'bigquery';
  readonly name: string;
  readonly enabled: boolean;
  readonly config: JsonObject;
  /** `stream`, `daily@02:00` or `hourly`. */
  readonly schedule: string;
  readonly state: string | null;
  readonly failingSince: Iso | null;
  readonly lastAttemptAt: Iso | null;
  readonly lastError: string | null;
  /** Where it writes, as the core names it (`siem.acme.internal:6514`, `s3://acme-audit/mcp-gateway/`). */
  readonly target?: string | null;
  /** False: its row was changed outside GateRook; it sends nothing until it is saved again (Run → 409). */
  readonly verified?: boolean;
  /** One of the demo world's (`GW_DEMO=1`): shown, never called (Test and Run → 409). */
  readonly demo?: boolean;
  readonly version?: number;
}

@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly api = inject(ApiClient);

  all(): Promise<{ readonly [name: string]: SettingsSection }> {
    return this.api.get('/api/settings');
  }

  /** One section; `tunnel` is Settings › Tunnel (`admin.tunnel`), which `GET /api/settings` doesn't list. */
  get(section: SettingsSectionName | 'tunnel'): Promise<SettingsSection> {
    return this.api.get(ApiClient.path`/api/settings/${section}`);
  }

  /** The body is a JSON Merge Patch of the section plus `version` (409 when stale, 422 with a pointer). */
  update(section: SettingsSectionName | 'tunnel' | 'tunnel/callers', patch: JsonObject & { readonly version?: number } & WithReason): Promise<SettingsSection> {
    return this.api.put(`/api/settings/${section}`, patch);
  }

  backup(): Promise<BackupStatus> {
    return this.api.get('/api/backup');
  }

  /** Without a saveToken the copy goes to `backups/` and counts towards the 7 kept. */
  backupNow(saveToken?: string | null): Promise<BackupCopy> {
    return this.api.post('/api/backup/db', saveToken ? { saveToken } : {});
  }
}

@Injectable({ providedIn: 'root' })
export class AuditExportApi {
  private readonly api = inject(ApiClient);

  list(): Promise<AuditExportDestination[]> {
    return this.api.get('/api/audit-export');
  }

  create(destination: Omit<AuditExportDestination, 'id' | 'state' | 'failingSince' | 'lastAttemptAt' | 'lastError'> & WithReason): Promise<AuditExportDestination> {
    return this.api.post('/api/audit-export', destination);
  }

  update(id: string, destination: Partial<AuditExportDestination> & WithReason): Promise<AuditExportDestination> {
    return this.api.put(ApiClient.path`/api/audit-export/${id}`, destination);
  }

  delete(id: string, body: WithReason = {}): Promise<void> {
    return this.api.delete(ApiClient.path`/api/audit-export/${id}`, body);
  }

  test(id: string): Promise<AuditExportDestination> {
    return this.api.post(ApiClient.path`/api/audit-export/${id}/test`);
  }

  /** Export now / Retry. */
  run(id: string): Promise<AuditExportDestination> {
    return this.api.post(ApiClient.path`/api/audit-export/${id}/run`);
  }
}
