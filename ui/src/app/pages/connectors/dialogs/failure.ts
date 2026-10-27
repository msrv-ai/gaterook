import { isApiError } from '../../../core/api/api-error';

/**
 * What a refused or failed action says in its dialog or toast: the core's own words when it sent them (problem+json
 * `detail`), a plain "not in this build yet" for a route of a step that isn't merged, and what to do otherwise.
 */
export function failureText(e: unknown, notAvailable = 'This build of the gateway core can’t do this yet.'): string {
  if (isApiError(e)) {
    if (e.kind === 'not-available') return notAvailable;
    if (e.kind === 'network') return 'The core didn’t answer. Check that MCP Gateway is running, then try again.';
    return e.detail;
  }
  return e instanceof Error ? e.message : 'Something went wrong. Try again.';
}

/** The technical side of a failed load, for the code block under Details. */
export function failureDetails(request: string, e: unknown): string {
  if (isApiError(e)) return `${request} → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
  return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
}
