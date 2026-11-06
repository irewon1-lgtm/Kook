import { test, expect, type Page } from '@playwright/test';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';

const site = process.env.KR4_PUBLIC_URL || 'http://127.0.0.1:4173/Kook/';
const manifestPattern = '**/data/manifest.json';
async function visit(page: Page, hash = '') {
  const target = new URL(site); target.hash = hash.replace(/^#/, ''); await page.goto(target.href);
  await expect(page.getByTestId('kr4-app')).toBeVisible();
}
async function live(page: Page, hash = '') {
  await visit(page, hash); await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'LIVE');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
}
async function record(page: Page, name: string, scenario: string) {
  const dir = resolve('test-results/controlled-originals'); mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: resolve(dir, name + '.png'), fullPage: false });
  const observed = await page.evaluate(() => ({ url: location.href, browserClock: new Date().toISOString(),
    source: document.querySelector('[data-testid="kr4-app"]')?.getAttribute('data-source'), body: document.body.innerText,
    userAgent: navigator.userAgent, viewport: { width: innerWidth, height: innerHeight } }));
  writeFileSync(resolve(dir, name + '.json'), JSON.stringify({ ...observed, actualCapturedAtUtc: new Date().toISOString(),
    kind: 'CONTROLLED_FAILURE_OR_RECORDED_SEQUENCE_NOT_CURRENT_MARKET_PROOF', controlled: true, scenario,
    runnerPlatform: process.platform, browserChannel: process.env.KR4_BROWSER_CHANNEL || 'chromium', physicalLgGramVerified: false }, null, 2));
}
test('real last good data survives network failure and returns to LIVE after recovery', async ({ page }) => {
  await live(page);
  const before = await page.locator('.home-stock-table .stock-row').first().innerText();
  await page.route(manifestPattern, route => route.abort('internetdisconnected'));
  await page.getByRole('button', { name: '새 자료 확인', exact: true }).click();
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'CACHE');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
  expect(await page.locator('.home-stock-table .stock-row').first().innerText()).toBe(before);
  await expect(page.locator('.data-status:visible')).toContainText('갱신 실패');
  await record(page, 'controlled-cache', 'ABORTED_MANIFEST_LAST_VERIFIED_REAL_CACHE');
  await page.unroute(manifestPattern);
  await page.getByRole('button', { name: '새 자료 확인', exact: true }).click();
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'LIVE');
});
test('fresh anonymous profile uses validated bundled data on network failure', async ({ page }) => {
  await page.route(manifestPattern, route => route.abort('internetdisconnected'));
  await visit(page);
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'BUNDLED');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
  await expect(page.locator('.universe-counts:visible')).toContainText('2,649');
  await record(page, 'controlled-bundled', 'ABORTED_MANIFEST_FRESH_PROFILE_GENUINE_BUNDLE');
});
test('corrupt current cache falls back to the byte-identical verified backup', async ({ page }) => {
  await live(page);
  await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('kr4-pc-verified-v1', 1); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    const current = await new Promise<unknown>((resolve, reject) => {
      const request = database.transaction('artifacts').objectStore('artifacts').get('core:current');
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    if (!current) throw Error('Expected actual verified cache before corruption regression');
    await new Promise<void>((resolve, reject) => {
      const transaction = database.transaction('artifacts', 'readwrite'); const store = transaction.objectStore('artifacts');
      store.put(current, 'core:backup'); store.put({ ...(current as object), stocksText: '[]' }, 'core:current');
      transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error);
    });
    database.close();
  });
  await page.route(manifestPattern, route => route.abort('internetdisconnected')); await page.reload();
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'BACKUP');
  await expect(page.locator('.universe-counts:visible')).toContainText('2,649');
  await record(page, 'controlled-backup', 'CONTROLLED_CORRUPT_CURRENT_WITH_ACTUAL_VERIFIED_BACKUP');
});
test('invalid independent financial response never blocks genuine core reception', async ({ page }) => {
  await page.route('**/financial.json', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await live(page);
  await expect(page.locator('.home-stock-table .stock-row')).toHaveCount(5);
  await expect(page.locator('.home-stock-table .stock-row').first().locator('.safety-badge').first()).toHaveText('미확보');
  await expect(page.locator('.data-status:visible')).toContainText('재무');
  await record(page, 'controlled-financial-hold', 'CONTROLLED_HASH_INVALID_STAGE4_REAL_CORE_UNCHANGED');
});
test('a corrupted bundle and unavailable network show failure rather than invented stocks', async ({ page }) => {
  await page.addInitScript(() => {
    class BrokenBundleDecoder extends TransformStream<Uint8Array, Uint8Array> {
      constructor() { super({ transform() {}, flush(controller) { controller.enqueue(new TextEncoder().encode('[]')); } }); }
    }
    Object.defineProperty(window, 'DecompressionStream', { configurable: true, value: BrokenBundleDecoder });
  });
  await page.route(manifestPattern, route => route.abort('internetdisconnected'));
  await visit(page);
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'FAILED');
  await expect(page.locator('.home-stock-table .stock-row')).toHaveCount(0);
  await expect(page.locator('.data-status:visible')).toContainText('수신 실패');
  await record(page, 'controlled-failed', 'CONTROLLED_INVALID_BUNDLE_PLUS_ALL_CORE_NETWORK_FAILURE');
});
function recordedPair() {
  const archive = readFileSync(resolve('e2e/fixtures/background-recorded-pair.tar.gz'));
  expect(createHash('sha256').update(archive).digest('hex')).toBe('b2a38438881bfc9306da3773c0bded9abe58d3a84d21319179966ae6b432a4c9');
  const tar = gunzipSync(archive); const files = new Map<string, Buffer>(); let offset = 0;
  while (offset + 512 <= tar.length && tar[offset] !== 0) {
    const header = tar.subarray(offset, offset + 512); const name = header.subarray(0, 100).toString().replace(/\0.*$/s, '');
    const size = parseInt(header.subarray(124, 136).toString().replace(/\0.*$/s, '').trim(), 8);
    if (!['before/manifest.json', 'before/stocks.json', 'provenance.json'].includes(name) || !Number.isSafeInteger(size) || size < 0 || size > 32 * 1024 * 1024) throw Error('Unexpected recorded fixture member');
    files.set(name, tar.subarray(offset + 512, offset + 512 + size)); offset += 512 + Math.ceil(size / 512) * 512;
  }
  expect(files.size).toBe(3);
  const manifest = JSON.parse(files.get('before/manifest.json')!.toString());
  const stocksText = files.get('before/stocks.json')!.toString();
  expect(createHash('sha256').update(stocksText).digest('hex')).toBe(manifest.files['stocks.json'].sha256);
  return { manifest, stocksText, stocks: JSON.parse(stocksText) as Array<{ issuerId: string; rankOrder: number | null }> };
}
test('actual recorded earlier snapshot updates date, count and rank in memory through background reception', async ({ page }) => {
  await page.clock.install();
  await live(page, '#ranking');
  const base = new URL((await page.getByTestId('kr4-app').getAttribute('data-data-base'))!, page.url()).href;
  const current = await (await page.request.get(base + 'manifest.json')).json();
  const afterStocks = await (await page.request.get(base + (current.basePath || '') + 'stocks.json')).json() as Array<{ issuerId: string; rankOrder: number | null }>;
  const before = recordedPair();
  expect(before.manifest.snapshotDate).not.toBe(current.snapshotDate);
  let historical = true;
  await page.route('**/*', async route => {
    const url = route.request().url();
    if (historical && url === base + 'manifest.json') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...before.manifest, dataKind: 'REAL_MARKET_RECORDED' }) });
    if (historical && url === base + (before.manifest.basePath || '') + 'stocks.json') return route.fulfill({ status: 200, contentType: 'application/json', body: before.stocksText });
    if (historical && url.startsWith(base)) return route.abort('failed');
    return route.continue();
  });
  await page.reload(); await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'CACHE');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-data-kind', 'REAL_MARKET_RECORDED');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
  await expect(page.locator('.universe-counts:visible')).toContainText(Number(before.manifest.coverage.completeCount).toLocaleString('ko-KR'));
  const search = page.getByRole('searchbox'); await search.fill('000660');
  await expect(page.locator('.ranking-stock-table .stock-row .rank-badge')).toHaveText(String(before.stocks.find(row => row.issuerId === '000660')!.rankOrder) + '위');
  await record(page, 'controlled-background-before', 'UNCHANGED_GENUINE_OCT29_RECORDED_DATA_NOT_CURRENT_LIVE');
  let navigations = 0; page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigations++; });
  historical = false;
  await page.clock.fastForward(5 * 60_000 + 1000);
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'LIVE');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
  await expect(page.locator('.ranking-source-summary')).toContainText(current.snapshotDate);
  await expect(page.locator('.universe-counts:visible')).toContainText(Number(current.coverage.completeCount).toLocaleString('ko-KR'));
  await expect(page.locator('.ranking-stock-table .stock-row .rank-badge')).toHaveText(String(afterStocks.find(row => row.issuerId === '000660')!.rankOrder) + '위');
  await expect(search).toHaveValue('000660'); expect(navigations).toBe(0);
  await record(page, 'controlled-background-after', 'ACTUAL_CURRENT_PUBLICATION_RECEIVED_WITHOUT_RELOAD_CLOCK_ACCELERATED');
});
