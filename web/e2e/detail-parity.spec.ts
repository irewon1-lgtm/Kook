import { test, expect, type Locator, type Page } from '@playwright/test';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Detail, PublicationManifest } from '../src/types';

// Every expected value comes from an authenticated, immutable publication of the
// original Android engines. No product formatter or classification is imported.
const representatives = ['005930', '000020', '003060', '395400', '474930', '142210'];
const norm = (value: string) => value.replace(/\s+/gu, ' ').trim();
const exact = (value: string) => new RegExp(`^${value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`, 'u');
const fixed = (value: number | null | undefined, digits: number) => value == null ? '미확보' : new Intl.NumberFormat('ko-KR', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(value);
const percent = (value: number | null | undefined, digits = 1) => value == null ? '미확보' : `${fixed(value, digits)}%`;
const integer = (value: number | null) => value == null ? '미확보' : new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 }).format(value);
const date = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : value ? new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value)) + ' KST' : '미확인';
const statuses: Record<string, string> = { PASS: '자료 확인', FAIL: '재무위험', HOLD: '미확보', NOT_APPLICABLE: '비교제외' };

function panel(page: Page) { return page.getByRole('tabpanel').filter({ visible: true }); }
function section(scope: Locator, name: string) { return scope.locator('section.dtl-panel').filter({ has: scope.page().getByRole('heading', { name, exact: true }) }); }
function row(scope: Locator, label: string) { return scope.locator('dt').filter({ hasText: exact(label) }).first().locator('xpath=following-sibling::dd[1]'); }
async function openDetails(scope: Locator) {
  // Exercise the real disclosure controls, rather than making hidden text pass.
  const summaries = scope.locator('details:not([open]) > summary');
  while (await summaries.count()) await summaries.first().click();
}
async function authenticated<T>(page: Page, prefix: URL, manifest: PublicationManifest, name: string): Promise<T> {
  const response = await page.request.get(new URL(name, prefix).href);
  expect(response.ok(), name).toBeTruthy();
  const bytes = await response.body(), expected = manifest.files[name];
  expect(expected, `declared original-source file ${name}`).toBeTruthy();
  expect(bytes.length, `${name} byte count`).toBe(expected.bytes);
  expect(createHash('sha256').update(bytes).digest('hex'), `${name} SHA-256`).toBe(expected.sha256);
  return JSON.parse(bytes.toString('utf8')) as T;
}
async function original(page: Page, code: string) {
  const target = new URL(process.env.KR4_PUBLIC_URL || 'http://127.0.0.1:4173/Kook/'); target.hash = `stock/${code}`;
  await page.goto(target.href);
  const app = page.getByTestId('kr4-app');
  await expect(app).toHaveAttribute('data-source', 'LIVE');
  await expect(app).toHaveAttribute('data-refreshing', 'false');
  await expect(page.getByTestId('stock-detail')).toBeVisible();
  const base = new URL((await app.getAttribute('data-data-base'))!, page.url());
  const generation = (await app.getAttribute('data-generation-id'))!;
  expect(generation).toMatch(/^[0-9a-f]{64}$/);
  // Follow the generation actually rendered, never a possibly newer root pointer.
  const prefix = new URL(`generations/${generation}/`, base);
  const response = await page.request.get(new URL('manifest.json', prefix).href); expect(response.ok()).toBeTruthy();
  const manifest = await response.json() as PublicationManifest;
  expect(manifest.generationId).toBe(generation);
  expect(manifest.files['stocks.json'].sha256).toBe(await app.getAttribute('data-stocks-sha'));
  const detail = await authenticated<Detail>(page, prefix, manifest, `details/${code}.json`);
  await expect(page.locator('.dtl-company-identity')).toContainText(detail.summary.name);
  await expect(page.locator('.dtl-company-identity')).toContainText(code);
  if (detail.candidate) await expect(section(panel(page), '최종 조사 후보')).toBeVisible();
  return { detail, prefix, manifest, generation };
}
async function value(scope: Locator, label: string, expected: string, checked: string[]) {
  await expect(row(scope, label), label).toHaveText(expected); checked.push(label);
}
function contains(actual: string, expected: string, label: string, checked: string[]) {
  if (!expected) return;
  expect(norm(actual), label).toContain(norm(expected)); checked.push(label);
}

