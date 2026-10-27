#!/usr/bin/env node
// Visual compare: the kit's page or element next to the app's route, in light, dark and high contrast, at 1440×900
// (ARCHITECTURE.md §10, "comparison with design/pages/<page>.html"). For each pair it writes ui/target/compare/:
//   <name>-<theme>-kit.png, <name>-<theme>-app.png and <name>-<theme>.png (both side by side, kit left, app right),
// plus index.html with all of them, worst first by a pixel score (the share of pixels that differ over the common
// height: a triage aid, not a pass/fail — data and dates differ by design). ui/target is git-ignored.
//
//   npm run dev -w ui                                   # in another terminal: core (demo world) + ng serve
//   npm run compare -w ui -- --app http://127.0.0.1:4200 [--only overview,status-screen] [--themes light,dark,hc]
//
// The kit is served by design/scripts/serve.mjs on a free port. Chromium: playwright-core with the browser at
// $PLAYWRIGHT_CHROMIUM, else $PLAYWRIGHT_BROWSERS_PATH/chromium, else /opt/pw-browsers/chromium (never downloads one).
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const UI = join(dirname(fileURLToPath(import.meta.url)), '..');
const ROOT = join(UI, '..');
const OUT = join(UI, 'target', 'compare');

/** Kit page or element → app route. Pages are compared inside the shell; elements against the dev gallery. */
const PAGES = [
  ['overview', 'pages/overview.html', '/overview'],
  ['approvals', 'pages/approvals.html', '/approvals'],
  ['connections', 'pages/connections.html', '/connections'],
  ['connection-new', 'pages/connection-new.html', '/connections/new'],
  ['connectors', 'pages/connectors.html', '/connectors'],
  ['secrets', 'pages/secrets.html', '/secrets'],
  ['endpoints', 'pages/endpoints.html', '/endpoints'],
  ['endpoint-detail', 'pages/endpoint-detail.html', '/endpoints/dev'],
  ['clients', 'pages/clients.html', '/clients'],
  ['policies', 'pages/policies.html', '/policies/dev'],
  ['playground', 'pages/playground.html', '/playground'],
  ['activity', 'pages/activity.html', '/activity'],
  ['insights', 'pages/insights.html', '/insights'],
  ['settings', 'pages/settings.html', '/settings/general'],
  ['onboarding', 'pages/onboarding.html', '/onboarding'],
];
const ELEMENTS = [
  'setup-map', 'how-it-works', 'getting-started', 'approval-request', 'approval-notification', 'tool-policy-list',
  'risk-callout', 'token-issue', 'client-card', 'connection-form', 'connector-card', 'event-detail', 'log-explorer',
  'settings-layout', 'status-screen', 'command-palette',
].map((n) => [n, `elements/${n}.html`, `/dev/elements/${n}`]);

const THEMES = {
  light: { theme: 'light', contrast: 'normal' },
  dark: { theme: 'dark', contrast: 'normal' },
  hc: { theme: 'light', contrast: 'more' },
  'hc-dark': { theme: 'dark', contrast: 'more' },
};

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};

