import { test, expect, type Page } from '@playwright/test';
import { copyFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Native browser/OS is recorded separately; scale-derived viewport/DPR do not prove a physical LG display.
const viewports = [
  { width: 2048, height: 1280, dpr: 1.25, physical: '2560x1600', windowsScale: 125, chromeZoom: 100 },
  { width: 1638, height: 1024, dpr: 1.5625, physical: '2560x1600', windowsScale: 125, chromeZoom: 125 },
  { width: 1707, height: 1067, dpr: 1.5, physical: '2560x1600', windowsScale: 150, chromeZoom: 100 },
  { width: 1365, height: 853, dpr: 1.875, physical: '2560x1600', windowsScale: 150, chromeZoom: 125 },
  { width: 1536, height: 960, dpr: 1.25, physical: '1920x1200', windowsScale: 125, chromeZoom: 100 },
  { width: 1229, height: 768, dpr: 1.5625, physical: '1920x1200', windowsScale: 125, chromeZoom: 125 },
  { width: 1280, height: 800, dpr: 1.5, physical: '1920x1200', windowsScale: 150, chromeZoom: 100 },
  { width: 1024, height: 640, dpr: 1.875, physical: '1920x1200', windowsScale: 150, chromeZoom: 125 },
  { width: 1366, height: 768, dpr: 1, physical: 'minimum CSS viewport', windowsScale: null, chromeZoom: 100 },
];
const routes = [
  { name: 'home', hash: '', required: '.home-stock-table .stock-row' },
  { name: 'ranking', hash: '#ranking', required: '.ranking-stock-table .stock-row' },
  { name: 'detail', hash: '#stock/005930/1', required: '.dtl-compact-metric' },
  { name: 'chart', hash: '#stock/005930/4', required: '[data-testid="chart_latest_close"]' },
  { name: 'analysis', hash: '#stock/005930/2', required: '.dtl-quarter-table' },
  { name: 'sources', hash: '#stock/005930/3', required: '[role="tabpanel"]:not([hidden])' },
];
const requestedRoutes = process.env.KR4_MATRIX_ROUTES?.split(',').map(value => value.trim()).filter(Boolean);
if (requestedRoutes?.some(name => !routes.some(route => route.name === name))) throw new Error('Unknown KR4_MATRIX_ROUTES entry');
const matrixRoutes = requestedRoutes ? routes.filter(route => requestedRoutes.includes(route.name)) : routes;
const outputDir = resolve('test-results', process.env.KR4_PUBLIC_URL ? 'public-viewport-originals' : 'local-viewport-originals');
// An optional outside-results mirror preserves originals when another suite cleans Playwright's output directory.
const archiveDir = process.env.KR4_MATRIX_EVIDENCE_DIR;
function save(name: string, value: unknown) {
  for (const dir of [outputDir, ...(archiveDir ? [resolve(archiveDir)] : [])]) {
    mkdirSync(dir, { recursive: true }); writeFileSync(resolve(dir, `${name}.json`), JSON.stringify(value, null, 2));
  }
}
async function ready(page: Page, hash: string) {
  const target = new URL(process.env.KR4_PUBLIC_URL || 'http://127.0.0.1:4173/Kook/');
  target.hash = hash.replace(/^#/, '');
  await page.goto(target.href);
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'LIVE');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
  await page.evaluate(() => document.fonts.ready);
  if (hash.startsWith('#stock/')) await expect(page.getByTestId('stock-detail')).toBeVisible();
  if (hash.endsWith('/4')) await expect(page.getByTestId('chart_latest_close')).toContainText('원');
  await page.evaluate(() => document.fonts.ready);
}
async function capture(page: Page, name: string, fullPage = false, scenario: unknown = null) {
  await page.evaluate(() => document.fonts.ready);
  mkdirSync(outputDir, { recursive: true });
  await page.screenshot({ path: resolve(outputDir, `${name}.png`), fullPage });
  if (archiveDir) { mkdirSync(archiveDir, { recursive: true }); copyFileSync(resolve(outputDir, `${name}.png`), resolve(archiveDir, `${name}.png`)); }
  const metadata = await page.evaluate(async () => {
    const app = document.querySelector<HTMLElement>('[data-testid="kr4-app"]');
    return { url: location.href, capturedAt: new Date().toISOString(), viewport: { width: innerWidth, height: innerHeight }, devicePixelRatio, userAgent: navigator.userAgent, platform: navigator.platform,
      source: document.querySelector('[data-testid="kr4-app"]')?.getAttribute('data-source'), sourceCommit: document.querySelector('[data-testid="kr4-app"]')?.getAttribute('data-source-commit'),
      uiCommit: app?.dataset.uiCommit || '', uiVersion: app?.dataset.uiVersion || '', inputHashes: JSON.parse(app?.dataset.inputHashes || '{}'), generationId: app?.dataset.generationId || '', stocksSha256: app?.dataset.stocksSha || '', fontSize: getComputedStyle(document.documentElement).fontSize, controlled: false, physicalLgGramVerified: false };
  });
  save(name, { ...metadata, kind: process.env.KR4_PUBLIC_URL ? 'PUBLIC_SITE_REAL_DATA_BROWSER_CAPTURE' : 'LOCAL_SITE_REAL_DATA_BROWSER_CAPTURE', browserEnvironment: `${process.platform} ${process.env.KR4_BROWSER_CHANNEL || 'chromium'}`, actualWindowsBrowser: process.platform === 'win32', fullPage, scenario,
    emulationNote: 'CSS viewport and DPR emulate display scale and browser zoom; actual physical LG Gram display settings were not changed.' });
}
async function measure(page: Page) {
  return page.evaluate(() => {
    const rect = (r: DOMRect) => ({ x: +r.x.toFixed(2), y: +r.y.toFixed(2), width: +r.width.toFixed(2), height: +r.height.toFixed(2) });
    const visible = (e: Element) => !!(e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden');
    const label = (e: Element) => `${e.tagName.toLowerCase()}.${e.className || ''} ${(e.getAttribute('aria-label') || e.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 100)}`;
    const controls = [...document.querySelectorAll('button, [role="button"], input:not([type="checkbox"]):not([type="radio"]), select, summary')].filter(visible);
    const shortControls = controls.filter(e => { const r = e.getBoundingClientRect(); return r.width < 43.5 || r.height < 43.5; }).map(e => ({ element: label(e), rect: rect(e.getBoundingClientRect()) }));
    const viewportButtons = controls.filter(e => { const r = e.getBoundingClientRect(); return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth; });
    const overlaps: unknown[] = [];
    viewportButtons.forEach((a, i) => viewportButtons.slice(i + 1).forEach(b => {
      if (a.contains(b) || b.contains(a)) return;
      const ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
      if (Math.min(ar.right, br.right) - Math.max(ar.left, br.left) > 1 && Math.min(ar.bottom, br.bottom) - Math.max(ar.top, br.top) > 1) overlaps.push({ a: label(a), b: label(b), aRect: rect(ar), bRect: rect(br) });
    }));
    const core = [...document.querySelectorAll('.metric-toggle, .stock-name, .rank-badge, .rank-pending, .stock-score, .safety-badge, .source-label, .dtl-company-identity h1, .dtl-score, .dtl-chip, [data-testid="chart_latest_close"], [data-testid="chart_source"]')].filter(visible);
    const clipped: unknown[] = [];
    for (const e of core) {
      const range = document.createRange(); range.selectNodeContents(e);
      const textRects = [...range.getClientRects()].filter(r => r.width > 0 && r.height > 0);
      for (let ancestor: Element | null = e; ancestor && ancestor !== document.body; ancestor = ancestor.parentElement) {
        const style = getComputedStyle(ancestor), bounds = ancestor.getBoundingClientRect();
        const clipsX = ['hidden', 'clip', 'scroll', 'auto'].includes(style.overflowX), clipsY = ['hidden', 'clip', 'scroll', 'auto'].includes(style.overflowY);
        if (textRects.some(r => (clipsX && (r.left < bounds.left - 1 || r.right > bounds.right + 1)) || (clipsY && (r.top < bounds.top - 1 || r.bottom > bounds.bottom + 1)))) {
          clipped.push({ element: label(e), rect: rect(e.getBoundingClientRect()), clippedBy: label(ancestor), ancestorRect: rect(bounds), textRects: textRects.map(rect) }); break;
        }
      }
    }
    const auxiliary = [...document.querySelectorAll('small, .muted, .stock-meta, .stock-sector, .status-lines, .dtl-footnote, .dtl-muted, .dtl-eyebrow, .pc-chart__muted')].filter(visible);
    const smallAuxiliary = auxiliary.filter(e => parseFloat(getComputedStyle(e).fontSize) < 18).map(e => ({ element: label(e), font: getComputedStyle(e).fontSize }));
    const menuFonts = [...document.querySelectorAll('.app-nav button, .dtl-tabs button')].filter(visible).map(e => ({ element: label(e), font: parseFloat(getComputedStyle(e).fontSize) }));
    const selector = document.querySelector('.metric-selector');
    const metricButtons = [...document.querySelectorAll('[data-testid^="metric-toggle-"]')].filter(visible).map(e => { const r = e.getBoundingClientRect(); return { element: label(e), rect: rect(r), fullyInInitialViewport: r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight }; });
    return { rootFont: getComputedStyle(document.documentElement).fontSize, bodyFont: parseFloat(getComputedStyle(document.body).fontSize), horizontalOverflow: { pass: document.documentElement.scrollWidth <= innerWidth + 1, documentWidth: document.documentElement.scrollWidth, viewportWidth: innerWidth },
      coreCount: core.length, clipped, controlCount: controls.length, shortControls, overlaps, smallAuxiliary, menuFonts,
      selector: selector && visible(selector) ? { position: getComputedStyle(selector).position, rect: rect(selector.getBoundingClientRect()), buttons: metricButtons } : null };
  });
}
for (const viewport of viewports) for (const font of [22, 26, 30]) {
  test(`real layout ${viewport.width}x${viewport.height} DPR${viewport.dpr} font${font}`, async ({ browser, baseURL }) => {
    const scenario = { ...viewport, font, actualWindowsBrowser: process.platform === 'win32', physicalLgGramVerified: false }, key = `${viewport.width}x${viewport.height}-dpr${viewport.dpr}-font${font}`;
    const context = await browser.newContext({ baseURL, viewport, deviceScaleFactor: viewport.dpr, locale: 'ko-KR', timezoneId: 'Asia/Seoul', reducedMotion: 'reduce' });
    const page = await context.newPage(), results: unknown[] = [], failures: string[] = [];
    try {
      await ready(page, '');
      for (let size = 22; size < font; size += 2) await page.getByRole('button', { name: '글씨 더 크게', exact: true }).click();
      await page.reload();
      await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
      expect(await page.evaluate(() => getComputedStyle(document.documentElement).fontSize)).toBe(`${font}px`);
      for (const route of matrixRoutes) {
        await ready(page, route.hash); await expect(page.locator(route.required).first()).toBeVisible(); await page.evaluate(() => scrollTo(0, 0));
        const measurement = await measure(page), routeFailures: string[] = [];
        if (!measurement.horizontalOverflow.pass) routeFailures.push('document horizontal overflow');
        if (measurement.clipped.length) routeFailures.push(`${measurement.clipped.length} clipped core fields`);
        if (measurement.shortControls.length) routeFailures.push(`${measurement.shortControls.length} controls smaller than 44x44`);
        if (measurement.overlaps.length) routeFailures.push(`${measurement.overlaps.length} overlapping visible controls`);
        if (measurement.smallAuxiliary.length) routeFailures.push(`${measurement.smallAuxiliary.length} explanatory texts below 18px`);
        if (measurement.menuFonts.some(item => item.font < 20)) routeFailures.push('menu text below 20px');
        if (measurement.bodyFont < 20 || measurement.rootFont !== `${font}px`) routeFailures.push('incorrect body/root font');
        let scrollAway: boolean | null = null;
        if (route.name === 'ranking') {
          if (measurement.selector?.position !== 'static') routeFailures.push('metric selector is not static');
          if (measurement.selector?.buttons.length !== 4) routeFailures.push('four expanded metrics missing');
          // Below-fold controls remain valid: all four must be expanded without opening a selector menu.
          // Click each after normal scrolling; Playwright checks visibility, stability and pointer reception.
          for (const id of ['M01', 'M02', 'M03', 'M04']) {
            const button = page.getByTestId(`metric-toggle-${id}`);
            await button.scrollIntoViewIfNeeded();
            await expect(button).toBeVisible();
            await button.click(); await expect(button).toHaveAttribute('aria-pressed', 'false');
            await button.click(); await expect(button).toHaveAttribute('aria-pressed', 'true');
          }
          await page.locator('.ranking-stock-table .stock-row').nth(20).scrollIntoViewIfNeeded();
          const box = await page.locator('.metric-selector').boundingBox(); scrollAway = !!box && box.y + box.height < 0;
          if (!scrollAway) routeFailures.push('metric selector does not scroll away'); await page.evaluate(() => scrollTo(0, 0));
        }
        if (route.name === 'analysis') {
          await page.locator('.dtl-quarter-table').scrollIntoViewIfNeeded(); const quarter = await measure(page);
          if (!quarter.horizontalOverflow.pass || quarter.clipped.length || quarter.overlaps.length) routeFailures.push('quarter section overflow/clipping/control overlap');
          results.push({ route: 'quarter-scroll', measurement: quarter }); await page.evaluate(() => scrollTo(0, 0));
        }
        results.push({ route: route.name, measurement, scrollAway, pass: !routeFailures.length, failures: routeFailures });
        if (routeFailures.length) { failures.push(`${route.name}: ${routeFailures.join('; ')}`); await capture(page, `failure-${key}-${route.name}`, false, scenario); }
      }
      save(`metrics-${key}`, { scenario, checkedRoutes: matrixRoutes.map(route => route.name), results, pass: !failures.length, failures, environment: `${process.platform} ${process.env.KR4_BROWSER_CHANNEL || 'chromium'}; CSS viewport/DPR emulation; physical LG Gram not tested` });
      expect(failures, JSON.stringify({ key, failures })).toEqual([]);
    } finally { await context.close(); }
  });
}
for (const viewport of [{ width: 1365, height: 853 }, { width: 1920, height: 1200 }]) {
  test(`original canonical captures ${viewport.width}x${viewport.height}`, async ({ browser, baseURL }) => {
    const context = await browser.newContext({ baseURL, viewport, deviceScaleFactor: 1, locale: 'ko-KR', timezoneId: 'Asia/Seoul', reducedMotion: 'reduce' });
    const page = await context.newPage();
    try {
      for (const route of routes) {
        await ready(page, route.hash); await expect(page.locator(route.required).first()).toBeVisible(); await capture(page, `canonical-${route.name}-${viewport.width}x${viewport.height}`);
        if (route.name === 'analysis' || route.name === 'sources') await capture(page, `canonical-${route.name}-${viewport.width}x${viewport.height}-fullpage`, true);
        if (route.name === 'analysis') { await page.locator('.dtl-quarter-table').scrollIntoViewIfNeeded(); await capture(page, `canonical-quarter-${viewport.width}x${viewport.height}`); }
      }
    } finally { await context.close(); }
  });
}
