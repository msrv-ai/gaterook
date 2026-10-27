#!/usr/bin/env node
// What the tracing agent saw that the image's metadata doesn't declare (report 15 §6, native.yml): the reflection
// types and resources of the agent's reachability-metadata.json missing from every metadata file under the roots
// (the modules' committed *-manual files, Spring AOT's output in app/target/spring-aot, the reachability repository
// the native build unpacked). A report for a person, who decides what goes into a module's *-manual metadata; the
// exit code is 0 unless the agent's output is missing.
//
//   node scripts/agent-diff.mjs <agent output dir> <root>...
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const NAMES = new Set(['reachability-metadata.json', 'reflect-config.json', 'resource-config.json', 'jni-config.json']);

function files(root, out = []) {
  if (!existsSync(root)) return out;
  for (const name of readdirSync(root)) {
    if (name === 'node_modules' || name === '.git') continue;
    const path = join(root, name);
    const stat = statSync(path);
    if (stat.isDirectory()) files(path, out);
    else if (NAMES.has(name)) out.push(path);
  }
  return out;
}

const typeName = (t) => (typeof t === 'string' ? t : t?.proxy ? `proxy:${t.proxy.join(',')}` : JSON.stringify(t));

// Both formats: reachability-metadata.json ({reflection, resources, jni}) and the legacy *-config.json files.
function collect(json, into) {
  if (Array.isArray(json)) {
    for (const e of json) if (e.name ?? e.type) into.types.add(typeName(e.type ?? e.name));
    return;
  }
  for (const e of json.reflection ?? []) into.types.add(typeName(e.type));
  for (const e of json.jni ?? []) into.types.add(typeName(e.type));
  // reachability-metadata.json: [{glob}]; resource-config.json: {resources: {includes: [{pattern}]}}.
  const resources = json.resources ?? [];
  for (const e of Array.isArray(resources) ? resources : []) {
    if (typeof e.glob === 'string') into.globs.add(e.glob);
  }
  for (const e of Array.isArray(resources) ? [] : resources.includes ?? []) {
    if (typeof e.pattern === 'string') into.patterns.push(javaRegExp(e.pattern));
  }
}

// A Java pattern of resource-config.json; \Q...\E (its usual quoting) has no JavaScript equivalent.
function javaRegExp(pattern) {
  const quoted = pattern.replace(/\\Q(.*?)\\E/g, (_, literal) => literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  return new RegExp(`^${quoted}$`);
}

function globRegExp(glob) {
  let re = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*' && glob[i + 1] === '*') { re += '.*'; i++; }
    else if (c === '*') re += '[^/]*';
    else re += c.replace(/[.+?^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${re}$`);
}

const [agentDir, ...roots] = process.argv.slice(2);
const agentFile = agentDir && join(agentDir, 'reachability-metadata.json');
if (!agentFile || !existsSync(agentFile)) {
  console.error(`agent-diff: no reachability-metadata.json in ${agentDir ?? '(no folder given)'}`);
  process.exit(2);
}

const declared = { types: new Set(), globs: new Set(), patterns: [] };
const sources = roots.flatMap((r) => files(r));
for (const f of sources) {
  try { collect(JSON.parse(readFileSync(f, 'utf8')), declared); } catch { /* not metadata of ours */ }
}
const globs = [...declared.globs].map(globRegExp).concat(declared.patterns);

const seen = { types: new Set(), globs: new Set(), patterns: [] };
collect(JSON.parse(readFileSync(agentFile, 'utf8')), seen);

const types = [...seen.types].filter((t) => !declared.types.has(t)).sort();
const resources = [...seen.globs].filter((g) => !declared.globs.has(g) && !globs.some((re) => re.test(g))).sort();

console.log(`### Tracing agent: ${seen.types.size} types, ${seen.globs.size} resources seen; ${sources.length} metadata files compared`);
console.log('');
console.log(types.length || resources.length
  ? 'Seen by the agent, declared nowhere (a person decides what goes into a module\'s *-manual metadata):'
  : 'Everything the agent saw is declared.');
if (types.length) console.log(['', '```', ...types.map((t) => `type     ${t}`), '```'].join('\n'));
if (resources.length) console.log(['', '```', ...resources.map((g) => `resource ${g}`), '```'].join('\n'));
