import { test, expect, type Page } from '@playwright/test';
import { createHash } from 'node:crypto';
import type { Candidate, FinancialRecord, Stock, ValuationBand } from '../src/types';

const metricFields = ['m01RevGrowth', 'm02OpMargin', 'm03Per', 'm04Price6m'] as const;
const number = (value: number, digits = 0) => new Intl.NumberFormat('ko-KR', { maximumFractionDigits: digits }).format(value);
function target(route = '') {
  const url = new URL(process.env.KR4_PUBLIC_URL || 'http://127.0.0.1:4173/Kook/');
  url.hash = route.replace(/^#/, ''); // Preserve a caller's pinned URL query and deployment base.
  return url.href;
}
async function ready(page: Page, route = '') {
  await page.goto(target(route));
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'LIVE');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
  await page.evaluate(() => document.fonts.ready);
}
async function publication(page: Page) {
  const base = new URL((await page.getByTestId('kr4-app').getAttribute('data-data-base'))!, page.url());
  const response = await page.request.get(new URL('manifest.json', base).href);
  expect(response.ok()).toBeTruthy();
  const manifest = await response.json();
  const prefix = new URL(manifest.basePath || './', base);
  async function read<T>(file: string): Promise<T> {
    const response = await page.request.get(new URL(file, prefix).href);
    expect(response.ok(), file).toBeTruthy();
    const bytes = await response.body();
    expect(createHash('sha256').update(bytes).digest('hex'), `${file} published content hash`).toBe(manifest.files[file].sha256);
    return JSON.parse(bytes.toString('utf8')) as T;
  }
  const stocks = await read<Stock[]>('stocks.json');
  const ranks = await read<Record<string, Record<string, { rank: number | null; score: number | null; isEligible: boolean }>>>('rankings.json');
  return { manifest, stocks, ranks, read };
}
const menu = (page: Page, name: string) => page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name, exact: true });

test('CONTROLLED_LOCAL_PREF: favorite picker sorts, cancels, saves and independently removes stale and known favorites', async ({ page }) => {
  test.info().annotations.push({ type: 'CONTROLLED_LOCAL_PREF', description: 'Only this browser favorite preference is seeded with unknown ZZZZZZ; all stocks remain actual published data.' });
  await page.addInitScript(() => localStorage.setItem('kr4-pc:favorites', JSON.stringify(['ZZZZZZ'])));
  await ready(page, '#favorites');
  const data = await publication(page);
  const list = page.locator('.favorite-list');
  await expect(list).toContainText('ZZZZZZ · 현재 게시자료에서 종목을 확인하지 못했습니다.');
  await page.getByRole('button', { name: 'ZZZZZZ 관심종목 삭제', exact: true }).click();
  await expect(list.locator('.favorite-row')).toHaveCount(0);
  await expect(page).toHaveURL(/#favorites$/);

  await page.getByRole('button', { name: '관심종목 추가', exact: true }).click();
  const picker = page.getByRole('dialog');
  const expected = [...data.stocks].sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : a.issuerId < b.issuerId ? -1 : a.issuerId > b.issuerId ? 1 : 0);
  await expect(picker.locator('.favorite-choice strong').first()).toHaveText(expected[0].name);
  await expect(picker.locator('.favorite-choice small').first()).toContainText(expected[0].issuerId);
  await picker.getByRole('textbox').fill('005930');
  await picker.getByRole('checkbox').check();
  await picker.getByRole('button', { name: '취소', exact: true }).click();
  await expect(picker).toHaveCount(0);
  await expect(list.locator('.favorite-row')).toHaveCount(0);

  await page.getByRole('button', { name: '관심종목 추가', exact: true }).click();
  await picker.getByRole('textbox').fill('CONTROLLED_NO_MATCH_1934');
  await expect(picker.getByRole('status')).toContainText('검색 결과가 없습니다.');
  await expect(picker.getByRole('checkbox')).toHaveCount(0);
  await picker.getByRole('textbox').fill('005930');
  await picker.getByRole('checkbox').check();
  const rect = await picker.boundingBox(); expect(rect).not.toBeNull();
  expect(rect!.x).toBeGreaterThan(2);
  await page.mouse.click(1, Math.max(1, rect!.y + 5)); // Native dialog backdrop, outside its content box.
  await expect(picker).toHaveCount(0);
  await expect(list.locator('.favorite-row')).toHaveCount(0);

  await page.getByRole('button', { name: '관심종목 추가', exact: true }).click();
  for (const code of ['005930', '000660']) {
    await picker.getByRole('textbox').fill(code);
    await picker.getByRole('checkbox').check();
  }
  await picker.getByRole('button', { name: '선택 완료 (2개)', exact: true }).click();
  await expect(list.locator('.favorite-row')).toHaveCount(2);
  const samsung = data.stocks.find(stock => stock.issuerId === '005930')!;
  await page.getByRole('button', { name: `${samsung.name} 관심종목 삭제`, exact: true }).click();
  await expect(list.locator('.favorite-row')).toHaveCount(1);
  await expect(list).toContainText('000660');
  await expect(page).toHaveURL(/#favorites$/);
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('kr4-pc:favorites')!))).toEqual(['000660']);
});

