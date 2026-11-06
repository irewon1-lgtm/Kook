import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import Detail from '../src/screens/Detail';
import type { Detail as StockDetail, MarketState, PublicationManifest } from '../src/types';
import { recordedTar } from './recorded-fixture';

// Genuine recorded positive candidate; only the current reception state is controlled.
const recorded = recordedTar('tests/fixtures/positive-candidate-recorded-20261105.tar.gz', 'f37f825a02c33251e713316f79cb40a11add85051882afbba85aba3858e4d679');
const code = '142210';
const oldDetail = JSON.parse(recorded.get(`details/${code}.json`)!) as StockDetail;
const manifest = JSON.parse(recorded.get('manifest.json')!) as PublicationManifest;
const financial = JSON.parse(recorded.get('financial.json')!);
const candidates = JSON.parse(recorded.get('candidates.json')!);
const originalDetail = JSON.stringify(oldDetail);
const valid: MarketState = {
  manifest, stocks: JSON.parse(recorded.get('stocks.json')!), source: 'CACHE',
  lastSuccessAt: '', lastAttemptAt: '', error: null, refreshing: false,
  financialByCode: financial.records, financialDate: financial.snapshotDate, financialError: null,
  candidatesByCode: candidates.records, candidatesError: null,
};
function render(patch: Partial<MarketState> = {}, initialTab = 0) {
  const html = renderToStaticMarkup(createElement(Detail, {
    detail: oldDetail, state: { ...valid, ...patch }, initialTab,
    onBack() {}, onStock() {}, onToggleFavorite() {}, isFavorite: false, onRefresh() {},
  }));
  expect(JSON.stringify(oldDetail)).toBe(originalDetail);
  return html;
}
function expectHeld(html: string) {
  expect(html).toContain('safety-badge safety-hold');
  expect(html).not.toContain('safety-badge safety-pass');
  expect(html).not.toContain('FINAL #');
  expect(html).toContain('87.8');
  expect(html).toContain('기존 4지표 순위');
}

describe('mounted old detail obeys current validated optional reception state', () => {
  it('retains the genuine active PASS and complete original candidate fields when current maps and dates validate', () => {
    expect(oldDetail.financial?.status).toBe('PASS');
    expect(oldDetail.candidate?.candidateRank).toBe(6);
    const html = render();
    expect(html).toContain('safety-badge safety-pass');
    expect(html).toContain('FINAL #6');
    for (const value of [oldDetail.candidate!.selectionReasonKo, ...oldDetail.candidate!.selectionFlags,
      oldDetail.candidate!.policyVersion, oldDetail.candidate!.financialSnapshotDate,
      oldDetail.candidate!.priceCutoffDate, oldDetail.financial!.facts!.reportPeriod]) {
      expect(html).toContain(String(value));
    }
  });
  it('immediately holds old PASS and FINAL after same-date financial validation fails and current maps clear', () => {
    expectHeld(render({ financialByCode: {}, financialError: 'CONTROLLED_FINANCIAL_INVALID', candidatesByCode: {}, candidatesError: 'CONTROLLED_DEPENDENCY_HOLD' }));
  });
  it('does not fall back to the old detail while current same-date optional maps are pending', () => {
    expectHeld(render({ financialByCode: {}, financialError: null, candidatesByCode: {}, candidatesError: undefined }));
  });
  it('does not trust a leftover PASS map when current financial validation reports an error', () => {
    expectHeld(render({ financialError: 'CONTROLLED_FINANCIAL_INVALID' }));
  });
  it('holds old optional records and identifies unknown provenance when the active manifest date advances', () => {
    const html = render({ manifest: { ...manifest, snapshotDate: '2026-11-06', priceCutoffDate: '2026-11-06' } });
    expectHeld(html);
    expect(html).toContain('데이터 출처 확인 중');
  });
  it('requires the financial publication date to match the current quant date', () => {
    expectHeld(render({ financialDate: '2026-11-04' }));
  });
  it.each([undefined, 'CONTROLLED_CANDIDATE_INVALID'])('holds old FINAL while candidate reception is pending or failed (%s)', candidatesError => {
    const html = render({ candidatesError });
    expect(html).toContain('safety-badge safety-pass');
    expect(html).not.toContain('FINAL #');
  });
  it('does not revive an old detail candidate when the validated current candidate map has no record', () => {
    const html = render({ candidatesByCode: {} });
    expect(html).toContain('safety-badge safety-pass');
    expect(html).not.toContain('FINAL #');
  });
  it.each(['FAIL', 'HOLD', 'NOT_APPLICABLE'] as const)('shows the current %s financial status without an old FINAL or changed quant score', status => {
    const html = render({ financialByCode: { [code]: { ...financial.records[code], status } } });
    expect(html).toContain(`safety-badge safety-${status.toLowerCase()}`);
    expect(html).not.toContain('FINAL #');
    expect(html).toContain('87.8');
  });
  it('replaces stale VALID optional source states with the current validation failure on the sources tab', () => {
    const html = render({ financialByCode: {}, financialError: 'CONTROLLED_FINANCIAL_INVALID', candidatesByCode: {}, candidatesError: undefined }, 3);
    expect(html).toContain('CONTROLLED_FINANCIAL_INVALID');
    expect(html).toContain('HOLD');
    expect(html).toContain(oldDetail.report.dataLimitations);
  });
  it('holds old PASS and FINAL for a CONTROLLED same-date corrected current core while the genuine old detail is mounted', () => {
    const correctedStock = { ...oldDetail.summary, rankOrder: 7, compositeScore: 86.5 };
    const correctedCandidate = { ...candidates.records[code], sourceRank: 7, compositeScore: 86.5 };
    const html = render({ stocks: valid.stocks.map(stock => stock.issuerId === code ? correctedStock : stock), candidatesByCode: { [code]: correctedCandidate } });
    expectHeld(html);
    expect(html).toContain('데이터 출처 확인 중');
  });
  it('detects a CONTROLLED same-date nested original metric correction even when rank and score are unchanged', () => {
    const correctedStock = { ...oldDetail.summary, m01RevGrowth: { ...oldDetail.summary.m01RevGrowth, rawValue: 65.7 } };
    expectHeld(render({ stocks: valid.stocks.map(stock => stock.issuerId === code ? correctedStock : stock) }));
  });
  it('keeps valid original PASS and FINAL when JSON object key order alone differs at every level', () => {
    function reorder(value: unknown): unknown {
      if (Array.isArray(value)) return value.map(reorder);
      if (value !== null && typeof value === 'object') return Object.fromEntries(Object.entries(value).reverse().map(([key, item]) => [key, reorder(item)]));
      return value;
    }
    const html = render({ stocks: valid.stocks.map(stock => stock.issuerId === code ? reorder(stock) as typeof stock : stock) });
    expect(html).toContain('safety-badge safety-pass');
    expect(html).toContain('FINAL #6');
    expect(html).not.toContain('데이터 출처 확인 중');
  });
  it.each([{ sourceRank: 7 }, { compositeScore: 86.5 }])('holds a CONTROLLED candidate source mismatch independently of matching current core (%o)', correction => {
    const html = render({ candidatesByCode: { [code]: { ...candidates.records[code], ...correction } } });
    expect(html).toContain('safety-badge safety-pass');
    expect(html).not.toContain('FINAL #');
  });
});
