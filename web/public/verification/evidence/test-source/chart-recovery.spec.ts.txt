import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { createHash } from 'node:crypto';
import type { DailyBar, PriceData, PublicationManifest } from '../src/types';

const number = (value: number) => value.toLocaleString('ko-KR', { maximumFractionDigits: 2 });
const sha256 = (text: string) => createHash('sha256').update(text).digest('hex');
async function ready(page: Page, code = '005930') {
  const target = new URL(process.env.KR4_PUBLIC_URL || 'http://127.0.0.1:4173/Kook/'); target.hash = `stock/${code}/4`;
  await page.goto(target.href);
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'LIVE');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
  await expect(page.getByTestId('price_chart_panel')).toBeVisible();
}
async function publication(page: Page) {
  const base = new URL((await page.getByTestId('kr4-app').getAttribute('data-data-base'))!, page.url());
  const response = await page.request.get(new URL('manifest.json', base).href); expect(response.ok()).toBeTruthy();
  const manifest = await response.json() as PublicationManifest;
  return { manifest, prefix: new URL(manifest.basePath || './', base) };
}
async function price(page: Page, manifest: PublicationManifest, prefix: URL, code: string) {
  const path = `prices/${code}.json`, url = new URL(path, prefix).href;
  const response = await page.request.get(url); expect(response.ok()).toBeTruthy();
  const text = await response.text(); expect(sha256(text)).toBe(manifest.files[path].sha256);
  expect(Buffer.byteLength(text)).toBe(manifest.files[path].bytes);
  return { data: JSON.parse(text) as PriceData, url, sha256: sha256(text) };
}
async function evidence(testInfo: TestInfo, value: object) {
  await testInfo.attach('chart-recovery-provenance', { body: JSON.stringify(value, null, 2), contentType: 'application/json' });
}
function sixMonthsBefore(day: string) {
  const date = new Date(`${day}T00:00:00Z`), wantedDay = date.getUTCDate();
  date.setUTCDate(1); date.setUTCMonth(date.getUTCMonth() - 6);
  date.setUTCDate(Math.min(wantedDay, new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 0)).getUTCDate()));
  return date.toISOString().slice(0, 10);
}
// Expectations use immutable actual observations, independently of the chart
// model. Exact OHLC aggregation remains covered by the original Kotlin oracle.
function observedWindows(rows: DailyBar[], interval: 'DAY' | 'WEEK' | 'MONTH') {
  const groups = new Map<string, DailyBar[]>();
  for (const row of rows) {
    const day = new Date(`${row.date}T00:00:00Z`);
    if (interval === 'WEEK') day.setUTCDate(day.getUTCDate() - (day.getUTCDay() + 6) % 7);
    const key = interval === 'MONTH' ? row.date.slice(0, 7) : day.toISOString().slice(0, 10);
    groups.set(key, [...(groups.get(key) || []), row]);
  }
  const from = sixMonthsBefore(rows.at(-1)!.date);
  return [...groups.values()].filter(group => group.at(-1)!.date >= from);
}
function lastPeriodEnd(day: string, interval: 'WEEK' | 'MONTH') {
  const date = new Date(`${day}T00:00:00Z`);
  if (interval === 'MONTH') date.setUTCMonth(date.getUTCMonth() + 1, 0);
  else date.setUTCDate(date.getUTCDate() + 4 - (date.getUTCDay() + 6) % 7);
  return date.toISOString().slice(0, 10);
}

