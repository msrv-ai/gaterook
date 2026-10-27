#!/usr/bin/env node
// Export only public marketing pages. No app prototypes or backend are shipped.
// Usage: node scripts/export-site.mjs --site-url https://your-domain.example/base/
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { parse, parseFragment, serialize } from "parse5";

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
export const OUTPUT = join(ROOT, "site-dist");
export const ASSETS = [
  "assets/theme-boot.js", "assets/kit.js", "assets/promo.js", "assets/logo-mark.svg",
  "assets/fonts", "assets/integrations", "assets/promo-flow.js", "vendor/lucide/lucide.min.js", "vendor/lucide/LICENSE", "dist/gw-kit.css",
];
const xml = (text) => String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&apos;");

export function siteUrl(args = process.argv.slice(2)) {
  const index = args.indexOf("--site-url");
  if (index < 0 || !args[index + 1]) {
    throw new Error("Supply --site-url with the public HTTPS URL (including any hosting subpath). No production domain is assumed.");
  }
  const url = new URL(args[index + 1]);
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (url.protocol !== "https:" && !(local && url.protocol === "http:")) {
    throw new Error("--site-url must use HTTPS; HTTP is permitted only for a local preview.");
  }
  if (url.username || url.password || url.search || url.hash) throw new Error("--site-url cannot contain credentials, a query, or a fragment.");
  if (!url.pathname.endsWith("/")) url.pathname += "/";
  return url;
}

export async function publicPages() {
  const [{ default: data }, { default: cloud }] = await Promise.all([
    import("../content/integrations-data.mjs"), import("../content/integrations-cloud.mjs"),
  ]);
  const guides = [...data, ...cloud];
  const slugs = new Set();
  for (const guide of guides) {
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(guide.slug) || slugs.has(guide.slug)) {
      throw new Error(`Invalid or duplicate integration slug: ${guide.slug}`);
    }
    slugs.add(guide.slug);
  }
  return [
    { source: "pages/gaterook.html", route: "", name: "GateRook" },
    { source: "pages/docs.html", route: "docs/", name: "Docs" },
    { source: "pages/docs-getting-started.html", route: "docs/getting-started/", name: "Getting started" },
    ...guides.map((guide) => ({ source: `pages/mcp-${guide.slug}.html`, route: `docs/${guide.slug}/`, name: guide.name })),
  ];
}

export function visit(node, fn) {
  fn(node);
  for (const child of node.childNodes ?? []) visit(child, fn);
  if (node.content) visit(node.content, fn);
}
export const attribute = (node, name) => node.attrs?.find((attr) => attr.name === name)?.value;
export const textContent = (node) => node.nodeName === "#text" ? node.value : (node.childNodes ?? []).map(textContent).join("");

function appendHtml(parent, html) {
  const fragment = parseFragment(html);
  for (const node of fragment.childNodes) {
    node.parentNode = parent;
    parent.childNodes.push(node);
  }
}

function exportPage(page, pages, base) {
  const source = readFileSync(join(ROOT, page.source), "utf8");
  const document = parse(source);
  const routes = new Map(pages.map((entry) => ["/" + entry.source, entry.route]));
  let head;
  visit(document, (node) => {
    if (node.tagName === "head") head = node;
    for (const attr of node.attrs ?? []) {
      if (!["href", "src"].includes(attr.name) || !attr.value || attr.value.startsWith("#")) continue;
      // Keep external links, data URLs and protocol links unchanged.
      if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(attr.value)) continue;
      const from = new URL(attr.value, `https://kit.invalid/${page.source}`);
      let target;
      if (routes.has(from.pathname)) target = routes.get(from.pathname);
      else if (/^\/(assets|vendor|dist)\//.test(from.pathname)) target = from.pathname.slice(1);
      else throw new Error(`${page.source}: cannot export non-public link ${attr.value}`);
      attr.value = new URL(target, base).pathname + from.search + from.hash;
    }
  });
  if (!head) throw new Error(`${page.source}: missing head`);
  // A clean canonical must reflect the chosen deployment, never a preview URL.
  head.childNodes = head.childNodes.filter((node) => !(
    (node.tagName === "link" && attribute(node, "rel") === "canonical") ||
    (node.tagName === "meta" && attribute(node, "property") === "og:url") ||
    (node.tagName === "script" && attribute(node, "id") === "site-breadcrumbs")
  ));
  const canonical = new URL(page.route, base).href;
  appendHtml(head, `\n<link rel="canonical" href="${xml(canonical)}">\n<meta property="og:url" content="${xml(canonical)}">\n`);
  if (page.route) {
    const crumbs = [{ name: "GateRook", item: base.href }];
    if (page.route !== "docs/") crumbs.push({ name: "Docs", item: new URL("docs/", base).href });
    crumbs.push({ name: page.name, item: canonical });
    const structured = {
      "@context": "https://schema.org", "@type": "BreadcrumbList",
      itemListElement: crumbs.map((crumb, index) => ({ "@type": "ListItem", position: index + 1, ...crumb })),
    };
    const json = JSON.stringify(structured).replace(/</g, "\\u003c");
    appendHtml(head, `<script type="application/ld+json" id="site-breadcrumbs">${json}</script>\n`);
  }
  const destination = join(OUTPUT, page.route, "index.html");
  mkdirSync(dirname(destination), { recursive: true });
  writeFileSync(destination, serialize(document) + "\n");
}

export async function exportSite(base) {
  const pages = await publicPages();
  // Check prerequisites before touching the previous export.
  const assets = [...ASSETS];
  if (existsSync(join(ROOT, "assets/docs.js"))) assets.push("assets/docs.js");
  for (const file of [...pages.map((page) => page.source), ...assets]) {
    if (!existsSync(join(ROOT, file))) throw new Error(`Missing ${file}. Run npm run build first.`);
  }
  rmSync(OUTPUT, { recursive: true, force: true });
  mkdirSync(OUTPUT, { recursive: true });
  for (const file of assets) {
    mkdirSync(dirname(join(OUTPUT, file)), { recursive: true });
    cpSync(join(ROOT, file), join(OUTPUT, file), { recursive: true });
  }
  for (const page of pages) exportPage(page, pages, base);
  const sitemap = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${pages.map((page) => `  <url><loc>${xml(new URL(page.route, base).href)}</loc></url>`).join("\n")}\n</urlset>\n`;
  writeFileSync(join(OUTPUT, "sitemap.xml"), sitemap);
  writeFileSync(join(OUTPUT, "robots.txt"), `User-agent: *\nAllow: ${base.pathname}\n\nSitemap: ${new URL("sitemap.xml", base).href}\n`);
  console.log(`site     ${pages.length} public HTML pages → site-dist/ (canonical base: ${base.href})`);
  return pages;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try { await exportSite(siteUrl()); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
