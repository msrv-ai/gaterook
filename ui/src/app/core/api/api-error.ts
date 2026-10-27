import { HttpErrorResponse } from '@angular/common/http';

/** One invalid field of a form, as the core reports it: a JSON Pointer of the request and a sentence. */
export interface ApiFieldError {
  readonly field: string;
  readonly message: string;
}

/**
 * The admin API's `application/problem+json` body (ARCHITECTURE.md §6, `admin.ApiProblem`): `title` says what went
 * wrong, `detail` what to do, `field` the JSON Pointer of the request member at fault, `confirm` the value to send
 * back to go ahead (`send-secret-to-new-host`, `danger`, `reset-invalid`).
 */
export interface ApiProblem {
  readonly type?: string;
  readonly title?: string;
  readonly detail?: string;
  readonly field?: string | null;
  readonly errors?: readonly ApiFieldError[];
  readonly confirm?: string;
  readonly locked?: string;
}

/**
 * What a failed admin call means to the UI:
 * - `network` — the core didn't answer (stopped, restarting): the status screen takes over;
 * - `unauthorized` — 401, the launch signature was refused (only a broken proxy or a squatted port gets this);
 * - `locked` — 503 in the locked mode (master key missing or invalid, §7.1);
 * - `not-available` — 404 without a problem body: this build of the core has no such route yet (an area whose
 *   backend step is not merged); pages show their "not available yet" state, never fake data;
 * - `not-found`, `conflict` (409: stale version, already decided), `unprocessable` (422: a rule refused it, with
 *   `field`), `bad-request`, `server`.
 */
export type ApiErrorKind =
  | 'network'
  | 'unauthorized'
  | 'locked'
  | 'not-available'
  | 'not-found'
  | 'conflict'
  | 'unprocessable'
  | 'bad-request'
  | 'server';

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number;
  readonly title: string;
  readonly detail: string;
  /** JSON Pointer of the request member at fault, if the core named one. */
  readonly field: string | null;
  readonly errors: readonly ApiFieldError[];
  readonly confirm: string | null;
  /**
   * The whole problem+json body as the core sent it, members beyond {@link ApiProblem} included (K1's 422
   * `{reason, line, column, fragment}` of a config file it won't edit). `null` without a problem body. Read it
   * through a typed accessor of the area's API service (`configEditProblem()`), never by string keys in pages.
   */
  readonly problem: Readonly<Record<string, unknown>> | null;

  constructor(kind: ApiErrorKind, status: number, problem: ApiProblem, body: Readonly<Record<string, unknown>> | null = null) {
    const title = problem.title ?? defaultTitle(kind);
    const detail = problem.detail ?? defaultDetail(kind);
    super(`${title}: ${detail}`);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.title = title;
    this.detail = detail;
    this.field = problem.field ?? null;
    this.errors = problem.errors ?? (problem.field ? [{ field: problem.field, message: detail }] : []);
    this.confirm = problem.confirm ?? null;
    this.problem = body;
  }

  /** The message for one form field (JSON Pointer), if the core reported one for it. */
  messageFor(pointer: string): string | null {
    return this.errors.find((e) => e.field === pointer)?.message ?? null;
  }

  static fromHttp(error: HttpErrorResponse): ApiError {
    const status = error.status;
    const problem = problemOf(error.error);
    const body = problem as Readonly<Record<string, unknown>> | null;
    const make = (kind: ApiErrorKind) => new ApiError(kind, status, problem ?? {}, body);
    if (status === 0) return new ApiError('network', 0, {});
    if (status === 401) return make('unauthorized');
    if (status === 503 && (problem?.locked || !problem)) return make('locked');
    if (status === 404) return make(problem ? 'not-found' : 'not-available');
    if (status === 409) return make('conflict');
    if (status === 422) return make('unprocessable');
    if (status >= 400 && status < 500) return make('bad-request');
    return make('server');
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

/** A route this core build doesn't have yet (its backend step isn't merged): show "not available yet". */
export function isNotAvailable(e: unknown): boolean {
  return isApiError(e) && e.kind === 'not-available';
}

function problemOf(body: unknown): ApiProblem | null {
  if (body && typeof body === 'object' && ('title' in body || 'detail' in body || 'type' in body)) {
    return body as ApiProblem;
  }
  if (typeof body === 'string' && body.trim().startsWith('{')) {
    try {
      return problemOf(JSON.parse(body));
    } catch {
      return null;
    }
  }
  return null;
}

function defaultTitle(kind: ApiErrorKind): string {
  switch (kind) {
    case 'network':
      return "Can't reach the gateway core";
    case 'unauthorized':
      return 'The core refused the request';
    case 'locked':
      return 'GateRook is locked';
    case 'not-available':
      return 'Not available yet';
    case 'not-found':
      return 'Not found';
    case 'conflict':
      return 'Changed meanwhile';
    case 'unprocessable':
      return "Can't do that";
    case 'bad-request':
      return 'Bad request';
    case 'server':
      return 'The core failed';
  }
}

function defaultDetail(kind: ApiErrorKind): string {
  switch (kind) {
    case 'network':
      return 'The core on 127.0.0.1 did not answer. It may be starting or stopped.';
    case 'unauthorized':
      return 'The request was not signed by this app. Restart GateRook.';
    case 'locked':
      return 'The master key is missing or invalid. Restore it from a backup or create a new key.';
    case 'not-available':
      return 'This version of the gateway core does not have this part yet.';
    case 'not-found':
      return 'It may have been removed. Reload the page.';
    case 'conflict':
      return 'Someone changed this in the meantime. Reload and try again.';
    case 'unprocessable':
      return 'The gateway refused this change.';
    case 'bad-request':
      return 'The gateway did not understand this request.';
    case 'server':
      return 'See the core log for details, then try again.';
  }
}