test('actual Samsung six-month observed bars expose bounds, changes and partial-period dates', async ({ page }, testInfo) => {
  await ready(page);
  const { manifest, prefix } = await publication(page), actual = await price(page, manifest, prefix, '005930');
  expect(actual.data.source).toBe('CACHE');
  const rows = actual.data.bars.filter(row => row.date <= manifest.priceCutoffDate);
  expect(rows.length).toBeGreaterThan(100); expect(new Set(rows.map(row => row.date)).size).toBe(rows.length);
  expect(rows.every(row => ![0, 6].includes(new Date(`${row.date}T00:00:00Z`).getUTCDay()))).toBeTruthy();
  expect(rows.map(row => row.date)).toEqual(rows.map(row => row.date).sort());
  await expect(page.getByTestId('chart_latest_close')).toHaveText(`${number(rows.at(-1)!.close)}원`);
  for (const sourceDate of [actual.data.collectedAt!, actual.data.lastAttemptAt, actual.data.lastTradingDate!, manifest.priceCutoffDate]) await expect(page.getByTestId('chart_source')).toContainText(sourceDate);
  await page.getByTestId('chart_period_6').click();
  const toggle = page.getByTestId('chart_candle_toggle'); if (await toggle.getAttribute('aria-checked') !== 'true') await toggle.click();
  const observations = [];
  for (const interval of ['DAY', 'WEEK', 'MONTH'] as const) {
    await page.getByTestId(`chart_interval_${interval}`).click();
    const windows = observedWindows(rows, interval), first = windows[0][0], last = windows.at(-1)!.at(-1)!;
    const included = windows.flat(), low = Math.min(...included.map(row => row.low)), high = Math.max(...included.map(row => row.high));
    await expect(page.getByTestId('chart_candle')).toHaveCount(windows.length);
    await expect(page.locator('.pc-chart__caption')).toContainText(`${windows.length}개 관측 봉`);
    await expect(page.getByTestId('chart_bar_count')).toHaveText(`표시 범위 ${first.date} ~ ${last.date} · 최저 ${number(low)}원 · 최고 ${number(high)}원`);
    const change = (last.close / windows[0].at(-1)!.close - 1) * 100;
    await expect(page.getByTestId('chart_window_change')).toHaveText(`선택 기간 원본 가격 변동 ${change >= 0 ? '+' : ''}${change.toFixed(2)}%`);
    // Exact observed date pairs catch synthetic weekend/holiday candles.
    const displayedDates = await page.getByTestId('chart_candle').locator('title').allTextContents();
    expect(displayedDates.map(text => text.split(': ')[0])).toEqual(windows.map(group => `${group[0].date} ~ ${group.at(-1)!.date}`));
    if (interval !== 'DAY') {
      const partial = lastPeriodEnd(last.date, interval) > manifest.priceCutoffDate, note = page.getByTestId('chart_aggregation_note');
      await expect(note).toContainText('첫 거래일부터 집계');
      if (partial) await expect(note).toContainText('기준일까지의 부분 봉'); else await expect(note).not.toContainText('기준일까지의 부분 봉');
      await expect(page.locator('.pc-chart__selected-date')).toContainText(`실제 ${windows.at(-1)!.length}거래일`);
      observations.push({ interval, count: windows.length, first: first.date, last: last.date, low, high, change, partial });
    } else observations.push({ interval, count: windows.length, first: first.date, last: last.date, low, high, change });
  }
  await evidence(testInfo, { kind: process.env.KR4_PUBLIC_URL ? 'PUBLIC_SITE_REAL_DATA_BROWSER_CHECK' : 'LOCAL_SITE_REAL_DATA_BROWSER_CHECK', controlled: false, priceUrl: actual.url, priceSha256: actual.sha256, source: actual.data.source, cutoff: manifest.priceCutoffDate, observations });
});

