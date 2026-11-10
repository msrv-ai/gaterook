import { ImportCandidate } from '../../core/api/connection-imports.api';
import { SecretInput, SecretInputs } from '../../core/api/connections.api';

type StagedSource = NonNullable<ImportCandidate['secretSources']>[number];

/**
 * One credential the import would use for a candidate, said without its value. A credential the AI prepared is a
 * literal from the repository, a local file, an external reference or a dynamic role; one the person entered or chose
 * in the editor replaces it.
 */
export interface CredentialSource {
  readonly field: string;
  readonly kind: 'literal' | 'file' | 'reference' | 'dynamic' | 'entered' | 'chosen';
  readonly label: string;
  /** Where it comes from: the file and its key, the reference and its store. */
  readonly details: readonly string[];
}

function staged(source: StagedSource): CredentialSource {
  const store = source.store ? `store ${source.store}` : 'store chosen when you import';
  switch (source.source) {
    case 'file':
      return { field: source.field, kind: 'file', label: 'Local file, read when you import',
        details: [source.path ?? '', ...(source.jsonPointer ? [`key ${source.jsonPointer}`] : [])].filter(Boolean) };
    case 'reference':
      return { field: source.field, kind: 'reference', label: 'External reference', details: [source.reference ?? '', store] };
    case 'vault-dynamic':
      return { field: source.field, kind: 'dynamic', label: 'Short-lived credentials from a Vault role', details: [source.reference ?? '', store] };
    default:
      return { field: source.field, kind: 'literal', label: 'Value from the repository, stored encrypted', details: [] };
  }
}

function replacement(field: string, input: SecretInput): CredentialSource {
  if (input.source === 'local') return { field, kind: 'entered', label: 'Entered by you, stored encrypted', details: [] };
  return { field, kind: 'chosen', label: input.source === 'vault-dynamic' ? 'Vault role you chose' : 'Reference you chose',
    details: [input.ref, ...(input.store ? [`store ${input.store}`] : [])] };
}

/** The credentials a candidate would use: the sources the AI prepared, except where the person replaced one. */
export function credentialSources(prepared: readonly StagedSource[] | undefined, replacements: SecretInputs): readonly CredentialSource[] {
  const rows = new Map<string, CredentialSource>();
  for (const source of prepared ?? []) rows.set(source.field, staged(source));
  for (const [field, input] of Object.entries(replacements)) rows.set(field, replacement(field, input));
  return [...rows.values()];
}

/**
 * Why a candidate has no destination until the person chooses one, or `null` when it can start selected. A credential
 * the AI prepared from a local file or an external store is read when the person imports, and presented to the host
 * the proposal named: the person sees that host and those sources first and decides where it goes.
 */
export function choiceReason(sources: readonly CredentialSource[]): string | null {
  const file = sources.some(source => source.kind === 'file');
  const store = sources.some(source => source.kind === 'reference' || source.kind === 'dynamic');
  if (!file && !store) return null;
  const from = file && store ? 'a local file and an external secret store' : file ? 'a local file' : 'an external secret store';
  return `Not selected: the AI prepared a credential from ${from}. Check the target and credential sources, then choose a destination.`;
}
