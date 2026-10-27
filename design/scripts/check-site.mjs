#!/usr/bin/env node
// Verify the deployable bundle, including links at a hosting subpath.
// Usage: node scripts/check-site.mjs --site-url https://your-domain.example/base/
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { extname, join, relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { parse } from "parse5";
import { OUTPUT, attribute, publicPages, siteUrl, textContent, visit } from "./export-site.mjs";

export async function checkSite(base) {
  const pages = await publicPages();
  const errors = [];
  const documents = new Map();
  const titles = new Set();
  const descriptions = new Set();
  const canonicalUrls = new Set(pages.map((page) => new URL(page.route, base).href));
  const check = (ok, message) => { if (!ok) errors.push(message); };
  const files = (path) => readdirSync(path).flatMap((name) => {
    const file = join(path, name);
    return statSync(file).isDirectory() ? files(file) : [file];
  });
  const allowedHtml = new Set(pages.map((page) => join(OUTPUT, page.route, "index.html")));
  check(existsSync(OUTPUT), "No site-dist/ bundle; run export-site.mjs first.");
  if (!existsSync(OUTPUT)) throw new Error(errors.join("\n"));
  for (const file of files(OUTPUT)) {
    if (extname(file) === ".html") check(allowedHtml.has(file), `Unexpected non-public HTML: ${relative(OUTPUT, file)}`);
  }
  for (const page of pages) {
    const file = join(OUTPUT, page.route, "index.html");
    check(existsSync(file), `Missing public page ${page.route || "/"}`);
    if (!existsSync(file)) continue;
    const document = parse(readFileSync(file, "utf8"));
    const nodes = [];
    visit(document, (node) => nodes.push(node));
    const ids = new Set(nodes.map((node) => attribute(node, "id")).filter(Boolean));
    documents.set(file, { nodes, ids });
    const title = nodes.filter((node) => node.tagName === "title").map(textContent);
    check(title.length === 1 && title[0].trim(), `${page.route}: needs one nonempty title`);
    check(!titles.has(title[0]), `${page.route}: duplicate title`);
    titles.add(title[0]);
    const description = nodes.filter((node) => node.tagName === "meta" && attribute(node, "name") === "description").map((node) => attribute(node, "content"));
    check(description.length === 1 && description[0]?.trim(), `${page.route}: needs one nonempty description`);
    check(!descriptions.has(description[0]), `${page.route}: duplicate description`);
    descriptions.add(description[0]);
    check(nodes.filter((node) => node.tagName === "h1").length === 1, `${page.route}: needs one h1`);
    check(nodes.filter((node) => node.tagName === "main").length === 1, `${page.route}: needs one main`);
    const canonical = nodes.filter((node) => node.tagName === "link" && attribute(node, "rel") === "canonical").map((node) => attribute(node, "href"));
    const expected = new URL(page.route, base).href;
    check(canonical.length === 1 && canonical[0] === expected, `${page.route}: canonical URL mismatch`);
    check(nodes.some((node) => node.tagName === "meta" && attribute(node, "property") === "og:url" && attribute(node, "content") === expected), `${page.route}: og:url mismatch`);
    check(!nodes.some((node) => node.tagName === "meta" && /robots|googlebot/.test(attribute(node, "name") ?? "") && /noindex/.test(attribute(node, "content") ?? "")), `${page.route}: unexpectedly noindex`);
    if (page.route) {
      const breadcrumbs = nodes.find((node) => node.tagName === "script" && attribute(node, "id") === "site-breadcrumbs");
      try {
        const structured = JSON.parse(textContent(breadcrumbs ?? {}));
        check(structured["@type"] === "BreadcrumbList" && structured.itemListElement.at(-1)?.item === expected, `${page.route}: invalid breadcrumbs`);
      } catch { errors.push(`${page.route}: missing or malformed JSON-LD breadcrumbs`); }
    }
  }
  function localTarget(value, from, label) {
    if (/^(?:mailto:|tel:|data:|blob:)/i.test(value)) return null;
    let url;
    try { url = new URL(value, from); } catch { errors.push(`${label}: invalid URL ${value}`); return null; }
    if (url.origin !== base.origin) return null;
    check(url.pathname.startsWith(base.pathname), `${label}: escapes hosting base: ${value}`);
    if (!url.pathname.startsWith(base.pathname)) return null;
    const local = decodeURIComponent(url.pathname.slice(base.pathname.length));
    let file = resolve(OUTPUT, local || ".");
    check(file === OUTPUT || file.startsWith(OUTPUT + "/"), `${label}: unsafe path ${value}`);
    if (!file.startsWith(OUTPUT + "/") && file !== OUTPUT) return null;
    if (url.pathname.endsWith("/")) file = join(file, "index.html");
    check(existsSync(file) && statSync(file).isFile(), `${label}: missing link or asset ${value}`);
    return { file, url };
  }
  for (const page of pages) {
    const document = documents.get(join(OUTPUT, page.route, "index.html"));
    if (!document) continue;
    const from = new URL(page.route, base);
    for (const node of document.nodes) {
      for (const attr of node.attrs ?? []) {
        if (!["href", "src"].includes(attr.name) || !attr.value) continue;
        const target = localTarget(attr.value, from, page.route || "/");
        if (target?.url.hash && documents.has(target.file)) {
          const id = decodeURIComponent(target.url.hash.slice(1));
          check(documents.get(target.file).ids.has(id), `${page.route}: missing anchor ${attr.value}`);
        }
      }
    }
  }
  const cssFile = join(OUTPUT, "dist/gw-kit.css");
  if (existsSync(cssFile)) {
    for (const match of readFileSync(cssFile, "utf8").matchAll(/url\(\s*["']?([^\s"')]+)["']?\s*\)/g)) {
      localTarget(match[1], new URL("dist/gw-kit.css", base), "CSS");
    }
  }
  const sitemapPath = join(OUTPUT, "sitemap.xml");
  check(existsSync(sitemapPath), "Missing sitemap.xml");
  if (existsSync(sitemapPath)) {
    const entities = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };
    const locations = [...readFileSync(sitemapPath, "utf8").matchAll(/<loc>(.*?)<\/loc>/g)].map((match) => match[1].replace(/&(amp|lt|gt|quot|apos);/g, (_, name) => entities[name]));
    check(locations.length === canonicalUrls.size && new Set(locations).size === locations.length, "Sitemap count or uniqueness mismatch");
    for (const expected of canonicalUrls) check(locations.includes(expected), `Sitemap missing ${expected}`);
  }
  const robotsPath = join(OUTPUT, "robots.txt");
  check(existsSync(robotsPath) && readFileSync(robotsPath, "utf8").includes(`Sitemap: ${new URL("sitemap.xml", base).href}`), "robots.txt must point to the canonical sitemap");
  if (errors.length) throw new Error(`Static site validation failed (${errors.length}):\n${errors.map((error) => `- ${error}`).join("\n")}`);
  console.log(`site     ${pages.length} pages: metadata, breadcrumbs, sitemap, links, anchors and local assets OK`);
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try { await checkSite(siteUrl()); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
