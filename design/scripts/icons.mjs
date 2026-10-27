#!/usr/bin/env node
// Lucide helper: which icon names exist in the pinned vendor build.
//   npm run icons -- database shield-check   → ✓ / ✗ per name
//   npm run icons -- --search shield          → every name containing "shield"
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const toPascal = (s) => s.replace(/(^|-)([a-z0-9])/g, (_, __, c) => c.toUpperCase());
const toKebab = (s) => s.replace(/([a-z0-9])([A-Z])/g, "$1-$2").replace(/([A-Z])([A-Z][a-z])/g, "$1-$2").replace(/([a-zA-Z])(\d)/g, "$1-$2").toLowerCase();

export function lucideNames() {
  const ctx = {};
  ctx.globalThis = ctx;
  ctx.self = ctx;
  vm.runInNewContext(readFileSync(join(ROOT, "vendor/lucide/lucide.min.js"), "utf8"), ctx);
  const icons = ctx.lucide?.icons ?? {};
  return { has: (name) => Boolean(icons[toPascal(name)]), all: () => Object.keys(icons).map(toKebab) };
}

if (process.argv[1]?.endsWith("icons.mjs")) {
  const args = process.argv.slice(2);
  const lib = lucideNames();
  if (args[0] === "--search") {
    const q = args[1] ?? "";
    console.log(lib.all().filter((n) => n.includes(q)).join("\n"));
  } else {
    let bad = 0;
    for (const a of args) {
      const ok = lib.has(a);
      if (!ok) bad++;
      console.log(`${ok ? "✓" : "✗"} ${a}`);
    }
    process.exit(bad ? 1 : 0);
  }
}
