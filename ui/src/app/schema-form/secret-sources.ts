/**
 * The kit's words for each secret source (design/elements/connection-form.html "Secret source"): the card, and the
 * secret field it switches to. The core lists which sources exist and whether they can be used right now
 * (`secretSources` of the form spec); these are only how each one reads.
 */
export interface SecretSourceCopy {
  /** The value of the radio and `data-source` in the kit (local · vault · gcp · aws · dynamic). */
  readonly kit: string;
  readonly title: string;
  readonly icon: string;
  readonly description: string;
  /** URI scheme set in mono inside the description. */
  readonly scheme: string | null;
  readonly meta: string | null;
  /** The secret field for this source. */
  readonly label: string | null;
  readonly hint: string;
  readonly test: string | null;
}

export const SECRET_SOURCE_COPY: Readonly<Record<string, SecretSourceCopy>> = {
  local: {
    kit: 'local',
    title: 'Encrypted here',
    icon: 'lock-keyhole',
    description: 'Stored in the local database, encrypted with the master key. Write-only.',
    scheme: null,
    meta: 'Default',
    label: null,
    hint: 'Encrypted with the master key on save. Nobody can view it afterwards: Replace or Rotate only.',
    test: null,
  },
  vault: {
    kit: 'vault',
    title: 'HashiCorp Vault or OpenBao',
    icon: 'vault',
    description: 'Resolve a vault:// reference at call time.',
    scheme: 'vault://',
    meta: 'KV v2',
    label: 'Secret reference',
    hint: "Path and key in Vault or OpenBao, resolved at call time with the core's Vault token.",
    test: 'Test reference',
  },
  'gcp-sm': {
    kit: 'gcp',
    title: 'GCP Secret Manager',
    icon: 'cloud',
    description: "Resolve gcp-sm:// with the core's service account.",
    scheme: 'gcp-sm://',
    meta: 'Needs secretAccessor',
    label: 'Secret reference',
    hint: "Resolved at call time with the core's service account.",
    test: 'Test reference',
  },
  'aws-sm': {
    kit: 'aws',
    title: 'AWS Secrets Manager',
    icon: 'cloud',
    description: "Resolve aws-sm:// with the core's AWS profile.",
    scheme: 'aws-sm://',
    meta: 'Needs GetSecretValue',
    label: 'Secret reference',
    hint: "Resolved at call time with the core's AWS profile.",
    test: 'Test reference',
  },
  dynamic: {
    kit: 'dynamic',
    title: 'Short-lived (Vault dynamic credentials)',
    icon: 'timer',
    description: 'Vault creates a database user per call. Nothing is stored here.',
    scheme: null,
    meta: 'Recommended for prod',
    label: 'Vault role',
    hint: 'Vault creates a database user for each call. No user or password is stored here.',
    test: 'Test role',
  },
};

/** Copy for a source the kit doesn't know (a future store): its label from the core, a link icon. */
export function sourceCopy(id: string, label?: string | null): SecretSourceCopy {
  return (
    SECRET_SOURCE_COPY[id] ?? {
      kit: id,
      title: label ?? id,
      icon: 'link',
      description: 'Resolve a reference at call time.',
      scheme: null,
      meta: null,
      label: 'Secret reference',
      hint: 'Resolved at call time.',
      test: 'Test reference',
    }
  );
}

/** An example reference for the placeholder, shaped by the connection: `vault://kv/dev/dev-db#password`. */
export function referencePlaceholder(source: string, env: string, name: string, key: string): string {
  const n = name || `${env}-db`;
  switch (source) {
    case 'vault':
      return `vault://kv/${env}/${n}#${key}`;
    case 'gcp-sm':
      return `gcp-sm://projects/<project>/secrets/${n}-${key}/versions/latest`;
    case 'aws-sm':
      return `aws-sm://<region>/${env}/${n}#${key}`;
    case 'dynamic':
      return `database/creds/${env}-readonly`;
    default:
      return '';
  }
}
