import { Injectable, signal } from '@angular/core';
import { Env, JsonObject, ToolMode } from '../api/types';

export interface EndpointShareExport {
  readonly endpointSlug: string;
  readonly connectionNames: readonly string[];
  readonly includeSecrets: boolean;
}

export interface EndpointShareTool {
  readonly toolKey: string;
  readonly mode: ToolMode;
  readonly overrides: JsonObject | null;
}

/** Only metadata approved for display; decrypted credentials stay in the desktop process. */
export interface EndpointSharePreview {
  readonly endpoint: {
    readonly slug: string;
    readonly description: string | null;
    readonly access: 'standard' | 'read-only';
    readonly instructions: string | null;
    readonly settings: JsonObject;
  };
  readonly suggestedSlug: string;
  readonly connections: readonly {
    readonly id: string;
    readonly name: string;
    readonly suggestedName: string;
    readonly type: string;
    readonly config: JsonObject;
    readonly templateCount: number;
    readonly env: Env;
    readonly secretCount: number;
    readonly omittedSecretFields: readonly string[];
    readonly supported: boolean;
    readonly warnings: readonly string[];
    readonly tools: readonly EndpointShareTool[];
  }[];
  readonly coreTools: readonly EndpointShareTool[];
  readonly warnings: readonly string[];
}

export interface EndpointShareReview {
  readonly handle: string;
  readonly preview: EndpointSharePreview;
}

export interface EndpointShareImport {
  readonly handle: string;
  readonly connectionIds: readonly string[];
  readonly includeEndpoint: boolean;
  readonly includeSecrets: boolean;
  readonly endpointSlug?: string;
  readonly reason?: string;
  readonly confirm?: 'danger';
}

export interface EndpointShareResult {
  readonly endpointSlug: string | null;
  readonly connections: readonly { readonly id: string; readonly name: string; readonly needsSetup: boolean }[];
  readonly warnings: readonly string[];
}

/** Permission review from the core; no decrypted configuration or secret values cross this boundary. */
export interface EndpointShareConfirmation {
  readonly confirm: 'danger';
  readonly status: 409;
  readonly title: string;
  readonly detail: string;
  readonly errors: readonly { readonly field: string; readonly message: string }[];
}

export type EndpointShareImportOutcome = EndpointShareResult | { readonly confirmationRequired: EndpointShareConfirmation };

/** A display name and revision only: the desktop owns paths, file bytes and decrypted handles. */
@Injectable({ providedIn: 'root' })
export class EndpointShareFiles {
  private revision = 0;
  readonly selected = signal<{ readonly fileName: string; readonly revision: number } | null>(null);

  select(file: { readonly fileName: string }): void {
    this.selected.set({ fileName: file.fileName, revision: ++this.revision });
  }

  clear(): void {
    this.selected.set(null);
  }
}
