import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Iso } from './types';

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
  /** `ready`, `installing`, `missing`, `available`, `failed` or `disabled`. */
  readonly state: string;
  /** The step of a running installation (`runtime`, `downloading`, `verifying`, `installing`), else null. */
  readonly step: string | null;
  readonly sizeBytes: number;
  /** Why the last installation failed, in the core's words; else null. */
  readonly message: string | null;
  readonly types: ModuleType[];
  readonly installed: boolean;
}

export interface ConnectorModules {
  /** `problem`: why the list couldn't be checked or why nothing installs from it, in the core's words; else null. */
  readonly channel: { readonly configured: boolean; readonly checkedAt: Iso | null; readonly offline: boolean; readonly problem: string | null };
  readonly modules: ConnectorModule[];
}

/** GateRook's connector modules (`/api/modules`). An installation is followed by the `module.state` events. */
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
}
