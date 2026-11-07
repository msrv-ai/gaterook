/** Keep reference-test feedback readable, including answers from older cores that list every sibling key. */
export function compactReferenceError(error: string | null | undefined, fallback: string): string {
  const diagnostic = error?.trim() ?? '';
  const legacyKeys = diagnostic.match(/^(.* has no key "[^"\r\n]*"\.) Keys: /s);
  const message = legacyKeys
    ? `${legacyKeys[1]} Check the key after # in the reference.`
    : diagnostic;
  // A path, key name or aggregated store failure can itself be very long. Keep the actionable summary intact.
  return message && message.length <= 320 ? message : fallback;
}
