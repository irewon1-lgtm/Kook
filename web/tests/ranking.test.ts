import { describe, expect, it } from 'vitest';
import { rankStocks, getMetric } from '../src/ranking';
import type { Metric, Stock, MetricId } from '../src/types';

function metric(id: MetricId, score: number | null, available = score !== null): Metric {
  return { id, nameKo: id, rawValue: score, percentileScore: score, unit: '%', isAvailable: available,
    reason: available ? null : 'CONTROLLED_UNIT_MISSING', description: '', interpretation: '', caution: '',
    source: 'CONTROLLED_UNIT', basis: 'CONTROLLED_UNIT', asOfDate: '2026-11-05', peerComparison: null };
}
function stock(code: string, scores: Array<number | null>): Stock {
  return { issuerId: code, name: '단위 시험 종목', market: 'KOSPI', sector: '', listingDate: '',
    isFinancial: false, isLossMaking: false, m01RevGrowth: metric('M01', scores[0]),
    m02OpMargin: metric('M02', scores[1]), m03Per: metric('M03', scores[2]), m04Price6m: metric('M04', scores[3]),
    compositeScore: null, rankOrder: null, isCompositeComplete: false, asOfDate: '2026-11-05',
    dataSource: 'CONTROLLED_UNIT', status: 'REAL' };
}
describe('Original Android global subset ranking contract', () => {
  it('uses equal selected percentiles, including an actual zero score', () => {
    const rows = [stock('000001', [0, 100, 20, 40])];
    expect(rankStocks(rows, ['M01', 'M02']).get('000001')).toEqual({ issuerId: '000001', score: 50, rank: 1, selectedMetricCount: 2, isEligible: true });
  });
  it('keeps missing stocks visible with null score and rank', () => {
    const ranks = rankStocks([stock('000001', [null, 80, 50, 10])], ['M01', 'M02']);
    expect(ranks.size).toBe(1);
    expect(ranks.get('000001')).toMatchObject({ score: null, rank: null, isEligible: false });
  });
  it('can rank an incomplete four-metric stock using its available subset', () => {
    expect(rankStocks([stock('000001', [null, 80, 50, 10])], ['M02']).get('000001')?.score).toBe(80);
  });
  it('breaks equal scores by original issuer code, not array or locale order', () => {
    const ranks = rankStocks([stock('0001A0', [50, 0, 0, 0]), stock('000020', [50, 0, 0, 0])], ['M01']);
    expect(ranks.get('000020')?.rank).toBe(1); expect(ranks.get('0001A0')?.rank).toBe(2);
  });
  it('requires at least one metric and rejects unknown metric ids', () => {
    expect(() => rankStocks([], [])).toThrow();
    expect(() => rankStocks([], ['M05' as MetricId])).toThrow();
  });
  it('uses set semantics for selected ids', () => {
    expect(rankStocks([stock('000001', [20, 80, 0, 0])], ['M01', 'M01', 'M02']).get('000001')?.score).toBe(50);
  });
  it('never uses composite, existing rank, financial-sector flag or status to change a subset rank', () => {
    const input = [stock('000001', [20, 80, 0, 0]), stock('000002', [30, 80, 0, 0])];
    const before = rankStocks(input, ['M01']);
    const after = rankStocks(input.map(s => ({ ...s, isFinancial: !s.isFinancial, status: 'MISSING', compositeScore: 100, rankOrder: 1 })), ['M01']);
    expect([...after]).toEqual([...before]);
  });
  it('retrieves the original metric without changing its raw data', () => {
    const s = stock('000001', [0, 10, 20, 30]); expect(getMetric(s, 'M01')).toBe(s.m01RevGrowth);
  });
});