function freePort() {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.once('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

function chromiumPath() {
  const candidates = [
    process.env.PLAYWRIGHT_CHROMIUM,
    process.env.PLAYWRIGHT_BROWSERS_PATH && join(process.env.PLAYWRIGHT_BROWSERS_PATH, 'chromium'),
    '/opt/pw-browsers/chromium',
  ].filter(Boolean);
  const found = candidates.find((p) => existsSync(p));
  if (!found) throw new Error(`No Chromium found (tried ${candidates.join(', ')}); set PLAYWRIGHT_CHROMIUM`);
  return found;
}

async function startKit() {
  const port = await freePort();
  const kit = spawn(process.execPath, [join(ROOT, 'design', 'scripts', 'serve.mjs'), '--port', String(port)], {
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  await new Promise((resolve) => kit.stdout.once('data', resolve));
  return { url: `http://127.0.0.1:${port}`, stop: () => kit.kill() };
}

/**
 * Loads a page in one appearance and waits until fonts, icons and the app's first data are in. The app keeps its
 * event stream open, so it never goes network-idle: wait for its main region and a moment for the first answers.
 */
async function shoot(browser, url, appearance, file, isApp) {
  // The demo world's clock is UTC+2 (BLUEPRINT §9): the app shows times in local time, so the browser runs there.
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    timezoneId: 'Europe/Berlin',
    locale: 'en-US',
  });
  await context.addInitScript((a) => {
    try {
      localStorage.setItem('gw-appearance', JSON.stringify(a));
      localStorage.removeItem('gw-sidebar-collapsed');
    } catch {
      // storage blocked: the default theme
    }
  }, appearance);
  const page = await context.newPage();
  if (isApp) {
    await page.goto(url, { waitUntil: 'load' });
    await page.waitForSelector('#main', { timeout: 15_000 });
    await page.waitForTimeout(1500);
  } else {
    await page.goto(url, { waitUntil: 'networkidle' });
  }
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(400);
  await page.screenshot({ path: file, fullPage: true });
  await context.close();
}

async function sideBySide(browser, kitFile, appFile, file, title) {
  const page = await browser.newPage({ viewport: { width: 2 * 1440 + 24, height: 900 } });
  // A page from setContent() may not load file: URLs; inline the screenshots.
  const img = (f) => `data:image/png;base64,${readFileSync(f).toString('base64')}`;
  await page.setContent(
    `<!doctype html><body style="margin:0;background:#888;font:14px sans-serif">
     <div style="display:flex;gap:24px;align-items:flex-start">
       <figure style="margin:0"><figcaption style="padding:4px;color:#fff">kit · ${title}</figcaption><img src="${img(kitFile)}"></figure>
       <figure style="margin:0"><figcaption style="padding:4px;color:#fff">app · ${title}</figcaption><img src="${img(appFile)}"></figure>
     </div></body>`,
    { waitUntil: 'load' },
  );
  await page.screenshot({ path: file, fullPage: true });
  await page.close();
}

/** The share of pixels that differ (any channel by more than 48) over the height both screenshots have. */
async function pixelScore(browser, kitFile, appFile) {
  const page = await browser.newPage();
  const img = (f) => `data:image/png;base64,${readFileSync(f).toString('base64')}`;
  const score = await page.evaluate(
    async ([a, b]) => {
      const load = (src) =>
        new Promise((resolve, reject) => {
          const i = new Image();
          i.onload = () => resolve(i);
          i.onerror = reject;
          i.src = src;
        });
      const [ia, ib] = await Promise.all([load(a), load(b)]);
      const w = Math.min(ia.width, ib.width);
      const h = Math.min(ia.height, ib.height);
      const data = (i) => {
        const c = document.createElement('canvas');
        c.width = w;
        c.height = h;
        const ctx = c.getContext('2d');
        ctx.drawImage(i, 0, 0);
        return ctx.getImageData(0, 0, w, h).data;
      };
      const da = data(ia);
      const db = data(ib);
      let diff = 0;
      for (let p = 0; p < da.length; p += 4) {
        if (Math.abs(da[p] - db[p]) > 48 || Math.abs(da[p + 1] - db[p + 1]) > 48 || Math.abs(da[p + 2] - db[p + 2]) > 48) diff++;
      }
      return { diff: diff / (w * h), heights: [ia.height, ib.height] };
    },
    [img(kitFile), img(appFile)],
  );
  await page.close();
  return score;
}

async function main() {
  const app = option('--app', process.env.GW_UI_URL);
  if (!app) {
    console.error('Usage: npm run compare -w ui -- --app http://127.0.0.1:<ng serve port> [--only a,b] [--themes light,dark,hc]');
    process.exit(2);
  }
  const only = option('--only', '')
    .split(',')
    .filter(Boolean);
  const themes = option('--themes', 'light,dark,hc').split(',');
  const pairs = [...PAGES, ...ELEMENTS].filter(([name]) => only.length === 0 || only.includes(name));
  mkdirSync(OUT, { recursive: true });

  const kit = await startKit();
  const browser = await chromium.launch({ executablePath: chromiumPath() });
  const rows = [];
  try {
    for (const [name, kitPath, route] of pairs) {
      for (const t of themes) {
        const a = THEMES[t];
        if (!a) throw new Error(`unknown theme ${t}`);
        const base = join(OUT, `${name}-${t}`);
        await shoot(browser, `${kit.url}/${kitPath}`, a, `${base}-kit.png`, false);
        await shoot(browser, new URL(route, app).href, a, `${base}-app.png`, true);
        await sideBySide(browser, `${base}-kit.png`, `${base}-app.png`, `${base}.png`, `${name} · ${t}`);
        const { diff, heights } = await pixelScore(browser, `${base}-kit.png`, `${base}-app.png`);
        const pct = (diff * 100).toFixed(1);
        rows.push({ diff, html: `<h2>${name} · ${t} · ${pct}% pixels differ · heights ${heights.join(' / ')}</h2><img src="${name}-${t}.png" style="max-width:100%">` });
        console.log(`compare: ${base}.png  ${pct}% differ, heights kit ${heights[0]} / app ${heights[1]}`);
      }
    }
  } finally {
    await browser.close();
    kit.stop();
  }
  rows.sort((x, y) => y.diff - x.diff);
  writeFileSync(join(OUT, 'index.html'), `<!doctype html><meta charset="utf-8"><title>Kit vs app</title>${rows.map((r) => r.html).join('\n')}`);
  console.log(`compare: ${join(OUT, 'index.html')}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
