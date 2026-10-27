import { PluginCheck } from '../../../core/api/plugins.api';
import { JsonObject, JsonValue } from '../../../core/api/types';
import { formatBytes, joinAnd } from '../connectors-view';

/**
 * The review of a staged plugin in the kit's words (`dlg-review-clickhouse`, `dlg-review-rabbitmq`): what the
 * package is, what its manifest declares (tools, the connection form, the hosts it needs) and the trust it carries.
 */
export interface PluginFacts {
  readonly id: string;
  readonly name: string;
  readonly version: string;
  readonly signed: boolean;
  readonly file: string;
  readonly size: string;
  readonly sha256: string;
  readonly toolNames: string[];
  /** Every tool declares `readOnlyHint` (ClickHouse: "6, all read only"). */
  readonly allReadOnly: boolean;
  /** "Host, port, virtual host, user and password, from its config schema". */
  readonly connectionForm: string | null;
  /** "The host of each connection, port 15672". */
  readonly access: string;
  /** The core said this system has no sandbox for the plugin process (`report.sandbox` null): the review says so. */
  readonly unsandboxed: boolean;
}

function isObject(v: JsonValue | undefined): v is JsonObject {
  return !!v && typeof v === 'object' && !Array.isArray(v);
}

function strings(v: JsonValue | undefined): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
}

/** `{connection.host}:15672` → "the host of each connection, port 15672"; other hosts as they are. */
export function accessText(access: JsonValue): string {
  const hosts = isObject(access) ? strings(access['hosts']) : [];
  const commands = isObject(access) ? strings(access['commands']) : [];
  const files = isObject(access) ? strings(access['files']) : [];
  const ports: string[] = [];
  const named: string[] = [];
  for (const h of hosts) {
    const m = /^\{connection\.host\}(?::(\d+))?$/.exec(h);
    if (m) {
      if (m[1]) ports.push(m[1]);
      else ports.push('');
    } else {
      named.push(h);
    }
  }
  const parts: string[] = [];
  if (ports.length) {
    const numbered = ports.filter(Boolean);
    parts.push(
      numbered.length
        ? `The host of each connection, ${numbered.length === 1 ? 'port' : 'ports'} ${joinAnd(numbered)}`
        : 'The host of each connection',
    );
  }
  if (named.length) parts.push(joinAnd(named));
  if (commands.length) parts.push(`the commands ${joinAnd(commands)}`);
  if (files.length) parts.push(`the files ${joinAnd(files)}`);
  if (!parts.length) return 'Nothing beyond its connections';
  const text = parts.join('; ');
  return text[0].toUpperCase() + text.slice(1);
}

/** The connection form in words: a sentence from the core, or its field titles. */
export function connectionFormText(preview: JsonValue): string | null {
  if (typeof preview === 'string') return preview.trim() || null;
  const fields = Array.isArray(preview)
    ? preview.map((f) => (typeof f === 'string' ? f : isObject(f) && typeof f['title'] === 'string' ? f['title'] : null)).filter((f): f is string => !!f)
    : [];
  if (!fields.length) return null;
  const words = fields.map((f, i) => (i === 0 ? f : f.toLowerCase()));
  return `${joinAnd(words)}, from its config schema`;
}

function readOnly(declared: JsonValue): boolean {
  return isObject(declared) && declared['readOnlyHint'] === true;
}

/** The file name the core reports, else the name of the JAR as it was chosen or fetched. */
export function pluginFacts(check: PluginCheck, source: string | null): PluginFacts {
  const r = check.report;
  const file = r.file || (source ? (source.split(/[\\/]/).at(-1) ?? source) : `${r.id}-${r.version}.jar`);
  return {
    id: r.id,
    name: r.name || r.id,
    version: r.version,
    signed: r.signed,
    file,
    size: formatBytes(r.size),
    sha256: r.sha256,
    toolNames: r.tools.map((t) => t.name),
    allReadOnly: r.tools.length > 0 && r.tools.every((t) => readOnly(t.declared)),
    connectionForm: connectionFormText(r.configPreview),
    access: accessText(r.access),
    // null: no sandbox here; a name: the sandbox it runs in; absent: a core that doesn't say (nothing is claimed).
    unsandboxed: r.sandbox === null,
  };
}
