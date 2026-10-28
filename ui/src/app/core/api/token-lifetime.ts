/** A fixed lifetime covering at least six calendar months, including the longest six-month interval. */
export const DEFAULT_TOKEN_TTL_DAYS = 184;

/** Named-token lifetime choices supported by Settings and the client connection API. */
export const TOKEN_TTL_DAYS = [1, 7, 30, 90, DEFAULT_TOKEN_TTL_DAYS] as const;

/** Accept a saved preset, retaining the default when settings have not loaded. */
export function tokenTtlDays(value: unknown): number {
  return typeof value === 'number' && TOKEN_TTL_DAYS.some((days) => days === value) ? value : DEFAULT_TOKEN_TTL_DAYS;
}
