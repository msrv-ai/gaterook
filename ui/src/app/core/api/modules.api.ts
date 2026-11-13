import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Iso, WithReason } from './types';

/** A connector type a module adds, as its card shows it before the module is installed. */
export interface ModuleType {
  readonly id: string;
  readonly name: string;
  readonly icon: string | null;
  readonly group: string;
  readonly description: string;
  readonly tools: number;
}

/**
 * One of GateRook's own connectors that isn't part of the installer. It installs by itself when something needs one
 * of its connector types; nobody reviews or confirms it.
 */
export interface ConnectorModule {
  readonly id: string;
  readonly name: string;
  readonly version: string;
  /** `ready`, `installing`, `missing`, `available`, `failed`, `revoked` or `disabled`. */
  readonly state: string;
  /** The step of a running installation (`runtime`, `downloading`, `verifying`, `installing`), else null. */
  readonly step: string | null;
  readonly sizeBytes: number;
  /** Why the last installation failed, in the core's words; else null. */
  readonly message: string | null;
  readonly types: ModuleType[];
  readonly installed: boolean;
  /** The version kept for Roll back (a week after an update), else null. */
  readonly previousVersion?: string | null;
  /**
   * A higher version the list has for an installed module: it installs by itself unless it was rolled back from. For a
   * module whose installed build is the person's own: the version the list has of it, whatever it is.
   */
  readonly listedVersion?: string | null;
  /** False only for a module a person installed from a file that GateRook's signed list doesn't pin: their own. */
  readonly signed?: boolean;
}

export interface ConnectorModules {
  /** `problem`: why the list couldn't be checked or why nothing installs from it, in the core's words; else null. */
  readonly channel: { readonly configured: boolean; readonly checkedAt: Iso | null; readonly offline: boolean; readonly problem: string | null };
  readonly modules: ConnectorModule[];
}

/**
 * GateRook's connector modules (`/api/modules`). An installation is followed by the `module.state` events. A module's
 * package from a file is checked and installed with the plugins API (`PluginsApi.checkFile`, `install`).
 */
@Injectable({ providedIn: 'root' })
export class ModulesApi {
  private readonly api = inject(ApiClient);

  list(): Promise<ConnectorModules> {
    return this.api.get('/api/modules');
  }

  /** Starts the installation of a module, by its id or by a connector type it adds; answers at once. */
  install(idOrType: string): Promise<ConnectorModule> {
    return this.api.post(ApiClient.path`/api/modules/${idOrType}/install`, {});
  }

  /** Checks the list again now; the updates it calls for start by themselves. */
  refresh(): Promise<ConnectorModules> {
    return this.api.post('/api/modules/refresh', {});
  }

  /** Returns a module to the version it had before; an update then installs only a later version. */
  rollback(id: string, reason?: string): Promise<ConnectorModule> {
    return this.api.post(ApiClient.path`/api/modules/${id}/rollback`, { reason: reason ?? null });
  }

  /** Replaces a person's own build of a module with the one GateRook's list has; answers at once. */
  replaceOwn(id: string): Promise<ConnectorModule> {
    return this.api.post(ApiClient.path`/api/modules/${id}/replace-own`, {});
  }

  /** Removes an installed module with its connector types; 409 while connections use one of them. */
  uninstall(id: string, body: WithReason = {}): Promise<void> {
    return this.api.delete(ApiClient.path`/api/modules/${id}`, body);
  }
}