test('real CACHE survives a controlled price 503 and recovers; genuine MISSING stays empty with retry', async ({ page }, testInfo) => {
  await ready(page);
  const { manifest, prefix } = await publication(page), actual = await price(page, manifest, prefix, '005930');
  await expect(page.getByTestId('chart_latest_close')).toHaveText(`${number(actual.data.bars.at(-1)!.close)}원`);
  await page.getByTestId('chart_period_6').click(); await page.getByTestId('chart_interval_DAY').click();
  const toggle = page.getByTestId('chart_candle_toggle'); if (await toggle.getAttribute('aria-checked') !== 'true') await toggle.click();
  const before = { titles: await page.getByTestId('chart_candle').locator('title').allTextContents(), ohlc: await page.getByTestId('chart_last_ohlc').innerText(), quote: await page.getByTestId('chart_latest_close').innerText() };
  let failures = 0, restored = 0;
  await page.route(actual.url, async route => { failures++; await route.fulfill({ status: 503, contentType: 'text/plain', body: 'CONTROLLED_NETWORK_SEQUENCE: price HTTP 503' }); });
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click();
  await expect.poll(() => failures).toBe(1); await expect(page.getByRole('button', { name: '다시 불러오기', exact: true })).toBeEnabled();
  expect(await page.getByTestId('chart_candle').locator('title').allTextContents()).toEqual(before.titles);
  expect(await page.getByTestId('chart_last_ohlc').innerText()).toBe(before.ohlc); await expect(page.getByTestId('chart_latest_close')).toHaveText(before.quote);
  await expect(page.getByTestId('chart_source')).toContainText('CACHE');
  await expect.soft(page.locator('.pc-chart__error')).toContainText('503');
  await expect.soft(page.locator('.pc-chart__error')).toContainText('CACHE');
  await page.unroute(actual.url);
  await page.route(actual.url, async route => { restored++; await route.continue(); });
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click();
  await expect.poll(() => restored).toBe(1); await expect(page.getByRole('button', { name: '다시 불러오기', exact: true })).toBeEnabled();
  await expect(page.locator('.pc-chart__error')).toHaveCount(actual.data.error ? 1 : 0);
  expect(await page.getByTestId('chart_candle').locator('title').allTextContents()).toEqual(before.titles);
  expect(await page.getByTestId('chart_last_ohlc').innerText()).toBe(before.ohlc); await page.unroute(actual.url);

  const indexResponse = await page.request.get(new URL('prices/index.json', prefix).href); expect(indexResponse.ok()).toBeTruthy();
  const index = await indexResponse.json() as { prices: Record<string, { source: string; bars: number }> };
  const missingCodes = Object.entries(index.prices).filter(([, row]) => row.source === 'MISSING' && row.bars === 0).map(([code]) => code);
  expect(missingCodes.length, 'Actual publication must include a genuine MISSING case for this regression').toBeGreaterThan(0);
  const missingCode = missingCodes.includes('005030') ? '005030' : missingCodes[0], missing = await price(page, manifest, prefix, missingCode);
  expect(missing.data.bars).toEqual([]); expect(missing.data.source).toBe('MISSING'); expect(missing.data.error).toBeTruthy();
  let missingRequests = 0;
  await page.route(missing.url, async route => { missingRequests++; await route.continue(); });
  await ready(page, missingCode);
  await expect(page.getByTestId('chart_source')).toContainText('MISSING');
  await expect(page.locator('.pc-chart__empty')).toContainText('수집된 실제 일봉이 없습니다');
  await expect(page.locator('.pc-chart__error')).toContainText(missing.data.error!);
  for (const id of ['chart_candle', 'chart_volume_bar', 'chart_price_line', 'chart_latest_close']) await expect(page.getByTestId(id)).toHaveCount(0);
  const attemptsBefore = missingRequests;
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click();
  await expect.poll(() => missingRequests).toBe(attemptsBefore + 1);
  await expect(page.locator('.pc-chart__error')).toContainText(missing.data.error!); await expect(page.getByTestId('chart_candle')).toHaveCount(0);
  await evidence(testInfo, { kind: 'CONTROLLED_NETWORK_SEQUENCE', controlled: true, actualPriceUrl: actual.url, actualPriceSha256: actual.sha256, injectedStatus: 503, injectedFailures: failures, restoredActualRequests: restored, preservedCandleCount: before.titles.length, missingCode, missingUrl: missing.url, missingSha256: missing.sha256, genuineMissingReason: missing.data.error, missingRequests });
});
