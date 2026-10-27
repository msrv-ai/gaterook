#!/usr/bin/env node
// Static preview server for the kit. Binds 127.0.0.1 only (same rule as the gateway core).
// Usage: npm run serve [-- --port 8020]  →  http://127.0.0.1:8020/pages/index.html
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { join, extname, normalize, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const portArg = process.argv.indexOf("--port");
const PORT = portArg > -1 ? Number(process.argv[portArg + 1]) : Number(process.env.PORT ?? 8020);
const TYPES = { ".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "text/javascript", ".mjs": "text/javascript", ".svg": "image/svg+xml", ".json": "application/json", ".png": "image/png", ".woff2": "font/woff2", ".txt": "text/plain" };

createServer(async (req, res) => {
  let path = decodeURIComponent(new URL(req.url, "http://x").pathname);
  if (path === "/") path = "/pages/index.html";
  if (path.endsWith("/")) path += "index.html";
  const file = normalize(join(ROOT, path));
  if (!file.startsWith(ROOT)) return res.writeHead(403).end();
  try {
    const body = await readFile(file);
    res.writeHead(200, { "Content-Type": TYPES[extname(file)] ?? "application/octet-stream", "Cache-Control": "no-store" });
    res.end(body);
  } catch {
    res.writeHead(404, { "Content-Type": "text/plain" }).end("Not found");
  }
}).listen(PORT, "127.0.0.1", () => console.log(`Design kit: http://127.0.0.1:${PORT}/pages/index.html`));
