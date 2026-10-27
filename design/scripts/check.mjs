#!/usr/bin/env node
// =============================================================
// GateRook Design Kit · check
// -------------------------------------------------------------
// The kit's definition of done, automated. Run after `npm run build`
// (or `npm run verify` for both). Exit code 1 on any error.
//
//   contrast   every pair in tokens/contrast-pairs.json, in light, dark,
//              light-hc and dark-hc (WCAG 2.2: 4.5 text, 3 non-text)
//   scss       no raw colours, px, font stacks, z-index or durations in
//              scss/components, scss mixins/base and elements; a value
//              that has no token carries a `// raw: <reason>` comment
//   html       pages/, elements/ and the showcase: lang, title, one main,
//              one h1, no skipped heading levels, buttons typed and named,
//              img alt, unique ids, ARIA id references resolve, no
//              external resources (Electron CSP), no glyph icons, Lucide
//              names exist in the pinned build, every
//              gw-* class exists in dist/gw-kit.css, sidebar identical
//              on every app page
//   manifest   every partial is @use'd and listed; every listed file exists
// =============================================================

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { parse } from "parse5";
import { loadTokens, modeValue } from "./build.mjs";
import { lucideNames } from "./icons.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const errors = [];
const warnings = [];
const err = (scope, msg) => errors.push(`✗ [${scope}] ${msg}`);
const warn = (scope, msg) => warnings.push(`! [${scope}] ${msg}`);
const read = (p) => readFileSync(join(ROOT, p), "utf8");
const ls = (dir, ext) => (existsSync(join(ROOT, dir)) ? readdirSync(join(ROOT, dir)).filter((f) => f.endsWith(ext)).map((f) => `${dir}/${f}`) : []);

// ─────────────────────────── contrast ───────────────────────────

function parseColor(s) {
  s = String(s).trim();
  let m = s.match(/^#([0-9a-f]{6})$/i);
  if (m) return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16)).concat(1);
  m = s.match(/^rgba?\(([^)]+)\)$/i);
  if (m) {
    const p = m[1].split(",").map((x) => parseFloat(x));
    return [p[0], p[1], p[2], p[3] ?? 1];
  }
  throw new Error(`Cannot parse colour "${s}"`);
}
const blend = (fg, bg) => [0, 1, 2].map((i) => fg[i] * fg[3] + bg[i] * (1 - fg[3])).concat(1);
const lum = ([r, g, b]) => {
  const f = (c) => ((c /= 255) <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
};
const ratio = (a, b) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};

function checkContrast() {
  const tokens = new Map(loadTokens().map((t) => [t.path.join("."), t]));
  const { pairs } = JSON.parse(read("tokens/contrast-pairs.json"));
  let n = 0;
  for (const mode of ["light", "dark", "light-hc", "dark-hc"]) {
    const val = (name) => {
      const t = tokens.get(name);
      if (!t) throw new Error(`contrast-pairs.json: unknown token ${name}`);
      return parseColor(modeValue(t, mode));
    };
    for (const p of pairs) {
      const under = val(p.under ?? "color.bg.surface");
      for (const on of [].concat(p.on)) {
        const bg = blend(val(on), under);
        const fg = blend(val(p.fg), bg);
        const r = ratio(fg, bg);
        n++;
        if (r < p.min) err("contrast", `${mode}: ${p.fg} on ${on} = ${r.toFixed(2)}:1 (needs ${p.min})`);
      }
    }
  }
  return n;
}

// ─────────────────────────── scss lint ───────────────────────────