for (const code of representatives) test(`original-source information remains readable across all detail tabs: ${code}`, async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  const { detail: d, prefix, manifest, generation } = await original(page, code);
  const checked: string[] = [], s = d.summary, metrics = [s.m01RevGrowth, s.m02OpMargin, s.m03Per, s.m04Price6m];
  await expect(page.getByRole('tab')).toHaveCount(5);
  const summary = panel(page), composite = section(summary, '종합점수');
  await expect(composite.locator('.dtl-score')).toHaveText(s.compositeScore == null ? '미산출' : `${fixed(s.compositeScore, 1)}점`);
  await expect(composite.locator('.dtl-facts-grid > div').nth(1)).toContainText(s.rankOrder == null ? '보류' : `${integer(s.rankOrder)}위`);
  await expect(summary.locator('.dtl-compact-metric')).toHaveCount(4);
  const identity = section(summary, '종목 기본정보');
  for (const [label, expected] of [['종목코드', code], ['시장', s.market], ['업종', s.sector], ['상장일', s.listingDate || '확인 필요'], ['재무 기준일', s.asOfDate], ['주가 마감일', manifest.priceCutoffDate], ['원자료 출처', s.dataSource], ['금융업 여부', s.isFinancial ? '금융업' : '비금융업'], ['적자 여부', s.isLossMaking ? '적자' : '적자 표시 없음']]) await value(identity, label, expected, checked);
  if (d.candidate) {
    const candidate = section(summary, '최종 조사 후보'); await openDetails(candidate);
    const candidateFields = candidate.locator('details > dl.dtl-rows').first();
    const labels: Record<string, string> = { schemaVersion: '자료 형식 버전', policyVersion: '정책 버전', candidateRank: '최종후보 순위', sourceRank: '기존 종합순위', issuerId: '종목코드', name: '회사명', market: '시장', sector: '업종', compositeScore: '종합점수', financialSnapshotDate: '재무 기준일', priceCutoffDate: '주가 마감일', m01: '성장 지표 원본', m02: '수익성 지표 원본', m03: '가치 지표 원본', m04: '흐름 지표 원본', selectionFlags: '선정 확인 항목', researchStatus: '조사 상태', selectionReasonKo: '선정 사유', financialSafetyStatus: '재무현황 상태', financialSafetyReason: '재무현황 사유', financialSafetyBasis: '재무현황 근거', debtToEquityPct: '부채비율 (%)', currentRatioPct: '유동비율 (%)', accountingIdentityGapPct: '회계 일치 오차 (%)', valuationBand: '상대 PER 밴드 코드', valuationBandKo: '상대 PER 밴드', valuationPercentile: 'PER 시장 상대점수' };
    for (const [key, expected] of Object.entries(d.candidate)) {
      const cell = row(candidateFields, labels[key] || `추가 원본 항목 · ${key}`);
      if (Array.isArray(expected)) for (const leaf of expected) await expect(cell).toContainText(String(leaf));
      else if (expected && typeof expected === 'object') {
        const metricLabels: Record<string, string> = { id: '식별자', rawValue: '실제 값', percentileScore: '시장 상대점수', basis: '계산·판정 근거', asOfDate: '기준일', source: '자료 출처' };
        for (const [field, leaf] of Object.entries(expected)) await value(cell, metricLabels[field] || `추가 원본 항목 · ${field}`, leaf == null ? '미확보' : String(leaf), checked);
      } else await expect(cell).toHaveText(expected == null ? '미확보' : String(expected));
      checked.push(`candidate.${key}`);
    }
    await expect(candidate).toContainText(`FINAL #${d.candidate.candidateRank}`);
  } else await expect(section(summary, '최종 조사 후보')).toHaveCount(0);

  // Financial dialog uses exact decimal strings, not lossy displayed billions.
  await section(summary, '재무현황').getByRole('button', { name: /근거 및 참고정보/ }).click();
  const finance = page.getByRole('dialog', { name: '재무자료와 판정 근거' });
  await expect(finance).toBeVisible();
  if (d.financial) {
    const f = d.financial, facts = f.facts;
    await value(finance, '판정', `${f.status} · ${statuses[f.status]}`, checked);
    await value(finance, '판정 사유 코드', f.reasonCode, checked);
    await value(finance, '자료 기준일', s.asOfDate, checked);
    await value(finance, '연결·별도', f.scope === 'CFS' ? '연결 (CFS)' : f.scope === 'OFS' ? '별도 (OFS)' : f.scope || '미확보', checked);
    await value(finance, '부채비율', percent(f.debtToEquityPct, 2), checked);
    await value(finance, '유동비율', percent(f.currentRatioPct, 2), checked);
    await value(finance, '회계 일치 오차', percent(f.accountingIdentityGapPct, 3), checked);
    await value(finance, '기존 판정 기준', '자본 > 0 · 부채비율 ≤ 400% · 유동비율 ≥ 70% · 회계 오차 ≤ 5%', checked);
    await value(finance, '판정 근거 원문', f.basis || '미확보', checked);
    if (facts) {
      for (const [key, label] of Object.entries({ reportPeriod: '보고기간', filingDate: '실제 공시일', currency: '통화·단위', corporationCode: '법인번호', receiptNo: '공시 접수번호', correctionCheck: '정정 확인 근거', correctionStatus: '정정공시 상태 코드', correctionProofSha256: '정정 검증 SHA-256', sourceFile: 'OpenDART 원본', sourceZipSha256: '원본 ZIP SHA-256' })) await value(finance, label, String(facts[key as keyof typeof facts] || '미확보'), checked);
      for (const [key, label] of Object.entries({ assets: '자산총계', liabilities: '부채총계', equity: '자본총계', current_assets: '유동자산', current_liabilities: '유동부채' })) {
        const amount = facts.amountsExact && key in facts.amountsExact ? facts.amountsExact[key] : facts.amounts[key];
        const expected = amount == null ? '미확보' : `${BigInt(amount).toLocaleString('ko-KR')}${facts.currency === 'KRW' ? '원' : ` ${facts.currency}`}`;
        await value(finance, label, expected, checked);
      }
      await value(finance, '자료 검증', facts.verified ? '공식 원문·공시 검증 완료' : '원문 수치 · 검증 대기', checked);
      await value(finance, '정정 금액 일치 검증', facts.correctionReconciled ? '확인 (true)' : '미확인 (false)', checked);
      if (facts.sourceUrl) await expect(row(finance, '공식 공시 출처').getByRole('link')).toHaveAttribute('href', facts.sourceUrl);
      await expect(row(finance, '검증 보류 항목').locator('li')).toHaveCount(facts.validationIssues.length);
      for (const issue of facts.validationIssues) await expect(row(finance, '검증 보류 항목')).toContainText(issue === 'MIXED_PERIOD' ? '서로 다른 보고기간의 원자료가 충돌해 보류' : issue);
    }
  } else await value(finance, '판정', 'HOLD · 미확보', checked);
  await page.keyboard.press('Escape'); await expect(finance).toHaveCount(0);

  // Actual keyboard tab operation, modal source and publication dates.
  await page.getByRole('tab', { name: '요약', exact: true }).focus(); await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('tab', { name: '4지표', exact: true })).toBeFocused();
  await expect(page.getByRole('tab', { name: '4지표', exact: true })).toHaveAttribute('aria-selected', 'true');
  for (const m of metrics) {
    const card = section(panel(page), m.nameKo), cardText = await card.innerText();
    contains(cardText, m.description, `${m.id}.description`, checked);
    contains(cardText, m.isAvailable ? m.interpretation : m.reason || '검증 가능한 값 없음', `${m.id}.interpretationOrReason`, checked);
    for (const [label, expected] of [['실제 값 원본', m.rawValue == null ? 'N/A' : `${String(m.rawValue)} ${m.unit}`], ['시장 상대점수 원본', m.percentileScore == null ? '보류' : `${String(m.percentileScore)}점`], ['계산 기준', m.basis || '미확보'], ['출처', m.source || '미확보'], ['기준일', date(m.asOfDate)], ['주의', m.caution || '미확보'], ['가용 상태', m.isAvailable ? '사용 가능' : '보류'], ['결측·상태 사유', m.reason || '추가 사유 없음']]) await value(card, label, expected, checked);
    if (m.peerComparison) {
      const peer = m.peerComparison, group = card.locator('.dtl-peer');
      await expect(group).toContainText(peer.groupLabel); await expect(group).toContainText(`N=${peer.sampleSize}`);
      const basisLabels: Record<string, string> = { KRX_EXACT_SECTOR: 'KRX 세부업종', EXACT_KRX_SECTOR: 'KRX 세부업종', KRX_SECTOR: 'KRX 세부업종', STANDARD_SECTOR_FAMILY: '표준 피어그룹', SECTOR_FAMILY: '표준 피어그룹', STANDARD_FAMILY: '표준 피어그룹', INSUFFICIENT: '표본 부족' };
      await expect(group).toContainText(basisLabels[peer.basis] || peer.basis); checked.push(`${m.id}.peer.basis`);
      if (peer.basis === 'INSUFFICIENT' || peer.median == null) await expect(row(group, '직접 비교')).toContainText('중앙값 미산출');
      else if (!m.isAvailable || m.rawValue == null) await expect(row(group, '직접 비교')).toHaveText('실제 값 미확보 · 직접 비교 보류');
      else if (peer.deltaFromMedian != null && Math.abs(peer.deltaFromMedian) > 1e-12 && peer.betterThanMedian != null) await expect(row(group, '직접 비교')).toContainText(peer.betterThanMedian ? m.id === 'M03' ? '상대적으로 유리' : '상대 우위' : m.id === 'M03' ? '상대 부담' : '상대 열위');
      await value(group, '비교 기준', peer.higherIsBetter ? '큰 값이 유리' : '작은 값이 유리', checked);
      await value(group, '비교 사유', peer.reason || '추가 사유 없음', checked);
      await value(group, '중앙값 대비 비율', percent(peer.relativeToMedianPct), checked);
      await value(group, '중앙값 차이 원본', peer.deltaFromMedian == null ? '미확보' : `${fixed(peer.deltaFromMedian, m.id === 'M03' ? 2 : 1)}${m.id === 'M03' ? '배' : '%p'}`, checked);
      await value(group, '업종 중앙값', peer.basis === 'INSUFFICIENT' || peer.median == null ? '미산출' : m.id === 'M03' ? `${fixed(peer.median, 2)}배` : percent(peer.median), checked);
    } else await expect(card.locator('.dtl-peer')).toContainText('피어 엔진 정보 없음');
  }
  const band = section(panel(page), 'Stage5 상대 PER 밴드');
  for (const [label, expected] of [['원본 분류명', d.valuation?.labelKo || '미적용'], ['원본 짧은 표시명', d.valuation?.shortLabelKo || '미적용'], ['원본 분류 코드', d.valuation?.code || '미적용'], ['원본 백분위 (0–100)', d.valuation ? String(d.valuation.percentile) : '미적용'], ['순위 변경', d.valuation ? '없음 (false)' : '미적용']]) await value(band, label, expected, checked);

  await page.getByRole('tab', { name: '종합해석', exact: true }).click(); await openDetails(panel(page));
  const analysisSections: Record<string, string> = {
    regimeTitle: d.analysis.regimeTitle, thesis: d.analysis.regimeTitle,
    businessState: '사업 상태', priceBurden: '가격 부담', causeConfidence: '원인 확신도', futureUncertainty: '미래 불확실성',
    quarterlyTrend: '실제 분기 숫자가 어떻게 변했는지', quarterlySignal: '실제 분기 숫자가 어떻게 변했는지', quarterlyCoverage: '실제 분기 숫자가 어떻게 변했는지',
    industryContext: '이 산업에서 숫자를 읽는 법', industryKpiGuide: '이 산업에서 숫자를 읽는 법',
    combinationMeaning: '네 지표 조합의 숨은 뜻', causeInvestigation: '왜 이런 숫자가 나왔는지 최근 근거 점검', causeCandidates: '왜 이런 숫자가 나왔는지 최근 근거 점검',
    disclosureDiffNote: '공시 변화와 경영진 설명 비교', consequence: '이 조합이 이어질 때 생길 일',
    falsifiers: '이 해석이 틀렸다고 볼 조건', nextQuarterWatch: '다음 분기 확인할 연결고리', confidenceNote: '해석의 신뢰도와 한계',
  };
  for (const [key, expected] of Object.entries(d.analysis)) {
    if (key === 'evidenceHighlights') continue;
    const actual = await section(panel(page), analysisSections[key]).innerText();
    for (const leaf of Array.isArray(expected) ? expected : [expected]) if (typeof leaf === 'string') contains(actual, leaf, `analysis.${key}`, checked);
  }
  await expect(panel(page).locator('.dtl-evidence-row')).toHaveCount(d.analysis.evidenceHighlights.length);
  for (let i = 0; i < d.analysis.evidenceHighlights.length; i++) {
    const e = d.analysis.evidenceHighlights[i], article = panel(page).locator('.dtl-evidence-row').nth(i);
    await expect(article.getByRole('heading', { name: e.title, exact: true })).toBeVisible();
    await value(article, '자료 식별자', e.id || '미확보', checked);
    await expect(article).toContainText(e.bodyText || '원문 본문을 확보하지 못했습니다.');
  }
  const history = section(panel(page), '실제 분기 숫자가 어떻게 변했는지'), points = [...d.history.points].sort((a, b) => b.fiscalYear - a.fiscalYear || b.quarter - a.quarter);
  await expect(history.locator('.dtl-quarter-table tbody tr')).toHaveCount(points.length);
  await expect(history.locator('.dtl-quarter-list .dtl-quarter')).toHaveCount(points.length);
  for (let i = 0; i < points.length; i++) {
    const q = points[i], article = history.locator('.dtl-quarter-list .dtl-quarter').nth(i), cells = history.locator('.dtl-quarter-table tbody tr').nth(i).locator('th,td');
    await expect(cells.nth(0)).toHaveText(q.period);
    await expect(cells.nth(1)).toHaveText(q.revenue == null ? '미확보' : `${fixed(q.revenue / 100000000, 1)}억원`);
    await expect(cells.nth(2)).toHaveText(q.operatingIncome == null ? '미확보' : `${fixed(q.operatingIncome / 100000000, 1)}억원`);
    for (const [cell, source] of [[3, q.operatingMargin], [4, q.revenueYoY], [5, q.revenueQoQ]] as const) await expect(cells.nth(cell)).toHaveText(percent(source));
    await expect(article).toContainText(q.scope === 'CFS' ? '연결 (CFS)' : q.scope === 'OFS' ? '별도 (OFS)' : q.scope || '미확보');
    await expect(article.locator('header .dtl-chip').last()).toHaveText(q.basis === 'DIRECT_3M' ? '직접 분기' : q.revenue != null && q.basis ? '재구성 분기' : '결측·미확인');
    for (const [label, expected] of [['회계연도·분기', `${q.fiscalYear}년 ${q.quarter}분기`], ['매출 원본 (원)', integer(q.revenue)], ['영업이익 원본 (원)', integer(q.operatingIncome)], ['분기 계산 기준', q.basis || '미확보'], ['원본 파일', q.sourceFile || '미확보'], ['결측·검증 사유', q.reason || '추가 사유 없음']]) await value(article, label, expected, checked);
  }
  for (const [label, expected] of [['수록된 분기', `${points.length}개`], ['직접 분기 (DIRECT_3M)', d.history.directQuarterCount == null ? '미확보' : `${d.history.directQuarterCount}개`], ['누적 차감 등 재구성 분기', d.history.reconstructedQuarterCount == null ? '미확보' : `${d.history.reconstructedQuarterCount}개`], ['시계열 출처', d.history.source || '미확보'], ['생성 시각', d.history.generatedAt || '미확보'], ['비교 가능 분기 목록', d.history.comparablePoints?.length ? d.history.comparablePoints.map(q => q.period).join(' · ') : '미확보']]) await value(history, label, expected, checked);

  await page.getByRole('button', { name: '조사 체크·출처 전체 보기 →', exact: true }).click();
  await expect(page.getByRole('tab', { name: '체크·출처', exact: true })).toHaveAttribute('aria-selected', 'true');
  await openDetails(panel(page));
  const report = section(panel(page), '원래 기업 리포트 전체'), research = section(panel(page), '정량 밖에서 반드시 확인할 것');
  for (const [key, label] of Object.entries({ oneLineView: '한 줄 관점', quantSummary: '정량 요약', recentChanges: '최근 변화', changeReason: '변화 이유', businessQuality: '사업의 질', valuationView: '가치평가 관점', momentumView: '가격 흐름 관점' })) await value(report, label, d.report[key as keyof typeof d.report] as string, checked);
  for (const item of d.report.positiveFactors) contains(await row(report, '긍정 요인').innerText(), item, 'report.positiveFactors', checked);
  for (const key of ['riskFactors', 'counterArguments', 'nextVerificationConditions'] as const) for (const leaf of Array.isArray(d.report[key]) ? d.report[key] as string[] : [d.report[key] as string]) contains(await research.innerText(), leaf, `report.${key}`, checked);
  contains(await section(panel(page), '데이터 한계와 출처').innerText(), d.report.dataLimitations, 'report.dataLimitations', checked);
  await expect(report.locator('.dtl-eyebrow')).toHaveText(`종목 ${d.report.issuerId} · 갱신 ${d.report.updatedAt}`);
  if (d.evidence.disclosureDiff) {
    const diff = section(panel(page), '공시 변화 비교 원본');
    const labels: Record<string, string> = { available: '본문 비교 가능', scope: '비교 범위', currentTitle: '최근 공시 제목', previousTitle: '이전 공시 제목', newlyAppeared: '새로 등장한 내용', disappeared: '사라진 내용', strengthened: '강화된 내용', weakened: '약화된 내용', note: '비교 주의사항' };
    for (const [key, leaf] of Object.entries(d.evidence.disclosureDiff)) {
      const cell = row(diff, labels[key] || `추가 원본 항목 · ${key}`);
      if (Array.isArray(leaf)) { if (!leaf.length) await expect(cell).toHaveText('항목 없음'); else for (const text of leaf) await expect(cell).toContainText(String(text)); }
      else await expect(cell).toHaveText(leaf == null ? '미확보' : typeof leaf === 'boolean' ? leaf ? '예 (true)' : '아니요 (false)' : String(leaf) || '미확보');
      checked.push(`disclosureDiff.${key}`);
    }
  }
  const context = d.sourceStates?.context as { categories?: Record<string, { checkedAt?: string; lastAttemptAt?: string; lastAttemptError?: string | null; retainedCount?: number; itemObservedAt?: Record<string, string> }> } | undefined;
  for (const [key, name] of [['disclosures', '공시'], ['ir', '기업 IR'], ['official', '기업·공식자료'], ['news', '뉴스']] as const) {
    const items = d.evidence[key], category = section(panel(page), `${name} 원문·출처 (${items.length}건)`);
    await expect(category.locator('.dtl-evidence-row')).toHaveCount(items.length);
    if (!items.length) await expect(category).toContainText('미확보');
    const metadata = context?.categories?.[key];
    if (metadata) {
      await value(category, items.length ? '이 분류의 실제 확인 기준일' : '미확보 분류의 확인 기록', metadata.checkedAt ? date(metadata.checkedAt) : '확인 시각 미확보', checked);
      await value(category, '최근 확인 시도', metadata.lastAttemptAt ? date(metadata.lastAttemptAt) : '확인 시도 기록 없음', checked);
      await value(category, '최근 확인 시도 오류', metadata.lastAttemptError || '기록된 시도 오류 없음', checked);
      await value(category, '실패·부분 수집 후 유지한 자료', metadata.retainedCount == null ? '유지 여부 미확보' : `${integer(metadata.retainedCount)}건`, checked);
    }
    for (let i = 0; i < items.length; i++) {
      const item = items[i], article = category.locator('.dtl-evidence-row').nth(i);
      await expect(article.getByRole('heading', { name: item.title, exact: true })).toBeVisible();
      await expect(article).toContainText(item.source || '발표일·출처 미확보');
      if (item.publishedAt) await expect(article).toContainText(item.publishedAt);
      if (metadata) {
        const dates = metadata.itemObservedAt || {}, observed = dates[`receipt:${item.receiptNo}`] || dates[`${item.kind}:${item.id}:${item.url}:${item.title}`];
        await value(article, '이 자료의 실제 확인 시각', observed ? date(observed) : '확인 시각 미확보', checked);
      }
      await value(article, '자료 식별자', item.id || '미확보', checked);
      await value(article, '출처 등급', item.sourceTier || '미확보', checked);
      await value(article, '공시 접수번호', item.receiptNo || '미확보', checked);
      if (item.url) await expect(row(article, '원문 주소').getByRole('link')).toHaveAttribute('href', item.url);
      await expect(article.locator('details .dtl-prose')).toHaveText(item.bodyText || '원문 본문을 확보하지 못했습니다. 제목만으로 원인 확정을 하지 않습니다.');
    }
  }
  await page.getByRole('button', { name: '데이터 갱신 상태 열기' }).click();
  const update = page.getByRole('dialog', { name: '데이터 갱신 상태' });
  await expect(update).toContainText('LIVE · GitHub에 공개된 자료 수신');
  await value(update, '화면의 재무 기준일', s.asOfDate, checked); await value(update, '공개자료 재무 기준일', manifest.snapshotDate, checked); await value(update, '주가 마감일', manifest.priceCutoffDate, checked);
  await update.getByRole('button', { name: '데이터 갱신 상태 닫기' }).click(); await expect(update).toHaveCount(0);
  expect(errors).toEqual([]);
  const output = resolve('test-results', 'detail-parity'); mkdirSync(output, { recursive: true });
  writeFileSync(resolve(output, `${code}.json`), JSON.stringify({ status: 'PASS', classification: 'REAL_PUBLISHED_MARKET_SOURCE_TO_DOM', url: page.url(), generation, sourceCommit: manifest.sourceCommit, checkedAt: new Date().toISOString(), runnerPlatform: process.platform, browserChannel: process.env.KR4_BROWSER_CHANNEL || 'chromium', physicalLgGramVerified: false, detailSha256: manifest.files[`details/${code}.json`].sha256, assertions: checked.length, checked, quarterCount: points.length, evidenceCount: d.evidence.all.length, financialStatus: d.financial?.status || 'HOLD', candidatePresent: Boolean(d.candidate) }, null, 2));
});
