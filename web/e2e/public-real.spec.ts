import { test, expect, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ids = ['M01', 'M02', 'M03', 'M04'];
async function published(page: Page) {
  const base = new URL((await page.getByTestId('kr4-app').getAttribute('data-data-base'))!, page.url());
  const manifest = await (await page.request.get(new URL('manifest.json', base).href)).json();
  const prefix = new URL(manifest.basePath || './', base);
  const ranks = await (await page.request.get(new URL('rankings.json', prefix).href)).json();
  const stocks = await (await page.request.get(new URL('stocks.json', prefix).href)).json() as Array<{ issuerId: string; market: string }>;
  return { base, prefix, manifest, ranks, stocks };
}
async function ready(page: Page, route = '') {
  const target = new URL(process.env.KR4_PUBLIC_URL || 'http://127.0.0.1:4173/Kook/');
  target.hash = route.replace(/^#/, '');
  await page.goto(target.href);
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-source', 'LIVE');
  await expect(page.getByTestId('kr4-app')).toHaveAttribute('data-refreshing', 'false');
  await page.evaluate(() => document.fonts.ready);
}
async function capture(page: Page, name: string) {
  await page.evaluate(() => document.fonts.ready);
  const dir = resolve('test-results', process.env.KR4_PUBLIC_URL ? 'public-originals' : 'local-originals'); mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: resolve(dir, `${name}.png`), fullPage: true });
  const meta = await page.evaluate(() => ({
    url: location.href, capturedAt: new Date().toISOString(), viewport: { width: innerWidth, height: innerHeight },
    devicePixelRatio, userAgent: navigator.userAgent, source: document.querySelector('[data-testid="kr4-app"]')?.getAttribute('data-source'),
    sourceCommit: document.querySelector('[data-testid="kr4-app"]')?.getAttribute('data-source-commit'),
    fontSize: getComputedStyle(document.documentElement).fontSize, selfHostedKoreanFontReady: document.fonts.check('22px "Noto Sans KR Variable"', '국내주식'), controlled: false,
  }));
  writeFileSync(resolve(dir, `${name}.json`), JSON.stringify({ ...meta, kind: process.env.KR4_PUBLIC_URL ? 'PUBLIC_SITE_REAL_DATA_BROWSER_CAPTURE' : 'LOCAL_SITE_REAL_DATA_BROWSER_CAPTURE', runnerPlatform: process.platform, browserChannel: process.env.KR4_BROWSER_CHANNEL || 'chromium', actualWindowsBrowser: process.platform === 'win32', physicalLgGramVerified: false }, null, 2));
}
async function noOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  const clippedNames = await page.locator('.stock-name:visible').evaluateAll(elements => elements.filter(element => element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1).map(element => element.textContent));
  expect(clippedNames).toEqual([]);
}
test('anonymous real-data home, full universe and five navigation destinations', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await ready(page);
  await expect(page.getByRole('heading', { name: '4지표 상위 순위' })).toBeVisible();
  await expect(page.locator('.home-stock-table .stock-row')).toHaveCount(5);
  await expect(page.locator('.universe-counts:visible')).toContainText('2,649');
  const publication = await published(page);
  await expect(page.locator('.universe-counts:visible')).toContainText(Number(publication.manifest.coverage.completeCount).toLocaleString('ko-KR'));
  await expect(page.getByRole('button', { name: /로그인|ChatGPT 로그인|GitHub 로그인/ })).toHaveCount(0);
  await noOverflow(page); await capture(page, 'canonical-home');
  for (const name of ['종목순위', '종목검색', '관심종목', '더보기', '홈']) {
    await page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name, exact: true }).click();
    await expect(page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name, exact: true })).toHaveAttribute('aria-current', 'page');
  }
  expect(errors).toEqual([]);
});
test('expanded nonfixed selectors and every mask use original global ranks', async ({ page }) => {
  await ready(page, '#ranking');
  const { ranks } = await published(page);
  for (const id of ids) await expect(page.getByTestId(`metric-toggle-${id}`)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.ranking-stock-table .stock-row')).toHaveCount(50);
  expect(await page.locator('.metric-selector').evaluate(element => getComputedStyle(element).position)).toBe('static');
  await page.locator('.ranking-stock-table .stock-row').nth(20).scrollIntoViewIfNeeded();
  expect((await page.locator('.metric-selector').boundingBox())!.y + (await page.locator('.metric-selector').boundingBox())!.height).toBeLessThan(0);
  for (let mask = 1; mask <= 15; mask++) {
    for (let index = 0; index < 4; index++) if (mask & 1 << index) {
      const button = page.getByTestId(`metric-toggle-${ids[index]}`);
      if (await button.getAttribute('aria-pressed') !== 'true') await button.click();
    }
    for (let index = 0; index < 4; index++) if (!(mask & 1 << index)) {
      const button = page.getByTestId(`metric-toggle-${ids[index]}`);
      if (await button.getAttribute('aria-pressed') === 'true') await button.click();
    }
    const expected = Object.values(ranks[String(mask)] as Record<string, { issuerId: string; score: number | null; rank: number | null }>).filter(row => row.rank !== null).sort((a, b) => a.rank! - b.rank!);
    const rows = page.locator('.ranking-stock-table .stock-row');
    for (let index = 0; index < 10; index++) {
      await expect(rows.nth(index).locator('.stock-meta')).toHaveText(expected[index].issuerId);
      await expect(rows.nth(index).locator('.rank-badge')).toHaveText(`${expected[index].rank}위`);
      await expect(rows.nth(index).locator('.stock-score')).toHaveText(new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 }).format(expected[index].score!) + '점');
    }
  }
  await capture(page, 'canonical-ranking');
});
test('search, market, risk, sort, missing rows and return navigation remain usable', async ({ page }) => {
  await ready(page, '#ranking');
  const search = page.getByRole('searchbox');
  await search.fill('005930');
  const samsung = page.locator('.ranking-stock-table .stock-row'); await expect(samsung).toHaveCount(1);
  const publication = await published(page);
  await expect(samsung).toContainText('삼성전자'); await expect(samsung.locator('.rank-badge')).toHaveText(`${publication.ranks['15']['005930'].rank}위`);
  await samsung.click(); await expect(page.getByTestId('stock-detail')).toContainText('005930');
  await page.getByRole('button', { name: '종목 목록으로 돌아가기' }).click(); await expect(search).toHaveValue('005930');
  await search.fill('');
  await page.getByRole('group', { name: '시장', exact: true }).getByRole('button', { name: 'KOSPI', exact: true }).click();
  const kospi = publication.stocks.filter(stock => stock.market === 'KOSPI').map(stock => publication.ranks['15'][stock.issuerId]).filter(row => row.rank !== null).sort((a, b) => a.rank - b.rank);
  await expect(page.locator('.ranking-stock-table .stock-row').first().locator('.rank-badge')).toHaveText(`${kospi[0].rank}위`);
  await page.getByRole('group', { name: '시장', exact: true }).getByRole('button', { name: '전체', exact: true }).click();
  await page.getByRole('group', { name: '재무자료 상태' }).getByRole('button', { name: '재무위험만' }).click();
  await expect(page.locator('.ranking-stock-table .stock-row').first().locator('.safety-badge')).toHaveText('재무위험');
  await page.getByRole('group', { name: '재무자료 상태' }).getByRole('button', { name: '미확보만' }).click();
  await expect(page.locator('.ranking-stock-table .stock-row').first().locator('.safety-badge')).toHaveText('미확보');
  await page.getByRole('group', { name: '재무자료 상태' }).getByRole('button', { name: '전체', exact: true }).click();
  await page.getByRole('combobox').filter({ has: page.locator('option[value="CODE"]') }).selectOption('CODE');
  await expect(page.locator('.ranking-stock-table .stock-row').first().locator('.stock-meta')).toHaveText('000020');
  await page.getByRole('combobox').filter({ has: page.locator('option[value="CODE"]') }).selectOption('COMBINATION');
  await page.getByRole('navigation', { name: '종목 목록 페이지' }).getByRole('button', { name: '마지막', exact: true }).click();
  await expect(page.locator('.incomplete-stock').first()).toContainText('결측');
  await noOverflow(page);
});
test('five detailed tabs, original analysis, real quarters, source failure and financial facts', async ({ page }) => {
  await ready(page, '#stock/005930'); await expect(page.getByTestId('stock-detail')).toBeVisible();
  await expect(page.getByRole('tab')).toHaveCount(5);
  await page.getByRole('tab', { name: '4지표', exact: true }).click();
  await expect(page.getByRole('heading', { name: '4지표 상세', exact: true })).toBeVisible();
  await expect(page.locator('.dtl-two-columns > .dtl-panel')).toHaveCount(4);
  await capture(page, 'canonical-detail');
  await page.getByRole('tab', { name: '종합해석', exact: true }).click();
  await expect(page.getByRole('tabpanel').filter({ visible: true })).toContainText('CORE READ');
  await expect(page.getByRole('tabpanel').filter({ visible: true })).toContainText('분기');
  await capture(page, 'integrated-analysis-quarters');
  await page.getByRole('tab', { name: '체크·출처', exact: true }).click();
  await expect(page.getByRole('tabpanel').filter({ visible: true })).toContainText('출처');
  await capture(page, 'checks-sources');
  await page.getByRole('tab', { name: '요약', exact: true }).click();
  await expect(page.getByRole('tabpanel').filter({ visible: true })).toContainText('종합점수');
  await noOverflow(page);
});
test('all 24 actual chart selections preserve actual latest daily quote and CACHE provenance', async ({ page }) => {
  await ready(page, '#stock/005930/4');
  const publication = await published(page);
  const price = await (await page.request.get(new URL('prices/005930.json', publication.prefix).href)).json();
  const expectedClose = Number(price.bars.at(-1).close).toLocaleString('ko-KR') + '원';
  await expect(page.getByTestId('chart_latest_close')).toHaveText(expectedClose);
  for (const interval of ['DAY', 'WEEK', 'MONTH']) for (const months of [1, 3, 6, 12]) {
    await page.getByTestId(`chart_interval_${interval}`).click(); await page.getByTestId(`chart_period_${months}`).click();
    for (const candle of [true, false]) {
      const toggle = page.getByTestId('chart_candle_toggle'); if (await toggle.getAttribute('aria-checked') !== String(candle)) await toggle.click();
      await expect(page.getByTestId('price_chart_panel')).toHaveAttribute('data-chart-mode', candle ? 'candle' : 'line');
      await expect(page.getByTestId('chart_latest_close')).toHaveText(expectedClose);
      await expect(page.getByTestId('chart_source')).toContainText('CACHE');
      await expect(page.getByTestId('chart_last_ohlc')).toContainText('시가');
    }
  }
  await page.getByTestId('chart_interval_DAY').click(); await page.getByTestId('chart_period_6').click();
  if (await page.getByTestId('chart_candle_toggle').getAttribute('aria-checked') !== 'true') await page.getByTestId('chart_candle_toggle').click();
  await capture(page, 'canonical-chart'); await noOverflow(page);
});
test('font preference and per-browser favorites survive reload without sharing accounts', async ({ page, browser }) => {
  await ready(page);
  await page.getByRole('button', { name: '글씨 더 크게', exact: true }).click(); await page.getByRole('button', { name: '글씨 더 크게', exact: true }).click();
  await page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '관심종목', exact: true }).click();
  await page.getByRole('button', { name: '관심종목 추가', exact: true }).click();
  const picker = page.getByRole('dialog'); await picker.getByRole('textbox').fill('005930');
  await picker.getByRole('checkbox').check(); await picker.getByRole('button', { name: /선택 완료/ }).click();
  await expect(page.locator('.favorite-list')).toContainText('삼성전자');
  await page.reload(); await expect(page.locator('.favorite-list')).toContainText('삼성전자');
  expect(await page.evaluate(() => getComputedStyle(document.documentElement).fontSize)).toBe('26px');
  const separate = await browser.newContext(); const second = await separate.newPage();
  await second.goto(page.url()); await expect(second.getByTestId('kr4-app')).toHaveAttribute('data-source', 'LIVE');
  await expect(second.locator('.favorite-list .favorite-row')).toHaveCount(0);
  await expect(second.getByText('아직 저장한 관심종목이 없습니다.', { exact: false })).toBeVisible();
  expect(await second.evaluate(() => getComputedStyle(document.documentElement).fontSize)).toBe('22px');
  await separate.close();
  await page.getByRole('button', { name: '기본 큰 글씨', exact: true }).click(); await noOverflow(page);
});
