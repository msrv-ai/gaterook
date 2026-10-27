import { Injectable, inject } from '@angular/core';
import { EventsPage } from './activity.api';
import { ApiClient } from './api-client';
import { JsonObject, JsonValue, ToolAnnotations, ToolMode } from './types';

export type Era = '2026-07-28' | '2025-11-25';

export interface PlaygroundTool {
  readonly name: string;
  readonly title: string;
  readonly description: string;
  /**
   * The strictest mode a call of the tool meets on the endpoint (`APPROVAL` when any of its pairs is in Approval);
   * `ALWAYS` for `approval_status`, which has no mode (playground.PlaygroundService.Tool).
   */
  readonly mode: ToolMode | 'ALWAYS';
  /** Off blocks Run, with `reason`. */
  readonly runnable: boolean;
  readonly reason: string | null;
  /** A call may wait for a person: Approval mode, or a tool that changes data (an `ssh_run` runbook not marked read only). */
  readonly needsApproval: boolean;
  readonly connections: string[];
  readonly annotations: ToolAnnotations;
  readonly declaredBy: string | null;
  readonly inputSchema: JsonObject;
}

/** `GET /api/playground/tools?endpoint=`: every tool and what `tools/list` of the era sends. */
export interface PlaygroundTools {
  readonly endpoint: string;
  readonly era: Era;
  readonly tools: PlaygroundTool[];
  readonly wirePreview: JsonObject;
}

/** What a Playground call came to: Structured, Text and JSON-RPC tabs. */
export interface PlaygroundCallView {
  /** The call's audit id; null when the answer has none (a JSON-RPC error before the call, `PlaygroundService`). */
  readonly callId: string | null;
  readonly wire: { readonly request: JsonObject; readonly response: JsonObject; readonly httpStatus: number };
  readonly structured: JsonValue;
  readonly text: string | null;
  readonly isError: boolean;
  readonly meta: { readonly durationMs: number; readonly bytes: number; readonly masked: number; readonly truncated: boolean };
  readonly approval: { readonly handle: string; readonly status: string } | null;
}

@Injectable({ providedIn: 'root' })
export class PlaygroundApi {
  private readonly api = inject(ApiClient);

  tools(endpoint: string, era?: Era | null): Promise<PlaygroundTools> {
    return this.api.get('/api/playground/tools', { endpoint, era });
  }

  /** Through the same pipeline as an agent (principal `playground`); a mutation becomes a request. */
  call(request: { readonly endpoint: string; readonly tool: string; readonly arguments: JsonObject; readonly era: Era }): Promise<PlaygroundCallView> {
    return this.api.post('/api/playground/call', request);
  }

  history(endpoint: string, cursor?: string | null, limit = 20): Promise<EventsPage> {
    return this.api.get('/api/playground/history', { endpoint, cursor, limit });
  }
}