test('actual alphanumeric deep link, accessible detail refresh, exact unknown code and focused search recovery', async ({ page }) => {
  await ready(page, '#stock/0001A0');
  const data = await publication(page);
  const actual = data.stocks.find(stock => stock.issuerId === '0001A0'); expect(actual).toBeDefined();
  await expect(page.getByTestId('stock-detail').getByRole('heading', { level: 1 })).toContainText(actual!.name);
  await expect(page.getByTestId('stock-detail')).toContainText('0001A0');
  await page.getByRole('button', { name: '데이터 갱신 상태 열기', exact: true }).press('Enter');
  const update = page.getByRole('dialog', { name: '데이터 갱신 상태', exact: true });
  await expect(update).toContainText('최근 확인 시도');
  await expect(update).toContainText(data.manifest.snapshotDate);
  const refreshed = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/manifest.json') && response.request().method() === 'GET');
  await update.getByRole('button', { name: '지금 자료 갱신', exact: true }).click();
  expect((await refreshed).ok()).toBeTruthy();
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
  await expect(update.getByRole('button', { name: '지금 자료 갱신', exact: true })).toBeEnabled();
  await update.getByRole('button', { name: '데이터 갱신 상태 닫기', exact: true }).click();
  await expect(update).toHaveCount(0);

  await ready(page, '#stock/ZZZZZZ');
  const alert = page.getByRole('alert');
  await expect(alert.getByRole('heading', { name: '종목을 찾을 수 없습니다', exact: true })).toBeVisible();
  await expect(alert.getByText('입력한 종목코드: ZZZZZZ', { exact: true })).toBeVisible();
  await expect(alert).toContainText('비슷한 이름의 다른 종목으로 자동 대체하지 않습니다.');
  await expect(page.getByTestId('stock-detail')).toHaveCount(0);
  await alert.getByRole('button', { name: '종목코드로 다시 검색', exact: true }).click();
  await expect(page.getByRole('searchbox')).toHaveValue('ZZZZZZ');
  await expect(page.getByRole('searchbox')).toBeFocused();
  await expect(page.locator('.ranking-results')).toContainText('검색 결과 0종목');
});