const SCSS_RULES = [
  [/#[0-9a-fA-F]{3,8}\b/, "raw hex colour, use a var(--gw-color-*) token"],
  [/\b(rgba?|hsla?|oklch|lab)\(/, "raw colour function, use a token (color-mix() over tokens is fine)"],
  [/:\s*(white|black)\b/, "named colour, use a token"],
  [/(?<![\w.])(?!0px)\d*\.?\d+px\b/, "raw px value, use a space / size / radius / layout token"],
  [/font-family:(?!\s*(var\(|inherit))/, "font stack, use var(--gw-font-family-*)"],
  [/z-index:\s*-?\d/, "raw z-index, use var(--gw-z-*)"],
  [/\b\d+m?s\b(?!\w)/, "raw duration, use var(--gw-motion-*)"],
];

function lintScss() {
  const files = [
    ...ls("scss/components", ".scss"),
    ...ls("elements", ".scss"),
    "scss/_mixins.scss",
    "scss/_reset.scss",
    "scss/_typography.scss",
  ];
  let n = 0;
  for (const f of files) {
    if (!existsSync(join(ROOT, f))) continue;
    n++;
    read(f)
      .split("\n")
      .forEach((line, i) => {
        const code = line.replace(/\/\/.*$/, "").replace(/\/\*.*?\*\//g, "");
        if (!code.trim() || /\/\/\s*raw:/.test(line)) return;
        for (const [re, msg] of SCSS_RULES) {
          if (re.test(code)) err("scss", `${f}:${i + 1} ${msg}\n      ${line.trim()}`);
        }
        if (/!important/.test(code) && !/\[hidden\]|prefers-reduced-motion|\/\/\s*raw:/.test(line)) warn("scss", `${f}:${i + 1} !important`);
      });
  }
  return n;
}

// ─────────────────────────── html ───────────────────────────

const walk = (node, fn) => {
  fn(node);
  for (const c of node.childNodes ?? []) walk(c, fn);
  if (node.content) walk(node.content, fn); // <template>
};
const attr = (node, name) => node.attrs?.find((a) => a.name === name)?.value;
const textOf = (node) => {
  let s = "";
  walk(node, (n) => {
    if (n.nodeName === "#text") s += n.value;
  });
  return s.replace(/\s+/g, " ").trim();
};

function collectCssClasses() {
  const css = existsSync(join(ROOT, "dist/gw-kit.css")) ? read("dist/gw-kit.css") : "";
  return new Set([...css.matchAll(/\.(gw-[a-zA-Z0-9_-]+)/g)].map((m) => m[1]));
}

const GLYPH_ICONS = /[✓✔✕✖⋯▾▸►◀⚠⚙⬆⬇←-⇿\u{1F300}-\u{1FAFF}]/u;

function checkHtml() {
  const known = collectCssClasses();
  const lucide = lucideNames();
  const files = [...ls("pages", ".html"), ...ls("elements", ".html")];
  const titles = new Map();
  const navs = new Map();
  for (const f of files) {
    const html = read(f);
    const doc = parse(html, { sourceCodeLocationInfo: true });
    const at = (n) => `${f}:${n.sourceCodeLocation?.startLine ?? "?"}`;
    const ids = new Map();
    const refs = [];
    let mains = 0;
    const headings = [];
    let hasLang = false;
    let title = "";
    walk(doc, (n) => {
      if (!n.tagName) return;
      const t = n.tagName;
      if (t === "html" && attr(n, "lang")) hasLang = true;
      if (t === "title") title = textOf(n);
      if (t === "main") mains++;
      if (/^h[1-6]$/.test(t)) headings.push({ level: +t[1], n });
      const id = attr(n, "id");
      if (id) {
        if (ids.has(id)) err("html", `${at(n)} duplicate id "${id}"`);
        ids.set(id, n);
      }
      for (const a of ["aria-controls", "aria-labelledby", "aria-describedby", "for", "list", "popovertarget", "data-gw-dialog-open"]) {
        const v = attr(n, a);
        if (v) for (const r of v.split(/\s+/).filter(Boolean)) refs.push({ r, a, n });
      }
      if (t === "meta" && attr(n, "name") === "viewport" && /user-scalable=no|maximum-scale=1(\b|\.0)/.test(attr(n, "content") ?? ""))
        err("html", `${at(n)} viewport blocks zoom`);
      for (const a of ["src", "href"]) {
        const v = attr(n, a);
        if (v && /^(https?:)?\/\//.test(v) && t !== "a") err("html", `${at(n)} external resource ${v} (Electron CSP: ship it locally)`);
      }
      if (t === "img" && attr(n, "alt") === undefined) err("html", `${at(n)} <img> without alt`);
      if (t === "button") {
        if (!attr(n, "type")) err("html", `${at(n)} <button> without type`);
        const named = textOf(n) || attr(n, "aria-label") || attr(n, "aria-labelledby") || attr(n, "title");
        if (!named) err("html", `${at(n)} icon-only <button> without aria-label`);
      }
      if (t === "a" && attr(n, "href") === undefined && !attr(n, "role")) err("html", `${at(n)} <a> without href (use a <button>)`);
      if (["div", "span", "i", "li"].includes(t) && attr(n, "onclick")) err("html", `${at(n)} click handler on <${t}> (use a native button)`);
      if (attr(n, "aria-expanded") !== undefined && !["button", "a", "input", "summary"].includes(t) && attr(n, "role") !== "combobox")
        err("html", `${at(n)} aria-expanded on <${t}> (belongs on the trigger button)`);
      const cls = attr(n, "class");
      if (cls)
        for (const c of cls.split(/\s+/)) {
          if (c.startsWith("gw-") && !known.has(c)) err("html", `${at(n)} class "${c}" is not defined in dist/gw-kit.css`);
        }
      const icon = attr(n, "data-lucide");
      if (icon !== undefined && !lucide.has(icon)) err("html", `${at(n)} Lucide icon "${icon}" does not exist in vendor/lucide (npm run icons -- --search <word>)`);
      const style = attr(n, "style");
      if (style && /#[0-9a-f]{3,8}\b|rgba?\(/i.test(style)) err("html", `${at(n)} inline style with a raw colour`);
      if (t === "nav" && attr(n, "data-gw-nav") !== undefined) {
        const s = n.sourceCodeLocation;
        if (s) navs.set(f, html.slice(s.startOffset, s.endOffset).replace(/\s+aria-current="page"/g, "").replace(/\s+/g, " "));
      }
    });
    walk(doc, (n) => {
      if (n.nodeName === "#text" && GLYPH_ICONS.test(n.value) && n.parentNode?.tagName !== "script" && n.parentNode?.tagName !== "code" && n.parentNode?.tagName !== "pre")
        err("html", `${f}:${n.sourceCodeLocation?.startLine ?? "?"} glyph used as an icon: "${n.value.trim().slice(0, 30)}" (use a Lucide icon)`);
    });
    if (!hasLang) err("html", `${f} <html> without lang`);
    if (!title) err("html", `${f} empty <title>`);
    else if (titles.has(title)) err("html", `${f} <title> "${title}" duplicates ${titles.get(title)}`);
    else titles.set(title, f);
    if (mains !== 1) err("html", `${f} has ${mains} <main> landmarks (needs exactly 1)`);
    const h1 = headings.filter((h) => h.level === 1).length;
    if (h1 !== 1) err("html", `${f} has ${h1} <h1> (needs exactly 1)`);
    headings.reduce((prev, h) => {
      if (h.level > prev + 1) err("html", `${at(h.n)} heading jumps from h${prev} to h${h.level}`);
      return h.level;
    }, 0);
    for (const { r, a, n } of refs) if (!ids.has(r)) err("html", `${at(n)} ${a}="${r}" points to no element`);
    if (!/dist\/gw-kit\.css/.test(html)) err("html", `${f} does not link dist/gw-kit.css`);
    if (!/assets\/theme-boot\.js/.test(html)) err("html", `${f} does not load assets/theme-boot.js in <head>`);
  }
  // sidebar must be identical everywhere (only aria-current differs)
  const navList = [...navs];
  if (navList.length > 1) {
    const [refFile, refNav] = navList.find(([f]) => f.endsWith("/_shell.html")) ?? navList[0];
    for (const [f, nav] of navList) if (nav !== refNav) err("html", `${f} sidebar <nav data-gw-nav> differs from ${refFile} (copy it from pages/_shell.html)`);
  }
  return files.length;
}

// ─────────────────────────── manifest ───────────────────────────

function checkManifest() {
  if (!existsSync(join(ROOT, "manifest.json"))) return err("manifest", "manifest.json missing");
  const m = JSON.parse(read("manifest.json"));
  const index = read("scss/index.scss");
  const listed = new Set([...(m.components ?? []), ...(m.elements ?? [])].map((c) => c.scss));
  for (const f of [...ls("scss/components", ".scss"), ...ls("elements", ".scss")]) {
    const name = f.replace(/^.*\/_/, "").replace(/\.scss$/, "");
    const useName = f.startsWith("elements") ? `../elements/${name}` : `components/${name}`;
    if (!index.includes(`"${useName}"`)) err("manifest", `${f} is not @use'd in scss/index.scss`);
    if (!listed.has(f)) err("manifest", `${f} has no manifest.json entry`);
  }
  for (const c of [...(m.components ?? []), ...(m.elements ?? [])]) {
    for (const k of ["scss", "html"]) if (c[k] && !existsSync(join(ROOT, c[k]))) err("manifest", `${c.name}: ${k} ${c[k]} does not exist`);
    if (!c.purpose) err("manifest", `${c.name}: missing purpose`);
  }
  for (const p of m.pages ?? []) if (!existsSync(join(ROOT, p.html))) err("manifest", `page ${p.html} does not exist`);
}

// ─────────────────────────── run ───────────────────────────

const pairs = checkContrast();
const scss = lintScss();
const html = checkHtml();
checkManifest();

for (const w of warnings) console.log(w);
for (const e of errors) console.log(e);
console.log(`\ncontrast ${pairs} checks · scss ${scss} files · html ${html} files · ${errors.length} errors · ${warnings.length} warnings`);
process.exit(errors.length ? 1 : 0);
