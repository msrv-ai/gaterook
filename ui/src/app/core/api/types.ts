/** Shared shapes of the admin API (ARCHITECTURE.md §6). Timestamps are ISO-8601 strings (Java `Instant`). */

/** Any JSON value (a Java `JsonNode`). */
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };

/** ISO-8601 instant, e.g. `2026-10-22T12:30:00Z`. */
export type Iso = string;

/** An environment (connector-api `Env`). */
export type Env = 'dev' | 'stage' | 'prod';

/** A tool mode on an endpoint, as the API writes it; the UI shows Off · Read only · Approval · Bypass. */
export type ToolMode = 'OFF' | 'READ_ONLY' | 'APPROVAL' | 'BYPASS';

/** MCP tool annotations (always explicit on built-in tools). */
export interface ToolAnnotations {
  readonly readOnlyHint: boolean | null;
  readonly destructiveHint: boolean | null;
  readonly idempotentHint: boolean | null;
  readonly openWorldHint: boolean | null;
}

/** Decisions of the audit (BLUEPRINT §7). */
export type Decision = 'allowed' | 'approved' | 'pending' | 'denied' | 'rejected' | 'expired' | 'error';

/** The optional reason every Setup-change-writing mutation accepts (≤ 500 characters). */
export interface WithReason {
  readonly reason?: string | null;
}

/** A page of a cursor-paginated list. */
export interface Cursor {
  readonly cursor?: string | null;
  readonly limit?: number;
}