test('Home retains its original top five while selected ranking changes, and inline raw financial valuation candidate evidence expands independently', async ({ page }) => {
  await ready(page);
  const data = await publication(page);
  const home = page.locator('.home-page');
  const before = await home.locator('.stock-row .stock-meta').allTextContents();
  const top = data.stocks.filter(stock => data.ranks['15'][stock.issuerId].rank !== null).sort((a, b) => data.ranks['15'][a.issuerId].rank! - data.ranks['15'][b.issuerId].rank!).slice(0, 5);
  expect(before).toEqual(top.map(stock => `${stock.issuerId} · ${stock.market}`));
  await menu(page, '종목순위').click();
  for (const id of ['M02', 'M03', 'M04']) await page.getByTestId(`metric-toggle-${id}`).click();
  const firstSelected = data.stocks.find(stock => data.ranks['1'][stock.issuerId].rank === 1)!;
  await expect(page.locator('.ranking-stock-table .stock-row').first().locator('.stock-meta')).toHaveText(firstSelected.issuerId);
  await menu(page, '홈').click();
  expect(await home.locator('.stock-row .stock-meta').allTextContents()).toEqual(before);
  const stock = top[0];
  await home.getByRole('button', { name: `${stock.name} 한눈 비교 펼치기`, exact: true }).click();
  const evidence = home.getByRole('region', { name: `${stock.name} 한눈 비교`, exact: true });
  await expect(evidence.locator('.inline-metric')).toHaveCount(4);
  for (let index = 0; index < 4; index++) {
    const metric = stock[metricFields[index]], cell = evidence.locator('.inline-metric').nth(index);
    await expect(cell).toContainText(metric.nameKo);
    const digits = metric.id === 'M03' ? 2 : 1;
    const value = metric.rawValue == null || !metric.isAvailable ? 'N/A' : new Intl.NumberFormat('ko-KR', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(metric.rawValue) + metric.unit;
    await expect(cell).toContainText(value);
    await expect(cell).toContainText(metric.percentileScore == null ? '미확보' : `${number(metric.percentileScore, 1)}점`);
    if (metric.reason) await expect(cell).toContainText(metric.reason);
  }
  const financial = (await data.read<{ records: Record<string, FinancialRecord> }>('financial.json')).records[stock.issuerId];
  const band = (await data.read<{ records: Record<string, ValuationBand | null> }>('valuations.json')).records[stock.issuerId];
  const candidate = (await data.read<{ records: Record<string, Candidate> }>('candidates.json')).records[stock.issuerId];
  expect(financial).toBeDefined(); expect(band).toBeTruthy(); expect(candidate).toBeDefined();
  await expect(evidence).toContainText(band!.labelKo);
  await expect(evidence).toContainText(`FINAL #${candidate.candidateRank}`);
  await evidence.getByText('재무자료와 판정 근거', { exact: true }).click();
  await expect(evidence.locator('.inline-fact-rows')).toContainText(financial.reasonCode);
  await expect(evidence.locator('.inline-fact-rows')).toContainText(financial.facts!.receiptNo);
  await expect(evidence.locator('.inline-fact-rows')).toContainText(financial.facts!.sourceZipSha256);
  await expect(page).toHaveURL(/#home$|\/Kook\/(?:\?[^#]*)?$/);
  await home.getByRole('button', { name: `${stock.name} 한눈 비교 접기`, exact: true }).click();
  await expect(evidence).toHaveCount(0);
  await home.locator('.stock-row').first().click();
  await expect(page.getByTestId('stock-detail')).toContainText(stock.issuerId);
});

test('ranking guidance preserves equal weighting and complete-only filter while company-name sort retains original ranks', async ({ page }) => {
  await ready(page, '#ranking');
  const data = await publication(page);
  await page.getByTestId('metric-toggle-M04').click();
  const guide = page.getByRole('region', { name: '순위 산출 안내', exact: true });
  await expect(guide).toContainText('동일 비중(각 33.3%)');
  await expect(guide).toContainText('선택한 지표: 성장 · 수익성 · 가치');
  await expect(guide).toContainText('15가지 조합');
  await expect(guide).toContainText('선택한 지표가 없는 종목도 목록에 공개하며 순위는 보류합니다.');
  await expect(guide).toContainText('재무자료 상태는 점수와 순위에 영향을 주지 않습니다.');
  const eligible = data.stocks.filter(stock => data.ranks['7'][stock.issuerId].isEligible);
  await page.getByRole('checkbox', { name: '선택지표 완성 종목만 보기', exact: true }).check();
  await expect(page.getByRole('heading', { name: `검색 결과 ${number(eligible.length)}종목`, exact: true })).toBeVisible();
  await page.getByRole('navigation', { name: '종목 목록 페이지' }).getByRole('button', { name: '마지막', exact: true }).click();
  await expect(page.locator('.ranking-stock-table .incomplete-stock')).toHaveCount(0);
  await page.getByRole('combobox').filter({ has: page.locator('option[value="NAME"]') }).selectOption('NAME');
  const expected = eligible.sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : a.issuerId < b.issuerId ? -1 : a.issuerId > b.issuerId ? 1 : 0);
  const rows = page.locator('.ranking-stock-table .stock-row');
  for (let index = 0; index < 5; index++) {
    await expect(rows.nth(index).locator('.stock-name')).toHaveText(expected[index].name);
    await expect(rows.nth(index).locator('.stock-meta')).toHaveText(expected[index].issuerId);
    await expect(rows.nth(index).locator('.rank-badge')).toHaveText(`${data.ranks['7'][expected[index].issuerId].rank}위`);
  }
  await page.getByRole('checkbox', { name: '선택지표 완성 종목만 보기', exact: true }).uncheck();
  await expect(page.getByRole('heading', { name: `검색 결과 ${number(data.stocks.length)}종목`, exact: true })).toBeVisible();
});
